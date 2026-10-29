package com.nutrix.app.data.repository

import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.domain.AnalyticsInput
import com.nutrix.app.domain.insights.Insight
import com.nutrix.app.domain.insights.InsightEngine
import com.nutrix.app.domain.insights.InsightInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Observations over [from]..[to] (completed days). [hidden] are the ones the user chose not to see. */
data class InsightReport(val from: Long, val to: Long, val all: List<Insight>, val hidden: Set<String>) {
    val visible: List<Insight> get() = all.filterNot { it.id in hidden }
    val hiddenCount: Int get() = all.count { it.id in hidden }
}

@Singleton
class InsightRepository @Inject constructor(
    private val meals: MealRepository,
    private val drinks: DrinkRepository,
    private val weights: WeightRepository,
    private val symptoms: SymptomRepository,
    private val health: HealthRepository,
    private val notes: NoteRepository,
    private val settings: SettingsRepository,
    notifier: DataChangeNotifier,
) {
    private data class Inputs(val goal: Int?, val target: Double?, val sources: List<DataSource>, val hidden: Set<String>)

    /** Recomputed after any diary write and when the guideline, target or source priority changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val reports: Flow<InsightReport> = combine(
        notifier.changes.onStart { emit(Unit) },
        settings.settings.map { Inputs(it.waterGoalMl, it.targetWeightKg, it.sourcePriority, it.hiddenInsights) }.distinctUntilChanged(),
    ) { _, inputs -> inputs }.mapLatest { compute(it.hidden) }

    suspend fun current(): InsightReport = compute(settings.current().hiddenInsights)

    private suspend fun compute(hidden: Set<String>, today: Long = TimeUtils.todayEpochDay()): InsightReport = withContext(Dispatchers.Default) {
        val window = InsightEngine.window(today)
        val s = settings.current()
        val records = AnalyticsInput(
            from = window.first,
            to = window.last,
            meals = meals.getRange(window.first, window.last).map { it.meal },
            drinks = drinks.getRange(window.first, window.last),
            weights = weights.getRange(window.first, window.last),
            symptoms = symptoms.getRange(window.first, window.last),
            // A night belongs to the morning it ends on, so the window's last evening ends today.
            sleeps = health.getSleepRange(window.first, window.last + 1),
            activity = health.getActivityRange(window.first, window.last),
            notes = notes.getRange(window.first, window.last),
        )
        val insights = InsightEngine.analyze(InsightInput(records, s.waterGoalMl, s.targetWeightKg))
        InsightReport(window.first, window.last, insights, hidden)
    }
}
