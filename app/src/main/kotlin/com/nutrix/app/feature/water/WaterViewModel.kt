package com.nutrix.app.feature.water

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.nutrix.app.R
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.core.time.RecordTime
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.data.repository.DrinkRepository
import com.nutrix.app.data.repository.UndoManager
import com.nutrix.app.data.repository.UserMessages
import com.nutrix.app.database.entity.DrinkEntity
import com.nutrix.app.database.entity.DrinkTemplateEntity
import com.nutrix.app.navigation.DrinkEditorRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

data class WaterUiState(
    val totalMl: Int = 0,
    val goalMl: Int? = null,
    val quickVolumes: List<Int> = emptyList(),
    val todayDrinks: List<DrinkEntity> = emptyList(),
    val templates: List<DrinkTemplateEntity> = emptyList(),
    /** Last 14 days, oldest first: date to total ml. */
    val history: List<Pair<LocalDate, Int>> = emptyList(),
)

@HiltViewModel
class WaterViewModel @Inject constructor(
    private val drinks: DrinkRepository,
    private val settings: SettingsRepository,
    private val undo: UndoManager,
    private val messages: UserMessages,
) : ViewModel() {

    val quickType = MutableStateFlow(DrinkType.WATER)

    val state: StateFlow<WaterUiState> = run {
        val today = TimeUtils.todayEpochDay()
        combine(
            drinks.observeDay(today),
            drinks.observeTemplates(),
            settings.settings,
            drinks.observeTotalsPerDay(today - 13, today),
        ) { list, templates, s, totals ->
            val byDay = totals.associate { it.localDate to it.value.toInt() }
            WaterUiState(
                totalMl = list.sumOf { it.volumeMl },
                goalMl = s.waterGoalMl,
                quickVolumes = s.quickVolumes,
                todayDrinks = list.sortedByDescending { it.epochMillis },
                templates = templates,
                history = (13 downTo 0).map { back -> LocalDate.ofEpochDay(today - back).let { it to (byDay[it.toEpochDay()] ?: 0) } },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WaterUiState())
    }

    fun quickAdd(volume: Int) {
        viewModelScope.launch { drinks.quickAdd(quickType.value, volume) }
    }

    fun addTemplate(t: DrinkTemplateEntity) {
        viewModelScope.launch { drinks.addFromTemplate(t) }
    }

    fun delete(drink: DrinkEntity) {
        viewModelScope.launch {
            val removed = drinks.delete(drink.id) ?: return@launch
            undo.offer(undo = { drinks.restore(removed) }, commit = { drinks.purge(removed) })
        }
    }

    fun setGoal(ml: Int?) {
        viewModelScope.launch { settings.setWaterGoal(ml?.takeIf { it in 100..10000 }) }
    }

    fun setQuickVolumes(volumes: List<Int>) {
        viewModelScope.launch { settings.setQuickVolumes(volumes) }
    }

    fun saveTemplate(t: DrinkTemplateEntity) {
        viewModelScope.launch {
            drinks.saveTemplate(t.copy(sortOrder = if (t.id == 0L) state.value.templates.size else t.sortOrder))
            messages.show(R.string.water_template_saved)
        }
    }

    fun deleteTemplate(t: DrinkTemplateEntity) {
        viewModelScope.launch {
            drinks.deleteTemplate(t)
            undo.offer(undo = { drinks.saveTemplate(t) })
        }
    }
}

data class DrinkForm(
    val id: Long = 0,
    val type: DrinkType = DrinkType.WATER,
    val name: String = "",
    val volumeText: String = "250",
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.now().withSecond(0).withNano(0),
    val zoneId: String = ZoneId.systemDefault().id,
    val photo: String? = null,
    val note: String = "",
    val templateId: Long? = null,
    val createdAt: Long = 0,
) {
    val volume: Int? get() = volumeText.toIntOrNull()?.takeIf { it in 1..5000 }
}

@HiltViewModel
class DrinkEditorViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val drinks: DrinkRepository,
    private val photos: PhotoStorage,
    private val undo: UndoManager,
    private val messages: UserMessages,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<DrinkEditorRoute>()
    private val _form = MutableStateFlow(DrinkForm())
    val form: StateFlow<DrinkForm> = _form.asStateFlow()
    val closed = MutableStateFlow(false)
    private val _loaded = MutableStateFlow(route.drinkId == 0L)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    /** While a save or delete runs, the form is locked: a second tap cannot insert a duplicate, and leaving cannot delete the photo being saved. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private var originalPhoto: String? = null

    init {
        if (route.drinkId != 0L) {
            viewModelScope.launch {
                val d = drinks.get(route.drinkId) ?: run { closed.value = true; return@launch }
                val rt = d.time
                originalPhoto = d.photoFile
                _form.value = DrinkForm(
                    id = d.id, type = d.drinkType, name = d.name.orEmpty(), volumeText = d.volumeMl.toString(),
                    date = rt.localDate, time = rt.localTime, zoneId = d.zoneId, photo = d.photoFile, note = d.note.orEmpty(),
                    templateId = d.templateId, createdAt = d.createdAt,
                )
                _loaded.value = true
            }
        }
    }

    fun update(transform: (DrinkForm) -> DrinkForm) = _form.update(transform)

    fun consumeCamera(file: String): Boolean {
        if (!_loaded.value || closed.value || _busy.value) return false
        setPhoto(file)
        return true
    }

    fun setPhotoFromUri(uri: android.net.Uri) {
        if (_busy.value) return
        viewModelScope.launch {
            runCatching { photos.importUri(uri) }.onSuccess { file -> if (_busy.value || closed.value) photos.delete(file) else setPhoto(file) }
        }
    }

    private fun setPhoto(file: String) {
        val previous = _form.value.photo
        if (previous == file) return
        if (previous != null && previous != originalPhoto) photos.delete(previous)
        _form.update { it.copy(photo = file) }
    }

    fun removePhoto() {
        if (_busy.value) return
        val previous = _form.value.photo
        if (previous != null && previous != originalPhoto) photos.delete(previous)
        _form.update { it.copy(photo = null) }
    }

    fun save() {
        val f = _form.value
        val volume = f.volume ?: return
        if (!_loaded.value || _busy.value || closed.value) return
        _busy.value = true
        // ATOMIC: even if the screen is cleared before this starts, the write below still runs to the end.
        viewModelScope.launch(start = CoroutineStart.ATOMIC) {
            val saved = withContext(NonCancellable) {
                try {
                    val rt = RecordTime.of(f.date, f.time, TimeUtils.zoneOrDefault(f.zoneId))
                    drinks.save(
                        DrinkEntity(
                            id = f.id, epochMillis = rt.epochMillis, zoneId = rt.zoneId, localDate = rt.epochDay,
                            drinkType = f.type, name = f.name.trim().ifBlank { null }, volumeMl = volume,
                            photoFile = f.photo, note = f.note.trim().ifBlank { null }, templateId = f.templateId,
                            createdAt = if (f.createdAt > 0) f.createdAt else System.currentTimeMillis(),
                        ),
                    )
                    true
                } catch (_: Exception) {
                    false
                }
            }
            if (saved) {
                messages.show(R.string.drink_saved)
                closed.value = true
            } else {
                messages.show(R.string.food_save_error)
                _busy.value = false
            }
        }
    }

    fun delete() {
        val id = _form.value.id
        if (id == 0L || _busy.value) return
        _busy.value = true
        viewModelScope.launch(start = CoroutineStart.ATOMIC) {
            val removed = withContext(NonCancellable) { runCatching { drinks.delete(id) }.getOrNull() }
            if (removed == null) {
                _busy.value = false
                return@launch
            }
            undo.offer(undo = { drinks.restore(removed) }, commit = { drinks.purge(removed) })
            closed.value = true
        }
    }

    /** Leaving while a save runs is ignored: the save closes the screen itself and keeps the photo. */
    fun cancel() {
        if (_busy.value) return
        val f = _form.value
        if (f.photo != null && f.photo != originalPhoto) photos.delete(f.photo)
        closed.value = true
    }
}
