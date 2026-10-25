package com.nutrix.app.feature.weight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrix.app.R
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.core.model.WeightUnit
import com.nutrix.app.core.time.RecordTime
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.domain.ChartPoint
import com.nutrix.app.core.util.Units
import com.nutrix.app.data.repository.UndoManager
import com.nutrix.app.data.repository.UserMessages
import com.nutrix.app.data.repository.WeightRepository
import com.nutrix.app.database.entity.WeightEntity
import com.nutrix.app.domain.WeightStats
import com.nutrix.app.domain.WeightStatsCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

enum class WeightPeriod(val days: Int?) { D30(30), D90(90), Y1(365), ALL(null) }

data class WeightUiState(
    val all: List<WeightEntity> = emptyList(),
    val stats: WeightStats = WeightStatsCalculator.compute(emptyList(), null),
    val unit: WeightUnit = WeightUnit.KG,
    val targetKg: Double? = null,
    val chart: List<ChartPoint> = emptyList(),
    val period: WeightPeriod = WeightPeriod.D90,
    val loaded: Boolean = false,
)

data class WeightForm(
    val id: Long = 0,
    val valueText: String = "",
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.now().withSecond(0).withNano(0),
    val zoneId: String = ZoneId.systemDefault().id,
    val note: String = "",
)

@HiltViewModel
class WeightViewModel @Inject constructor(
    private val weights: WeightRepository,
    private val settings: SettingsRepository,
    private val undo: UndoManager,
    private val messages: UserMessages,
) : ViewModel() {
    private val period = MutableStateFlow(WeightPeriod.D90)

    val state: StateFlow<WeightUiState> = combine(weights.observeAll(), settings.settings, period) { list, s, p ->
        val today = TimeUtils.todayEpochDay()
        val inPeriod = list.filter { p.days == null || it.localDate > today - p.days }
        // One point per day (the day's mean) keeps the chart readable with several weighings a day.
        val chart = inPeriod.groupBy { it.localDate }.map { (day, items) ->
            ChartPoint(day, Units.toDisplay(items.map { it.weightKg }.average(), s.weightUnit))
        }.sortedBy { it.epochDay }
        WeightUiState(
            all = list,
            stats = WeightStatsCalculator.compute(list, s.targetWeightKg),
            unit = s.weightUnit,
            targetKg = s.targetWeightKg,
            chart = chart,
            period = p,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WeightUiState())

    private val _form = MutableStateFlow<WeightForm?>(null)
    val form: StateFlow<WeightForm?> = _form.asStateFlow()

    /** Set when a likely duplicate was found; the user must confirm saving. */
    private val _duplicate = MutableStateFlow<WeightEntity?>(null)
    val duplicate: StateFlow<WeightEntity?> = _duplicate.asStateFlow()

    fun setPeriod(p: WeightPeriod) {
        period.value = p
    }

    fun startAdd() {
        val last = state.value.stats.latest
        _form.value = WeightForm(valueText = last?.let { format(it.weightKg) }.orEmpty())
    }

    fun startEdit(w: WeightEntity) {
        if (w.source != DataSource.MANUAL) return
        val rt = w.time
        _form.value = WeightForm(id = w.id, valueText = format(w.weightKg), date = rt.localDate, time = rt.localTime, zoneId = w.zoneId, note = w.note.orEmpty())
    }

    private fun format(kg: Double): String = Units.roundTo(Units.toDisplay(kg, state.value.unit), 1).toString().replace('.', ',')

    fun updateForm(transform: (WeightForm) -> WeightForm) = _form.update { it?.let(transform) }

    fun closeForm() {
        _form.value = null
        _duplicate.value = null
    }

    fun parsedKg(): Double? {
        val f = _form.value ?: return null
        val v = Units.parseDecimal(f.valueText) ?: return null
        return Units.toKg(v, state.value.unit).takeIf { Units.isPlausibleWeightKg(it) }
    }

    fun save(force: Boolean = false) {
        val f = _form.value ?: return
        val kg = parsedKg() ?: return
        viewModelScope.launch {
            val rt = RecordTime.of(f.date, f.time, TimeUtils.zoneOrDefault(f.zoneId))
            if (!force) {
                val dup = weights.findLikelyDuplicate(rt, kg, ignoreId = f.id)
                if (dup != null) {
                    _duplicate.value = dup
                    return@launch
                }
            }
            weights.saveManual(f.id, rt, kg, f.note)
            messages.show(R.string.weight_saved)
            closeForm()
        }
    }

    fun delete(w: WeightEntity) {
        if (w.source != DataSource.MANUAL) return
        viewModelScope.launch {
            val removed = weights.delete(w.id) ?: return@launch
            undo.offer(undo = { weights.restore(removed) })
            closeForm()
        }
    }

    fun setTarget(kg: Double?) {
        viewModelScope.launch { settings.setTargetWeight(kg) }
    }
}
