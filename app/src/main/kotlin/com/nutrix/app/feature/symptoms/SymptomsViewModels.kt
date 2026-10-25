package com.nutrix.app.feature.symptoms

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.nutrix.app.R
import com.nutrix.app.core.model.BodyPosition
import com.nutrix.app.core.model.SymptomType
import com.nutrix.app.core.time.RecordTime
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.data.repository.SymptomRepository
import com.nutrix.app.data.repository.UndoManager
import com.nutrix.app.data.repository.UserMessages
import com.nutrix.app.database.entity.MealWithPhotos
import com.nutrix.app.database.entity.SymptomEntity
import com.nutrix.app.navigation.SymptomEditorRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

data class SymptomDay(val date: LocalDate, val items: List<SymptomEntity>)

@HiltViewModel
class SymptomsViewModel @Inject constructor(
    private val symptoms: SymptomRepository,
    meals: MealRepository,
) : ViewModel() {
    private val window = MutableStateFlow(30)

    val days: StateFlow<List<SymptomDay>?> = window.flatMapLatest { days ->
        val today = TimeUtils.todayEpochDay()
        symptoms.observeRange(today - days + 1, today + 1).map { list ->
            list.groupBy { it.localDate }.map { (d, items) -> SymptomDay(LocalDate.ofEpochDay(d), items.sortedByDescending { it.epochMillis }) }
                .sortedByDescending { it.date }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Meals referenced by visible symptoms, to show "after: lunch". */
    val recentMeals: StateFlow<Map<Long, MealWithPhotos>> = meals.observeRecent(200)
        .map { list -> list.associateBy { it.meal.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _quickAdded = MutableSharedFlow<Pair<Long, SymptomType>>(extraBufferCapacity = 4)
    val quickAdded: SharedFlow<Pair<Long, SymptomType>> = _quickAdded.asSharedFlow()

    fun quickAdd(type: SymptomType) {
        viewModelScope.launch {
            val id = symptoms.quickAdd(type)
            _quickAdded.tryEmit(id to type)
        }
    }

    fun loadMore() {
        window.value += 30
    }
}

data class SymptomForm(
    val id: Long = 0,
    val types: Set<SymptomType> = emptySet(),
    val intensities: Map<SymptomType, Int> = emptyMap(),
    val customName: String = "",
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.now().withSecond(0).withNano(0),
    val zoneId: String = ZoneId.systemDefault().id,
    val durationText: String = "",
    val mealId: Long? = null,
    val position: BodyPosition? = null,
    val note: String = "",
    val createdAt: Long = 0,
) {
    val recordTime: RecordTime get() = RecordTime.of(date, time, TimeUtils.zoneOrDefault(zoneId))
    val needsCaution: Boolean
        get() = intensities.values.any { it >= 4 } ||
            types.any { it == SymptomType.DARKENING_VISION || it == SymptomType.PALPITATIONS }
}

@HiltViewModel
class SymptomEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val symptoms: SymptomRepository,
    private val meals: MealRepository,
    private val undo: UndoManager,
    private val messages: UserMessages,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<SymptomEditorRoute>()
    private val _form = MutableStateFlow(SymptomForm(mealId = route.mealId.takeIf { it != 0L }))
    val form: StateFlow<SymptomForm> = _form.asStateFlow()
    val isEdit = route.symptomId != 0L

    private val _candidateMeals = MutableStateFlow<List<MealWithPhotos>>(emptyList())
    val candidateMeals: StateFlow<List<MealWithPhotos>> = _candidateMeals.asStateFlow()
    val closed = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            if (route.symptomId != 0L) {
                val s = symptoms.get(route.symptomId) ?: run { closed.value = true; return@launch }
                val rt = s.time
                _form.value = SymptomForm(
                    id = s.id, types = setOf(s.type), intensities = s.intensity?.let { mapOf(s.type to it) } ?: emptyMap(),
                    customName = s.customName.orEmpty(), date = rt.localDate, time = rt.localTime, zoneId = s.zoneId,
                    durationText = s.durationMinutes?.toString().orEmpty(), mealId = s.mealId, position = s.position,
                    note = s.note.orEmpty(), createdAt = s.createdAt,
                )
            } else if (route.mealId != 0L) {
                // Symptom logged from a meal: start right after that meal.
                meals.get(route.mealId)?.meal?.let { m ->
                    val now = RecordTime.now()
                    if (now.epochMillis - m.epochMillis > 6 * 3_600_000L) {
                        val rt = m.time
                        _form.update { it.copy(date = rt.localDate, time = rt.localTime.plusMinutes(30)) }
                    }
                }
            }
            refreshMeals()
        }
    }

    private suspend fun refreshMeals() {
        val candidates = meals.mealsBefore(_form.value.recordTime)
        val linked = _form.value.mealId?.let { id -> if (candidates.none { it.meal.id == id }) meals.get(id) else null }
        _candidateMeals.value = (listOfNotNull(linked) + candidates).distinctBy { it.meal.id }.sortedByDescending { it.meal.epochMillis }
    }

    fun update(transform: (SymptomForm) -> SymptomForm) {
        val before = _form.value
        _form.update(transform)
        if (before.date != _form.value.date || before.time != _form.value.time) viewModelScope.launch { refreshMeals() }
    }

    fun toggleType(type: SymptomType) = _form.update { f ->
        if (isEdit) {
            val intensity = f.intensities.values.firstOrNull()
            f.copy(types = setOf(type), intensities = intensity?.let { mapOf(type to it) } ?: emptyMap())
        } else if (type in f.types) {
            f.copy(types = f.types - type, intensities = f.intensities - type)
        } else {
            f.copy(types = f.types + type)
        }
    }

    fun setIntensity(type: SymptomType, value: Int?) = _form.update { f ->
        f.copy(intensities = if (value == null) f.intensities - type else f.intensities + (type to value))
    }

    fun save() {
        val f = _form.value
        if (f.types.isEmpty()) return
        viewModelScope.launch {
            val rt = f.recordTime
            val duration = f.durationText.toIntOrNull()?.takeIf { it in 1..(7 * 24 * 60) }
            val list = f.types.sortedBy { it.ordinal }.map { type ->
                SymptomEntity(
                    id = if (isEdit) f.id else 0,
                    epochMillis = rt.epochMillis,
                    zoneId = rt.zoneId,
                    localDate = rt.epochDay,
                    type = type,
                    customName = f.customName.trim().ifBlank { null }.takeIf { type == SymptomType.OTHER },
                    intensity = f.intensities[type],
                    durationMinutes = duration,
                    note = f.note.trim().ifBlank { null },
                    mealId = f.mealId,
                    position = f.position,
                    createdAt = if (f.createdAt > 0) f.createdAt else System.currentTimeMillis(),
                )
            }
            symptoms.saveAll(list)
            messages.show(R.string.symptom_saved)
            closed.value = true
        }
    }

    fun delete() {
        if (!isEdit) return
        viewModelScope.launch {
            val removed = symptoms.delete(_form.value.id) ?: return@launch
            undo.offer(undo = { symptoms.restore(removed) })
            closed.value = true
        }
    }
}
