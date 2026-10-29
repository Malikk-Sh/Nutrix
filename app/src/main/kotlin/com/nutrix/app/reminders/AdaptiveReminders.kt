package com.nutrix.app.reminders

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.nutrix.app.core.ApplicationScope
import com.nutrix.app.core.datastore.ReminderType
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.data.repository.DataChangeNotifier
import com.nutrix.app.data.repository.DrinkRepository
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.data.repository.WeightRepository
import com.nutrix.app.database.entity.MealEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The routine learned from the diary. Recomputed on demand: four weeks of records are cheap to scan. */
@Singleton
class RoutineRepository @Inject constructor(
    private val meals: MealRepository,
    private val drinks: DrinkRepository,
    private val weights: WeightRepository,
    notifier: DataChangeNotifier,
) {
    suspend fun current(today: Long = TimeUtils.todayEpochDay()): Routine {
        val window = RoutineLearner.window(today)
        return RoutineLearner.learn(
            meals.getRange(window.first, window.last).map { it.meal },
            drinks.getRange(window.first, window.last),
            weights.getRange(window.first, window.last),
            window,
        )
    }

    val routine: Flow<Routine> = notifier.changes.onStart { emit(Unit) }.conflate().map { current() }
}

/** What the user does with reminders: ignoring one repeatedly makes it quieter, any answer restores it. */
@Singleton
class ReminderFeedback @Inject constructor(
    private val settings: SettingsRepository,
    private val scheduler: ReminderScheduler,
    @ApplicationScope private val scope: CoroutineScope,
) {
    suspend fun ignored(type: ReminderType) {
        settings.recordReminderIgnored(type)
        scheduler.schedule(type)
    }

    suspend fun engaged(type: ReminderType) {
        if ((settings.reminderIgnores.first()[type] ?: 0) == 0) return
        settings.resetReminderIgnores(type)
        scheduler.schedule(type)
    }

    /** For callers without a scope that outlives them, such as an activity handling a notification tap. */
    fun opened(type: ReminderType) {
        scope.launch { engaged(type) }
    }
}

/** Plans the optional "how was the meal?" question for meals logged right after eating. */
@Singleton
class AfterMealCheckIn @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val meals: MealRepository,
) {
    /** Meals are logged inside the app, so watching while the process lives catches every new one. */
    suspend fun watch() {
        meals.observeLast().filterNotNull().distinctUntilChangedBy { it.id }.collect { plan(it) }
    }

    /** When the question is switched on right after a meal was logged. */
    suspend fun planLatest() {
        meals.observeLast().first()?.let { plan(it) }
    }

    suspend fun plan(meal: MealEntity, now: Long = System.currentTimeMillis()) {
        val config = settings.current().reminders.firstOrNull { it.type == ReminderType.AFTER_MEAL }
        if (config?.enabled != true || meal.feelings.isNotEmpty()) return
        val delay = AfterMealPlan.delayMillis(meal.epochMillis, now, config.intervalMinutes) ?: return
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ReminderWorker.KEY_TYPE to ReminderType.AFTER_MEAL.name, ReminderWorker.KEY_MEAL_ID to meal.id))
            .addTag(ReminderScheduler.TAG)
            .build()
        // A later meal replaces the pending question: only the latest one is asked about.
        WorkManager.getInstance(context).enqueueUniqueWork(ReminderScheduler.workName(ReminderType.AFTER_MEAL), ExistingWorkPolicy.REPLACE, request)
    }
}
