package com.nutrix.app.feature.reports

import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.datastore.UserSettings
import com.nutrix.app.data.repository.DayCheckRepository
import com.nutrix.app.data.repository.DrinkRepository
import com.nutrix.app.data.repository.IntakePlanRepository
import com.nutrix.app.database.entity.IntakePlanEntity
import com.nutrix.app.data.repository.HealthRepository
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.data.repository.NoteRepository
import com.nutrix.app.data.repository.SymptomRepository
import com.nutrix.app.data.repository.WeightRepository
import com.nutrix.app.database.entity.DrinkEntity
import com.nutrix.app.database.entity.ExerciseEntity
import com.nutrix.app.database.entity.MealWithPhotos
import com.nutrix.app.database.entity.NoteEntity
import com.nutrix.app.database.entity.SleepEntity
import com.nutrix.app.database.entity.SymptomEntity
import com.nutrix.app.database.entity.WeightEntity
import com.nutrix.app.domain.AnalyticsCalculator
import com.nutrix.app.domain.AnalyticsInput
import com.nutrix.app.domain.DailyActivity
import com.nutrix.app.domain.DayIntake
import com.nutrix.app.domain.DayIntakes
import com.nutrix.app.domain.Metric
import com.nutrix.app.domain.MetricSeries
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Everything a report or export needs for a period. */
data class ReportData(
    val from: LocalDate,
    val to: LocalDate,
    val settings: UserSettings,
    val meals: List<MealWithPhotos>,
    val drinks: List<DrinkEntity>,
    val weights: List<WeightEntity>,
    val symptoms: List<SymptomEntity>,
    val sleeps: List<SleepEntity>,
    val activity: List<DailyActivity>,
    val exercises: List<ExerciseEntity>,
    val notes: List<NoteEntity>,
    val series: Map<Metric, MetricSeries>,
    val trackedDays: Set<Long>,
    /** Days the user confirmed as fully recorded. */
    val confirmedDays: Set<Long> = emptySet(),
    /** The whole guideline history; each entry applies from its start day. */
    val plans: List<IntakePlanEntity> = emptyList(),
) {
    /** Every day of the period by the diary's own rule: food and caloric drinks once, missing values never zero. */
    val intake: List<DayIntake>
        get() = (from.toEpochDay()..to.toEpochDay()).map { day ->
            DayIntakes.of(day, meals.map { it.meal }, drinks, day in confirmedDays, LocalDate.now().toEpochDay())
        }

    val isEmpty: Boolean
        get() = meals.isEmpty() && drinks.isEmpty() && weights.isEmpty() && symptoms.isEmpty() && sleeps.isEmpty() &&
            activity.isEmpty() && exercises.isEmpty() && notes.isEmpty()
}

@Singleton
class ReportDataLoader @Inject constructor(
    private val meals: MealRepository,
    private val drinks: DrinkRepository,
    private val weights: WeightRepository,
    private val symptoms: SymptomRepository,
    private val health: HealthRepository,
    private val notes: NoteRepository,
    private val settings: SettingsRepository,
    private val checks: DayCheckRepository,
    private val plans: IntakePlanRepository,
) {
    suspend fun load(from: LocalDate, to: LocalDate): ReportData {
        val f = from.toEpochDay()
        val t = to.toEpochDay()
        val mealList = meals.getRange(f, t)
        val input = AnalyticsInput(
            from = f,
            to = t,
            meals = mealList.map { it.meal },
            drinks = drinks.getRange(f, t),
            weights = weights.getRange(f, t),
            symptoms = symptoms.getRange(f, t),
            sleeps = health.getSleepRange(f, t),
            activity = health.getActivityRange(f, t),
            notes = notes.getRange(f, t),
        )
        return ReportData(
            from = from,
            to = to,
            settings = settings.current(),
            meals = mealList,
            drinks = input.drinks,
            weights = input.weights,
            symptoms = input.symptoms,
            sleeps = input.sleeps,
            activity = input.activity,
            exercises = health.getExercises(f, t),
            notes = input.notes,
            series = AnalyticsCalculator.series(input),
            trackedDays = AnalyticsCalculator.trackedDays(input),
            confirmedDays = checks.getRange(f, t),
            plans = plans.all(),
        )
    }
}
