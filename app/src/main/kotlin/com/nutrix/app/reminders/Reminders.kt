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
import com.nutrix.app.core.datastore.ReminderType
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.datastore.UserSettings
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.data.repository.DrinkRepository
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.data.repository.NoteRepository
import com.nutrix.app.data.repository.SymptomRepository
import com.nutrix.app.data.repository.WeightRepository
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
) {
    private val wm get() = WorkManager.getInstance(context)

    suspend fun rescheduleAll() {
        val s = settings.current()
        ReminderType.entries.forEach { schedule(it, s) }
    }

    fun nextFor(type: ReminderType, s: UserSettings, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        val config = s.reminders.firstOrNull { it.type == type } ?: return null
        return ReminderPlanner.nextTrigger(config, quiet(s), now)
    }

    fun schedule(type: ReminderType, s: UserSettings) {
        val next = nextFor(type, s)
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
        if (config?.enabled == true && !quiet.contains(now.hour * 60 + now.minute) && shouldNotify(type, s)) {
            notifier.show(type)
        }
        // The regular chain continues; a snoozed one-off does not re-plan it.
        if (!snoozed) scheduler.schedule(type, s)
        return Result.success()
    }

    /** Skips reminders that would be pointless right now (never to blame the user). */
    private suspend fun shouldNotify(type: ReminderType, s: UserSettings): Boolean {
        val today = TimeUtils.todayEpochDay()
        return when (type) {
            ReminderType.WATER -> s.waterGoalMl?.let { goal -> drinks.totalForDay(today) < goal } ?: true
            ReminderType.WEIGHT -> weights.observeDay(today).first().isEmpty()
            ReminderType.MISSED_ENTRIES -> {
                meals.getRange(today, today).isEmpty() && drinks.getRange(today, today).isEmpty() &&
                    symptoms.getRange(today, today).isEmpty() && notes.getRange(today, today).isEmpty()
            }
            ReminderType.MEAL, ReminderType.WELLBEING -> true
        }
    }

    companion object {
        const val KEY_TYPE = "type"
        const val KEY_SNOOZED = "snoozed"
    }
}

@Singleton
class ReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    suspend fun show(type: ReminderType) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val (title, text, action) = when (type) {
            ReminderType.MEAL -> Triple(R.string.notif_meal_title, R.string.notif_meal_text, AppActions.ADD_FOOD_CAMERA)
            ReminderType.WATER -> Triple(R.string.notif_water_title, R.string.notif_water_text, AppActions.ADD_WATER)
            ReminderType.WEIGHT -> Triple(R.string.notif_weight_title, R.string.notif_weight_text, AppActions.ADD_WEIGHT)
            ReminderType.WELLBEING -> Triple(R.string.notif_wellbeing_title, R.string.notif_wellbeing_text, AppActions.ADD_SYMPTOM)
            ReminderType.MISSED_ENTRIES -> Triple(R.string.notif_missed_title, R.string.notif_missed_text, AppActions.OPEN_TODAY)
        }
        val id = NOTIFICATION_BASE + type.ordinal
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).putExtra(AppActions.EXTRA_ACTION, action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, NotificationChannels.REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, context.getString(R.string.notif_action_snooze), actionIntent(ReminderActionReceiver.ACTION_SNOOZE, type, id))
        if (type == ReminderType.WATER) {
            val volume = settings.current().quickVolumes.firstOrNull { it >= 200 } ?: 250
            builder.addAction(0, context.getString(R.string.notif_action_water, volume), actionIntent(ReminderActionReceiver.ACTION_ADD_WATER, type, id, volume))
        }
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    private fun actionIntent(action: String, type: ReminderType, id: Int, volume: Int = 0): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id * 10 + action.hashCode() % 10,
            Intent(context, ReminderActionReceiver::class.java)
                .setAction(action)
                .putExtra(ReminderActionReceiver.EXTRA_TYPE, type.name)
                .putExtra(ReminderActionReceiver.EXTRA_ID, id)
                .putExtra(ReminderActionReceiver.EXTRA_VOLUME, volume),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val NOTIFICATION_BASE = 4100
    }
}

@AndroidEntryPoint
class ReminderActionReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: ReminderScheduler
    @Inject lateinit var drinks: DrinkRepository
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val type = runCatching { ReminderType.valueOf(intent.getStringExtra(EXTRA_TYPE).orEmpty()) }.getOrNull() ?: return
        val id = intent.getIntExtra(EXTRA_ID, 0)
        NotificationManagerCompat.from(context).cancel(id)
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_SNOOZE -> scheduler.snooze(type)
                    ACTION_ADD_WATER -> drinks.quickAdd(DrinkType.WATER, intent.getIntExtra(EXTRA_VOLUME, 250))
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SNOOZE = "com.nutrix.app.SNOOZE"
        const val ACTION_ADD_WATER = "com.nutrix.app.ADD_WATER"
        const val EXTRA_TYPE = "type"
        const val EXTRA_ID = "id"
        const val EXTRA_VOLUME = "volume"
    }
}
