package com.nutrix.app.data.repository

import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.domain.AnalyticsInput
import com.nutrix.app.domain.insights.Analysis
import com.nutrix.app.domain.insights.DataCoverage
import com.nutrix.app.domain.insights.InsightComparison
import com.nutrix.app.domain.insights.InsightHistory
import com.nutrix.app.domain.insights.InsightTopic
import com.nutrix.app.domain.insights.RuleCheck
import com.nutrix.app.domain.insights.Insight
import com.nutrix.app.domain.insights.WeeklyOverview
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
data class InsightReport(
    val from: Long,
    val to: Long,
    val all: List<Insight>,
    val hidden: Set<String>,
    val coverage: DataCoverage = DataCoverage(0, 0, 0, 0, 0, 0, 0, 0),
    val hasWaterGoal: Boolean = false,
    /** The four weeks before [from], to tell repeating patterns from new ones. */
    val previous: Analysis = Analysis.EMPTY,
    /** How every rule fared in the analysis window, including the ones that found nothing. */
    val checks: Map<String, RuleCheck> = emptyMap(),
    /** Topics the user chose: their observations come first, the engine's order is kept within each group. */
    val interests: Set<InsightTopic> = emptySet(),
) {
    val overview: WeeklyOverview get() = WeeklyOverview.build(visible, coverage, hasWaterGoal)
    val visible: List<Insight> get() = all.filterNot { it.id in hidden }.sortedBy { if (it.topic in interests) 0 else 1 }
    val hiddenCount: Int get() = all.count { it.id in hidden }
    fun history(insight: Insight): InsightHistory = InsightComparison.history(insight, previous)
    /** Patterns of the previous weeks that did not show up again (hidden ones stay hidden). */
    val faded: List<Insight> get() = InsightComparison.faded(Analysis(all, checks), previous).filterNot { it.id in hidden }
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
    private val checks: DayCheckRepository,
) {
    private data class Inputs(val goal: Int?, val target: Double?, val sources: List<DataSource>, val hidden: Set<String>, val topics: Set<String>)

    /** Recomputed after any diary write and when the guideline, target or source priority changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val reports: Flow<InsightReport> = combine(
        notifier.changes.onStart { emit(Unit) },
        settings.settings.map { Inputs(it.waterGoalMl, it.targetWeightKg, it.sourcePriority, it.hiddenInsights, it.insightTopics) }.distinctUntilChanged(),
    ) { _, inputs -> inputs }.mapLatest { compute(it.hidden, it.topics) }

    suspend fun current(): InsightReport = settings.current().let { compute(it.hiddenInsights, it.insightTopics) }

    private suspend fun compute(hidden: Set<String>, topics: Set<String>, today: Long = TimeUtils.todayEpochDay()): InsightReport = withContext(Dispatchers.Default) {
        val window = InsightEngine.window(today)
        val earlier = InsightComparison.previousWindow(window)
        val s = settings.current()
        // One read for both windows; the engine and the coverage keep to their own days.
        val records = AnalyticsInput(
            from = window.first,
            to = window.last,
            meals = meals.getRange(earlier.first, window.last).map { it.meal },
            drinks = drinks.getRange(earlier.first, window.last),
            weights = weights.getRange(earlier.first, window.last),
            symptoms = symptoms.getRange(earlier.first, window.last),
            // A night belongs to the morning it ends on, so the window's last evening ends today.
            sleeps = health.getSleepRange(earlier.first, window.last + 1),
            activity = health.getActivityRange(earlier.first, window.last),
            notes = notes.getRange(earlier.first, window.last),
            confirmedDays = checks.getRange(earlier.first, window.last),
        )
        val current = InsightEngine.evaluate(InsightInput(records, s.waterGoalMl, s.targetWeightKg))
        val previous = InsightEngine.evaluate(InsightInput(records.copy(from = earlier.first, to = earlier.last), s.waterGoalMl, s.targetWeightKg))
        val interests = InsightTopic.entries.filter { it.name in topics }.toSet()
        InsightReport(window.first, window.last, current.insights, hidden, DataCoverage.of(records), s.waterGoalMl != null,
            previous, current.checks, interests)
    }
}
