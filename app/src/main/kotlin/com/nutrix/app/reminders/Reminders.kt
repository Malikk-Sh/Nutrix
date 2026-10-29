package com.nutrix.app.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nutrix.app.MainActivity
import com.nutrix.app.R
import com.nutrix.app.core.ApplicationScope
import com.nutrix.app.core.datastore.ReminderConfig
import com.nutrix.app.core.datastore.ReminderType
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.datastore.UserSettings
import com.nutrix.app.core.datastore.supportsAdaptive
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.core.ui.labelRes
import com.nutrix.app.data.repository.DrinkRepository
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.data.repository.NoteRepository
import com.nutrix.app.data.repository.SymptomRepository
import com.nutrix.app.data.repository.WeightRepository
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.navigation.AppActions
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

object NotificationChannels {
    const val REMINDERS = "reminders"

    fun create(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(REMINDERS, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_reminders_desc)
            },
        )
    }
}

/** Schedules local reminders with WorkManager (survives reboots, respects quiet hours). */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val routines: RoutineRepository,
) {
    private val wm get() = WorkManager.getInstance(context)

    suspend fun rescheduleAll() {
        val s = settings.current()
        val routine = routineFor(s.reminders)
        val ignores = settings.reminderIgnores.first()
        ReminderType.entries.forEach { schedule(it, s, routine, ignores[it] ?: 0) }
    }

    /** The diary is read only when a reminder actually follows the routine. */
    private suspend fun routineFor(configs: List<ReminderConfig>): Routine =
        if (configs.any { it.enabled && it.adaptive && it.type.supportsAdaptive }) routines.current() else Routine()

    /** Next planned reminder: learned times for adaptive ones, quieter after reminders were ignored in a row. */
    fun nextFor(type: ReminderType, s: UserSettings, routine: Routine, ignoredInARow: Int, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        val config = s.reminders.firstOrNull { it.type == type } ?: return null
        return ReminderPlanner.nextTrigger(config, quiet(s), now) { AdaptivePlan.timesFor(config, routine, ignoredInARow, it) }
    }

    suspend fun schedule(type: ReminderType, s: UserSettings? = null) {
        val current = s ?: settings.current()
        schedule(type, current, routineFor(current.reminders.filter { it.type == type }), settings.reminderIgnores.first()[type] ?: 0)
    }

    private fun schedule(type: ReminderType, s: UserSettings, routine: Routine, ignoredInARow: Int) {
        if (type == ReminderType.AFTER_MEAL) {
            // Planned per meal by AfterMealCheckIn; switching it off drops a pending question.
            if (s.reminders.firstOrNull { it.type == type }?.enabled != true) wm.cancelUniqueWork(workName(type))
            return
        }
        val next = nextFor(type, s, routine, ignoredInARow)
        if (next == null) {
            wm.cancelUniqueWork(workName(type))
            return
        }
        val delay = Duration.between(ZonedDateTime.now(), next).toMillis().coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ReminderWorker.KEY_TYPE to type.name))
            .addTag(TAG)
            .build()
        wm.enqueueUniqueWork(workName(type), ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun snooze(type: ReminderType) {
        val s = settings.current()
        val at = ReminderPlanner.adjustForQuiet(ZonedDateTime.now().plusMinutes(s.snoozeMinutes.toLong()), quiet(s))
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(Duration.between(ZonedDateTime.now(), at).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ReminderWorker.KEY_TYPE to type.name, ReminderWorker.KEY_SNOOZED to true))
            .addTag(TAG)
            .build()
        wm.enqueueUniqueWork("${workName(type)}_snooze", ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll() = wm.cancelAllWorkByTag(TAG)

    companion object {
        const val TAG = "reminders"
        fun workName(type: ReminderType) = "reminder_${type.name}"
        fun quiet(s: UserSettings) = QuietHours(s.quietHoursEnabled, s.quietStartMinutes, s.quietEndMinutes)
    }
}

@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsRepository,
    private val scheduler: ReminderScheduler,
    private val notifier: ReminderNotifier,
    private val routines: RoutineRepository,
    private val meals: MealRepository,
    private val drinks: DrinkRepository,
    private val weights: WeightRepository,
    private val symptoms: SymptomRepository,
    private val notes: NoteRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val type = runCatching { ReminderType.valueOf(inputData.getString(KEY_TYPE).orEmpty()) }.getOrNull() ?: return Result.success()
        val snoozed = inputData.getBoolean(KEY_SNOOZED, false)
        val s = settings.current()
        val config = s.reminders.firstOrNull { it.type == type }
        val now = ZonedDateTime.now()
        val quiet = ReminderScheduler.quiet(s)
        if (config?.enabled == true && !quiet.contains(now.hour * 60 + now.minute)) {
            if (type == ReminderType.AFTER_MEAL) {
                askAfterMeal(inputData.getLong(KEY_MEAL_ID, 0))
                return Result.success()
            }
            val (notify, detail) = decide(config, s, now)
            if (notify) notifier.show(type, detail)
        }
        // The regular chain continues; a snoozed one-off does not re-plan it.
        if (!snoozed) scheduler.schedule(type)
        return Result.success()
    }

    /**
     * Whether reminding now is useful, with a routine-based reason when there is one. Skips reminders
     * that would be pointless right now (never to blame the user).
     */
    private suspend fun decide(config: ReminderConfig, s: UserSettings, now: ZonedDateTime): Pair<Boolean, String?> {
        val today = TimeUtils.todayEpochDay()
        val minute = now.hour * 60 + now.minute
        val routine = if (config.adaptive && config.type.supportsAdaptive) routines.current(today) else Routine()
        fun time(m: Int) = Formatters.time(TimeUtils.timeOfMinutes(m))
        return when (config.type) {
            ReminderType.MEAL -> {
                val slot = AdaptivePlan.slotFor(routine, now.toLocalDate(), minute) ?: return true to null
                val logged = meals.getRange(today, today).map { TimeUtils.minutesOfDay(TimeUtils.localDateTime(it.meal.epochMillis, it.meal.zoneId).toLocalTime()) }
                AdaptivePlan.mealMissing(slot, logged, minute) to applicationContext.getString(R.string.notif_meal_adaptive, time(slot.minute))
            }
            ReminderType.WATER -> {
                val total = drinks.totalForDay(today)
                val target = s.waterGoalMl ?: routine.fluidMedianMl
                val expected = AdaptivePlan.expectedFluid(routine, s.waterGoalMl, minute)
                if (target == null || expected == null) return (s.waterGoalMl?.let { total < it } ?: true) to null
                val text = if (s.waterGoalMl != null) R.string.notif_water_pace_goal else R.string.notif_water_pace_usual
                AdaptivePlan.fluidBehind(total, expected, target) to applicationContext.getString(text, expected, total)
            }
            ReminderType.WEIGHT -> weights.observeDay(today).first().isEmpty() to
                routine.weighMinute?.let { applicationContext.getString(R.string.notif_weight_adaptive, time(it)) }
            ReminderType.MISSED_ENTRIES -> {
                (meals.getRange(today, today).isEmpty() && drinks.getRange(today, today).isEmpty() &&
                    symptoms.getRange(today, today).isEmpty() && notes.getRange(today, today).isEmpty()) to null
            }
            ReminderType.WELLBEING, ReminderType.AFTER_MEAL -> true to null
        }
    }

    /** Once a day at most, only while the meal still has no feeling: "normal" is never assumed. */
    private suspend fun askAfterMeal(mealId: Long) {
        val meal = meals.get(mealId)?.meal ?: return
        if (meal.feelings.isNotEmpty()) return
        val today = TimeUtils.todayEpochDay()
        val ignores = settings.reminderIgnores.first()[ReminderType.AFTER_MEAL] ?: 0
        if (!AfterMealPlan.mayAsk(today, settings.afterMealAskedDay(), ignores)) return
        settings.setAfterMealAskedDay(today)
        notifier.showAfterMeal(meal)
    }

    companion object {
        const val KEY_TYPE = "type"
        const val KEY_SNOOZED = "snoozed"
        const val KEY_MEAL_ID = "meal_id"
    }
}

@Singleton
class ReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    /** [detail] replaces the generic text when the reminder is based on the user's routine. */
    suspend fun show(type: ReminderType, detail: String? = null) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val (title, text, action) = when (type) {
            ReminderType.MEAL -> Triple(R.string.notif_meal_title, R.string.notif_meal_text, AppActions.ADD_FOOD_CAMERA)
            ReminderType.WATER -> Triple(R.string.notif_water_title, R.string.notif_water_text, AppActions.ADD_WATER)
            ReminderType.WEIGHT -> Triple(R.string.notif_weight_title, R.string.notif_weight_text, AppActions.ADD_WEIGHT)
            ReminderType.WELLBEING -> Triple(R.string.notif_wellbeing_title, R.string.notif_wellbeing_text, AppActions.ADD_SYMPTOM)
            ReminderType.MISSED_ENTRIES -> Triple(R.string.notif_missed_title, R.string.notif_missed_text, AppActions.OPEN_TODAY)
            ReminderType.AFTER_MEAL -> return
        }
        val id = notificationId(type)
        recordIfUnanswered(type, id)
        val body = detail ?: context.getString(text)
        val builder = base(type, id, action)
            .setContentTitle(context.getString(title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .addAction(0, context.getString(R.string.notif_action_snooze), actionIntent(ReminderActionReceiver.ACTION_SNOOZE, type, id))
        if (type == ReminderType.WATER) {
            val volume = settings.current().quickVolumes.firstOrNull { it >= 200 } ?: 250
            builder.addAction(0, context.getString(R.string.notif_action_water, volume), actionIntent(ReminderActionReceiver.ACTION_ADD_WATER, type, id, volume))
        }
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    /** "How was the meal?" with one-tap answers; "don't remember" records nothing, so the meal stays unrated. */
    suspend fun showAfterMeal(meal: MealEntity) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val type = ReminderType.AFTER_MEAL
        val id = notificationId(type)
        recordIfUnanswered(type, id)
        val name = meal.title?.takeIf { it.isNotBlank() } ?: meal.items.firstOrNull() ?: context.getString(meal.mealType.labelRes)
        val time = Formatters.time(TimeUtils.localDateTime(meal.epochMillis, meal.zoneId).toLocalTime())
        val body = context.getString(R.string.notif_after_meal_text, name, time)
        val builder = base(type, id, AppActions.openMeal(meal.id))
            .setContentTitle(context.getString(R.string.notif_after_meal_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .addAction(0, context.getString(R.string.notif_action_feel_normal), actionIntent(ReminderActionReceiver.ACTION_FEEL_NORMAL, type, id, mealId = meal.id))
            .addAction(0, context.getString(R.string.notif_action_feel_mark), openIntent(type, id, AppActions.openMeal(meal.id)))
            .addAction(0, context.getString(R.string.notif_action_feel_unsure), actionIntent(ReminderActionReceiver.ACTION_UNSURE, type, id))
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    private fun base(type: ReminderType, id: Int, action: String) = NotificationCompat.Builder(context, NotificationChannels.REMINDERS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentIntent(openIntent(type, id, action))
        .setDeleteIntent(actionIntent(ReminderActionReceiver.ACTION_DISMISSED, type, id))
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    /** A reminder still in the shade when the next one of its kind comes was left unanswered. */
    private suspend fun recordIfUnanswered(type: ReminderType, id: Int) {
        val shown = runCatching { context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == id } }.getOrDefault(false)
        if (shown) settings.recordReminderIgnored(type)
    }

    private fun openIntent(type: ReminderType, id: Int, action: String): PendingIntent = PendingIntent.getActivity(
        context, id,
        Intent(context, MainActivity::class.java)
            .putExtra(AppActions.EXTRA_ACTION, action)
            .putExtra(EXTRA_REMINDER, type.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionIntent(action: String, type: ReminderType, id: Int, volume: Int = 0, mealId: Long = 0): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id * 10 + action.hashCode() % 10,
            Intent(context, ReminderActionReceiver::class.java)
                .setAction(action)
                .putExtra(ReminderActionReceiver.EXTRA_TYPE, type.name)
                .putExtra(ReminderActionReceiver.EXTRA_ID, id)
                .putExtra(ReminderActionReceiver.EXTRA_VOLUME, volume)
                .putExtra(ReminderActionReceiver.EXTRA_MEAL_ID, mealId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val NOTIFICATION_BASE = 4100
        /** Set on the content intent so opening a reminder counts as answering it. */
        const val EXTRA_REMINDER = "nutrix_reminder"
        fun notificationId(type: ReminderType) = NOTIFICATION_BASE + type.ordinal
    }
}

@AndroidEntryPoint
class ReminderActionReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: ReminderScheduler
    @Inject lateinit var feedback: ReminderFeedback
    @Inject lateinit var drinks: DrinkRepository
    @Inject lateinit var meals: MealRepository
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val type = runCatching { ReminderType.valueOf(intent.getStringExtra(EXTRA_TYPE).orEmpty()) }.getOrNull() ?: return
        val id = intent.getIntExtra(EXTRA_ID, 0)
        NotificationManagerCompat.from(context).cancel(id)
        val pending = goAsync()
        scope.launch {
            try {
                if (intent.action == ACTION_DISMISSED) {
                    feedback.ignored(type)
                } else {
                    feedback.engaged(type)
                    when (intent.action) {
                        ACTION_SNOOZE -> scheduler.snooze(type)
                        ACTION_ADD_WATER -> drinks.quickAdd(DrinkType.WATER, intent.getIntExtra(EXTRA_VOLUME, 250))
                        ACTION_FEEL_NORMAL -> meals.markFeelingNormal(intent.getLongExtra(EXTRA_MEAL_ID, 0))
                        // ACTION_UNSURE: an answer, but nothing is recorded, so the meal stays unrated.
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SNOOZE = "com.nutrix.app.SNOOZE"
        const val ACTION_ADD_WATER = "com.nutrix.app.ADD_WATER"
        const val ACTION_DISMISSED = "com.nutrix.app.REMINDER_DISMISSED"
        const val ACTION_FEEL_NORMAL = "com.nutrix.app.FEEL_NORMAL"
        const val ACTION_UNSURE = "com.nutrix.app.FEEL_UNSURE"
        const val EXTRA_TYPE = "type"
        const val EXTRA_ID = "id"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_MEAL_ID = "meal_id"
    }
}
