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
import com.nutrix.app.feature.ai.DrinkPhotoAnalyzer
import com.nutrix.app.feature.ai.DrinkPhotoResult
import com.nutrix.app.feature.ai.FoodAiError
import com.nutrix.app.feature.ai.FoodAiException
import com.nutrix.app.feature.ai.isTransient
import com.nutrix.app.feature.food.FoodEditorViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.data.repository.UndoManager
import com.nutrix.app.data.repository.UserMessages
import com.nutrix.app.database.entity.DrinkEntity
import com.nutrix.app.database.entity.DrinkTemplateEntity
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.navigation.DrinkEditorRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
    val energy: EnergyInput = EnergyInput.defaultFor(DrinkType.WATER),
    /** False until the user picks an energy option, so changing the type still brings that type's default. */
    val energyTouched: Boolean = false,
    val mealId: Long? = null,
    val aiHint: String = "",
) {
    val volume: Int? get() = volumeText.toIntOrNull()?.takeIf { it in 1..5000 }
    /** Water needs no energy value; for other drinks only a half-typed number blocks saving. */
    val energyInvalid: Boolean get() = type != DrinkType.WATER && energy.invalid
    val nutrition get() = if (type == DrinkType.WATER) null else energy.toNutrition()
}

/** Recognition of the drink's photo: progress, a result applied to the form or waiting for the user's choice, or an error. */
data class DrinkAiState(
    val analyzing: Boolean = false,
    val attempt: Int = 0,
    val retrying: Boolean = false,
    /** The last estimate, for "show result". */
    val result: DrinkPhotoResult? = null,
    /** An estimate that would replace values already in the drink: applied only when the user says so. */
    val choice: DrinkPhotoResult? = null,
    val error: FoodAiError? = null,
)

@HiltViewModel
class DrinkEditorViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val drinks: DrinkRepository,
    private val meals: MealRepository,
    private val photos: PhotoStorage,
    private val undo: UndoManager,
    private val messages: UserMessages,
    private val drinkAi: DrinkPhotoAnalyzer,
    settings: SettingsRepository,
) : ViewModel() {
    val hasGeminiKey: StateFlow<Boolean> = settings.hasGeminiKey.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    private val _ai = MutableStateFlow(DrinkAiState())
    val ai: StateFlow<DrinkAiState> = _ai.asStateFlow()
    private var aiJob: Job? = null
    private var aiRequest = 0L

    private val route = savedStateHandle.toRoute<DrinkEditorRoute>()
    private val _form = MutableStateFlow(DrinkForm())
    val form: StateFlow<DrinkForm> = _form.asStateFlow()
    val closed = MutableStateFlow(false)
    private val _loaded = MutableStateFlow(route.drinkId == 0L && route.mealId == 0L)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Meals of the drink's day that it can be linked to. */
    val dayMeals: StateFlow<List<MealEntity>> = _form.map { it.date.toEpochDay() }.distinctUntilChanged()
        .flatMapLatest { day -> meals.observeDay(day) }
        .map { list -> list.map { it.meal }.sortedBy { it.epochMillis } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
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
                    // A saved drink keeps what it has: an old drink without a value stays unknown, whatever its type.
                    energy = EnergyInput.of(d.nutrition), energyTouched = true, mealId = d.mealId,
                )
                _loaded.value = true
            }
        } else if (route.mealId != 0L) {
            // Added from a meal: the drink starts at the meal's time and is linked to it.
            viewModelScope.launch {
                meals.get(route.mealId)?.meal?.let { m ->
                    val rt = m.time
                    _form.update { it.copy(date = rt.localDate, time = rt.localTime, zoneId = m.zoneId, mealId = m.id) }
                }
                _loaded.value = true
            }
        }
    }

    fun setType(type: DrinkType) = _form.update { f ->
        f.copy(type = type, energy = if (f.energyTouched) f.energy else EnergyInput.defaultFor(type))
    }

    fun setEnergy(energy: EnergyInput) = _form.update { it.copy(energy = energy, energyTouched = true) }

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
        cancelAi()
        if (previous != null && previous != originalPhoto) photos.delete(previous)
        _form.update { it.copy(photo = file) }
    }

    fun removePhoto() {
        if (_busy.value) return
        cancelAi()
        val previous = _form.value.photo
        if (previous != null && previous != originalPhoto) photos.delete(previous)
        _form.update { it.copy(photo = null) }
    }

    /** Sends the drink's photo to Gemini; one repeat for a transient failure, like the food photo. */
    fun analyzePhoto() {
        val requested = _form.value
        val photo = requested.photo ?: return
        if (!_loaded.value || _busy.value || closed.value || _ai.value.analyzing) return
        val request = ++aiRequest
        _ai.value = DrinkAiState(analyzing = true, attempt = 1)
        aiJob = viewModelScope.launch {
            try {
                for (attempt in 1..FoodEditorViewModel.MAX_AI_ATTEMPTS) {
                    if (request != aiRequest) return@launch
                    _ai.update { it.copy(attempt = attempt, retrying = false) }
                    try {
                        val result = drinkAi.analyze(photo, requested.aiHint)
                        if (request != aiRequest) return@launch
                        if (result.photoFile != photo || _form.value.photo != photo) throw FoodAiException(FoodAiError.PHOTO_CHANGED)
                        // A fresh drink is filled at once; values the user already gave are replaced only by choice.
                        if (!requested.energyTouched && requested.name.isBlank()) {
                            fill(result, requested, overwrite = false)
                            _ai.update { it.copy(result = result, error = null) }
                        } else {
                            _ai.update { it.copy(result = result, choice = result, error = null) }
                        }
                        return@launch
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        if (request != aiRequest) return@launch
                        val reason = (error as? FoodAiException)?.reason ?: FoodAiError.INVALID_RESPONSE
                        if (attempt < FoodEditorViewModel.MAX_AI_ATTEMPTS && reason.isTransient) {
                            _ai.update { it.copy(retrying = true) }
                            delay(FoodEditorViewModel.AI_RETRY_DELAY_MS)
                        } else {
                            _ai.update { it.copy(error = reason) }
                            return@launch
                        }
                    }
                }
            } finally {
                if (request == aiRequest) _ai.update { it.copy(analyzing = false, retrying = false) }
            }
        }
    }

    fun cancelAi() {
        aiRequest++
        aiJob?.cancel()
        aiJob = null
        _ai.value = DrinkAiState()
    }

    fun applyAi() {
        val choice = _ai.value.choice ?: return
        fill(choice, _form.value, overwrite = true)
        _ai.update { it.copy(choice = null) }
    }

    fun dismissAi() = _ai.update { it.copy(choice = null) }

    /** Fields the user changed while the request ran are kept, unless the user chose to replace them. */
    private fun fill(result: DrinkPhotoResult, requested: DrinkForm, overwrite: Boolean) {
        val e = result.estimate
        fun <T> keep(now: T, then: T, new: T) = if (overwrite || now == then) new else now
        _form.update { f ->
            val type = keep(f.type, requested.type, e.type)
            f.copy(
                type = type,
                name = keep(f.name, requested.name, e.drinkName),
                volumeText = e.volumeMl?.let { keep(f.volumeText, requested.volumeText, it.roundToInt().coerceIn(1, 5000).toString()) } ?: f.volumeText,
                energy = if (type == DrinkType.WATER) f.energy else keep(f.energy, requested.energy, EnergyInput.fromEstimate(e, result.model)),
                energyTouched = true,
            )
        }
    }

    fun save() {
        val f = _form.value
        val volume = f.volume ?: return
        if (!_loaded.value || _busy.value || closed.value || f.energyInvalid) return
        _busy.value = true
        // ATOMIC: even if the screen is cleared before this starts, the write below still runs to the end.
        viewModelScope.launch(start = CoroutineStart.ATOMIC) {
            val saved = withContext(NonCancellable) {
                try {
                    val rt = RecordTime.of(f.date, f.time, TimeUtils.zoneOrDefault(f.zoneId))
                    // A link survives only to a meal of the same day; moving the drink to another day drops it.
                    val mealId = f.mealId?.takeIf { id -> meals.get(id)?.meal?.localDate == rt.epochDay }
                    drinks.save(
                        DrinkEntity(
                            id = f.id, epochMillis = rt.epochMillis, zoneId = rt.zoneId, localDate = rt.epochDay,
                            drinkType = f.type, name = f.name.trim().ifBlank { null }, volumeMl = volume,
                            photoFile = f.photo, note = f.note.trim().ifBlank { null }, templateId = f.templateId,
                            createdAt = if (f.createdAt > 0) f.createdAt else System.currentTimeMillis(),
                            nutrition = f.nutrition, mealId = mealId,
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
        cancelAi()
        val f = _form.value
        if (f.photo != null && f.photo != originalPhoto) photos.delete(f.photo)
        closed.value = true
    }
}
