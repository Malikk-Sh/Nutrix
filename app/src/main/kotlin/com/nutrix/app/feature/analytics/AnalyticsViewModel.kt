package com.nutrix.app.feature.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.WeightUnit
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.data.repository.DataChangeNotifier
import com.nutrix.app.data.repository.DayCheckRepository
import com.nutrix.app.data.repository.IntakePlanRepository
import com.nutrix.app.database.entity.IntakePlanEntity
import com.nutrix.app.data.repository.DrinkRepository
import com.nutrix.app.data.repository.HealthRepository
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.data.repository.NoteRepository
import com.nutrix.app.data.repository.SymptomRepository
import com.nutrix.app.data.repository.WeightRepository
import com.nutrix.app.domain.AnalyticsCalculator
import com.nutrix.app.domain.AnalyticsInput
import com.nutrix.app.domain.CorrelationResult
import com.nutrix.app.domain.DayIntake
import com.nutrix.app.domain.DayIntakes
import com.nutrix.app.domain.Metric
import com.nutrix.app.domain.MetricSeries
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

sealed interface AnalyticsPeriod {
    val from: LocalDate
    val to: LocalDate

    data class LastDays(val days: Int) : AnalyticsPeriod {
        override val to: LocalDate get() = LocalDate.now()
        override val from: LocalDate get() = to.minusDays(days - 1L)
    }

    data class Custom(override val from: LocalDate, override val to: LocalDate) : AnalyticsPeriod
}

data class AnalyticsState(
    val period: AnalyticsPeriod = AnalyticsPeriod.LastDays(30),
    val series: Map<Metric, MetricSeries> = emptyMap(),
    val correlations: List<CorrelationResult> = emptyList(),
    val trackedDays: Int = 0,
    val totalDays: Int = 0,
    val unit: WeightUnit = WeightUnit.KG,
    val loading: Boolean = true,
    /** Every day of the period, empty days included, by the same rule as the food diary. */
    val intake: List<DayIntake> = emptyList(),
    /** The whole history of guidelines; each day is compared with the one it had. */
    val plans: List<IntakePlanEntity> = emptyList(),
)

@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val meals: MealRepository,
    private val drinks: DrinkRepository,
    private val weights: WeightRepository,
    private val symptoms: SymptomRepository,
    private val health: HealthRepository,
    private val notes: NoteRepository,
    private val checks: DayCheckRepository,
    private val plans: IntakePlanRepository,
    settings: SettingsRepository,
    notifier: DataChangeNotifier,
) : ViewModel() {
    val period = MutableStateFlow<AnalyticsPeriod>(AnalyticsPeriod.LastDays(30))

    val state: StateFlow<AnalyticsState> = combine(
        period,
        notifier.changes.onStart { emit(Unit) },
        settings.settings.map { it.weightUnit },
    ) { p, _, unit -> p to unit }
        .mapLatest { (p, unit) -> compute(p, unit) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AnalyticsState())

    private suspend fun compute(p: AnalyticsPeriod, unit: WeightUnit): AnalyticsState = withContext(Dispatchers.Default) {
        val from = p.from.toEpochDay()
        val to = p.to.toEpochDay()
        val input = AnalyticsInput(
            from = from,
            to = to,
            meals = meals.getRange(from, to).map { it.meal },
            drinks = drinks.getRange(from, to),
            weights = weights.getRange(from, to),
            symptoms = symptoms.getRange(from, to),
            sleeps = health.getSleepRange(from, to),
            activity = health.getActivityRange(from, to),
            notes = notes.getRange(from, to),
            confirmedDays = checks.getRange(from, to),
        )
        val series = AnalyticsCalculator.series(input)
        AnalyticsState(
            period = p,
            series = series,
            correlations = AnalyticsCalculator.correlations(input, series),
            trackedDays = AnalyticsCalculator.trackedDays(input).size,
            totalDays = (to - from + 1).toInt(),
            unit = unit,
            loading = false,
            intake = DayIntakes.range(input, TimeUtils.todayEpochDay()),
            plans = plans.all(),
        )
    }

    fun setConfirmed(day: Long, confirmed: Boolean) {
        viewModelScope.launch { checks.setConfirmed(day, confirmed) }
    }

    fun setPeriod(p: AnalyticsPeriod) {
        period.value = p
    }

    fun today(): Long = TimeUtils.todayEpochDay()
}
