package com.nutrix.app.feature.dashboard

import androidx.lifecycle.ViewModel
import com.nutrix.app.data.repository.InsightRepository
import com.nutrix.app.domain.insights.Coverage
import com.nutrix.app.domain.insights.Insight
import androidx.lifecycle.viewModelScope
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.datastore.UserSettings
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.data.repository.DrinkRepository
import com.nutrix.app.data.repository.HealthRepository
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.data.repository.SymptomRepository
import com.nutrix.app.data.repository.TimelineItem
import com.nutrix.app.data.repository.TimelineRepository
import com.nutrix.app.data.repository.WeightRepository
import com.nutrix.app.database.entity.MealWithPhotos
import com.nutrix.app.database.entity.SleepEntity
import com.nutrix.app.database.entity.SymptomEntity
import com.nutrix.app.database.entity.WeightEntity
import com.nutrix.app.domain.DailyActivity
import com.nutrix.app.domain.WeightStatsCalculator
import com.nutrix.app.integration.sync.HealthSyncCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WeightCard(val latest: WeightEntity?, val weekChangeKg: Double?, val spark: List<Double>)
data class FoodCard(val count: Int, val last: MealWithPhotos?, val photos: List<String>)
data class WaterCard(val totalMl: Int, val goalMl: Int?, val quickVolumes: List<Int>)
data class ActivityCard(val enabled: Boolean, val today: DailyActivity?, val lastSync: Long?)

data class DashboardState(
    val epochDay: Long = TimeUtils.todayEpochDay(),
    val settings: UserSettings = UserSettings(),
    val weight: WeightCard = WeightCard(null, null, emptyList()),
    val food: FoodCard = FoodCard(0, null, emptyList()),
    val water: WaterCard = WaterCard(0, null, emptyList()),
    val sleep: SleepEntity? = null,
    val sleepSessions: List<SleepEntity> = emptyList(),
    val activity: ActivityCard = ActivityCard(false, null, null),
    val symptoms: List<SymptomEntity> = emptyList(),
    val todaySymptomCount: Int = 0,
    val timeline: List<TimelineItem> = emptyList(),
    val loaded: Boolean = false,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val meals: MealRepository,
    private val drinks: DrinkRepository,
    private val weights: WeightRepository,
    private val symptoms: SymptomRepository,
    private val health: HealthRepository,
    private val timeline: TimelineRepository,
    private val settings: SettingsRepository,
    private val sync: HealthSyncCoordinator,
    insights: InsightRepository,
) : ViewModel() {

    /** The most relevant visible observation; the coverage hint is left to the insights screen. */
    val topInsight: StateFlow<Insight?> = insights.reports.map { r -> r.visible.firstOrNull { it !is Coverage } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Emits the current day and switches automatically after midnight. */
    private val today: Flow<Long> = flow {
        while (true) {
            emit(TimeUtils.todayEpochDay())
            delay(30_000)
        }
    }.distinctUntilChanged()

    val state: StateFlow<DashboardState> = today.flatMapLatest { day -> dayState(day) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())

    private fun dayState(day: Long): Flow<DashboardState> {
        val weightCard = weights.observeAll().map { list ->
            val stats = WeightStatsCalculator.compute(list, null)
            val spark = list.filter { it.localDate > day - 30 }.groupBy { it.localDate }.toSortedMap()
                .values.map { items -> items.map { it.weightKg }.average() }
            WeightCard(stats.latest, stats.weekChangeKg, spark)
        }
        val foodCard = combine(meals.observeDay(day), meals.observeRecent(6)) { todayMeals, recent ->
            val photos = (todayMeals.sortedByDescending { it.meal.epochMillis } + recent)
                .distinctBy { it.meal.id }
                .flatMap { m -> m.sortedPhotos.take(1).map { it.fileName } }
                .take(4)
            FoodCard(todayMeals.size, todayMeals.maxByOrNull { it.meal.epochMillis } ?: recent.firstOrNull(), photos)
        }
        val waterAndSettings = combine(drinks.observeTotal(day), settings.settings) { total, s -> s to WaterCard(total, s.waterGoalMl, s.quickVolumes) }
        val activityCard = combine(health.observeActivityDay(day), settings.settings, sync.lastSuccessfulSync) { a, s, last ->
            ActivityCard(s.healthConnectEnabled || s.samsungHealthEnabled, a, last)
        }
        val symptomFlow = combine(symptoms.observeRecent(3), symptoms.observeDay(day)) { recent, todayList -> recent to todayList.size }
        val first = combine(weightCard, foodCard, waterAndSettings, health.observeLatestSleepDay()) { w, f, ws, sleeps ->
            val sleep = sleeps.maxByOrNull { it.endMillis }
            DashboardState(epochDay = day, settings = ws.first, weight = w, food = f, water = ws.second, sleep = sleep,
                sleepSessions = sleeps.filter { it.localDate == sleep?.localDate })
        }
        return combine(first, activityCard, symptomFlow, timeline.observeDay(day)) { base, act, sym, tl ->
            base.copy(activity = act, symptoms = sym.first, todaySymptomCount = sym.second, timeline = tl, loaded = true)
        }
    }

    fun addWater(volume: Int) {
        viewModelScope.launch { drinks.quickAdd(DrinkType.WATER, volume) }
    }

    fun refreshHealth() {
        viewModelScope.launch { sync.syncIfStale() }
    }
}
