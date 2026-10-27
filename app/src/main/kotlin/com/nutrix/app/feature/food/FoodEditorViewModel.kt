package com.nutrix.app.feature.food

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.AfterMealFeeling
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.model.PortionEaten
import com.nutrix.app.core.model.Satiety
import com.nutrix.app.core.model.WeightUnit
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.core.time.RecordTime
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.core.util.Units
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.data.repository.UndoManager
import com.nutrix.app.data.repository.UserMessages
import com.nutrix.app.R
import com.nutrix.app.data.repository.WeightRepository
import com.nutrix.app.database.entity.FavoriteMealEntity
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.database.entity.WeightEntity
import com.nutrix.app.feature.ai.FoodReference
import com.nutrix.app.feature.ai.SmartSuggestions
import com.nutrix.app.navigation.FoodEditorRoute
import com.nutrix.app.navigation.NavResults
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/** Everything the editor holds; serialisable so it can be stored as a draft. */
@Serializable
data class MealForm(
    val saveToken: String = UUID.randomUUID().toString(),
    val mealId: Long = 0,
    val epochDay: Long = TimeUtils.todayEpochDay(),
    val minuteOfDay: Int = TimeUtils.minutesOfDay(LocalTime.now()),
    val zoneId: String = ZoneId.systemDefault().id,
    val mealType: MealType = MealType.SNACK,
    val title: String = "",
    val items: List<String> = emptyList(),
    val appetite: Int? = null,
    val portion: PortionEaten? = null,
    val satiety: Satiety? = null,
    val feelings: Set<AfterMealFeeling> = emptySet(),
    val feelingOther: String = "",
    val weightText: String = "",
    val alsoSaveWeight: Boolean = false,
    val note: String = "",
    val photos: List<String> = emptyList(),
    val gramsText: String = "",
    val kcalText: String = "",
    val kcalFromReference: Boolean = false,
    val saveAsFavorite: Boolean = false,
    val createdAt: Long = 0,
) {
    val date: LocalDate get() = LocalDate.ofEpochDay(epochDay)
    val time: LocalTime get() = TimeUtils.timeOfMinutes(minuteOfDay)
    val recordTime: RecordTime get() = RecordTime.of(date, time, TimeUtils.zoneOrDefault(zoneId))
}

data class FoodEditorUiState(
    val form: MealForm = MealForm(),
    val isEdit: Boolean = false,
    val loaded: Boolean = false,
    val dirty: Boolean = false,
    val lastWeight: WeightEntity? = null,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val favorites: List<FavoriteMealEntity> = emptyList(),
    val titleSuggestions: List<String> = emptyList(),
    val timeSuggestions: List<String> = emptyList(),
    val calorieEstimatesEnabled: Boolean = true,
    val pendingDraft: MealForm? = null,
    val processingPhoto: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
)

sealed interface FoodEditorEvent {
    data object Saved : FoodEditorEvent
    data object Closed : FoodEditorEvent
    data object Deleted : FoodEditorEvent
}

@HiltViewModel
class FoodEditorViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val meals: MealRepository,
    private val weights: WeightRepository,
    private val settings: SettingsRepository,
    private val photoStorage: PhotoStorage,
    private val undo: UndoManager,
    private val messages: UserMessages,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<FoodEditorRoute>()
    private val json = Json { ignoreUnknownKeys = true }
    private var initialForm: MealForm? = null
    private val draftMutex = Mutex()
    private var draftJob: Job? = null
    private var finished = false
    private val draftKind = when {
        route.mealId != 0L -> "meal-edit-${route.mealId}"
        route.favoriteId != 0L -> "meal-favorite-${route.favoriteId}"
        route.repeatMealId != 0L -> "meal-repeat-${route.repeatMealId}"
        else -> DRAFT_KIND
    }
    private fun encode(form: MealForm) = json.encodeToString(MealForm.serializer(), form)
    private fun decode(payload: String?) = payload?.let { runCatching { json.decodeFromString(MealForm.serializer(), it) }.getOrNull() }
    private fun ownedPhotos(): List<String> = savedStateHandle.get<ArrayList<String>>(EDITOR_PHOTOS).orEmpty()

    private val _state = MutableStateFlow(FoodEditorUiState(isEdit = route.mealId != 0L))
    val state: StateFlow<FoodEditorUiState> = _state.asStateFlow()

    private val _events = MutableStateFlow<FoodEditorEvent?>(null)
    val events: StateFlow<FoodEditorEvent?> = _events.asStateFlow()

    /** Camera / crop results delivered through this entry's SavedStateHandle. */
    val cameraResult = savedStateHandle.getStateFlow<String?>(NavResults.CAMERA_PHOTO, null)
    val cropResult = savedStateHandle.getStateFlow<String?>(NavResults.CROPPED_PHOTO, null)

    val favorites = meals.observeFavorites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            combine(settings.settings, weights.observeLatest()) { s, w -> s to w }.collect { (s, w) ->
                _state.update { it.copy(weightUnit = s.weightUnit, lastWeight = w, calorieEstimatesEnabled = s.calorieEstimates) }
            }
        }
        viewModelScope.launch { favorites.collect { list -> _state.update { it.copy(favorites = list) } } }
    }

    private suspend fun load() {
        val s = settings.current()
        val history = meals.getRange(TimeUtils.todayEpochDay() - 60, TimeUtils.todayEpochDay())
        val titles = if (s.smartSuggestions) meals.frequentTitles() else emptyList()
        val now = LocalTime.now()
        val form: MealForm = when {
            route.mealId != 0L -> {
                val existing = meals.get(route.mealId)
                if (existing == null) {
                    _events.value = FoodEditorEvent.Closed
                    return
                }
                fromEntity(existing.meal, existing.sortedPhotos.map { it.fileName }, s.weightUnit)
            }
            route.favoriteId != 0L -> {
                val fav = meals.getFavorite(route.favoriteId)
                meals.markFavoriteUsed(route.favoriteId)
                applyFavorite(MealForm(mealType = SmartSuggestions.guessMealType(history, now)), fav)
            }
            route.repeatMealId != 0L -> {
                val source = meals.get(route.repeatMealId)?.meal
                MealForm(mealType = source?.mealType ?: SmartSuggestions.guessMealType(history, now)).let { base ->
                    if (source == null) base else base.copy(
                        title = source.title.orEmpty(),
                        items = source.items,
                        gramsText = source.portionGrams?.toString().orEmpty(),
                        kcalText = source.caloriesEstimate?.toString().orEmpty(),
                    )
                }
            }
            else -> MealForm(mealType = SmartSuggestions.guessMealType(history, now))
        }
        initialForm = form
        val draft = (decode(savedStateHandle[EDITOR_FORM]) ?: decode(meals.getDraft(draftKind)?.payload))
            ?.takeIf { it.mealId == form.mealId }
        // Android may restore a bundle captured before the transaction committed.
        if (draft != null && meals.isSaveCommitted(draft.saveToken)) {
            savedStateHandle.remove<String>(EDITOR_FORM)
            finished = true
            _events.value = FoodEditorEvent.Saved
            return
        }
        _state.update {
            it.copy(
                form = form,
                loaded = true,
                titleSuggestions = titles,
                timeSuggestions = if (s.smartSuggestions) SmartSuggestions.dishesForTime(history, now) else emptyList(),
                pendingDraft = draft,
            )
        }
    }

    private fun fromEntity(m: MealEntity, photos: List<String>, unit: WeightUnit): MealForm {
        val rt = m.time
        return MealForm(
            mealId = m.id,
            epochDay = rt.epochDay,
            minuteOfDay = TimeUtils.minutesOfDay(rt.localTime),
            zoneId = m.zoneId,
            mealType = m.mealType,
            title = m.title.orEmpty(),
            items = m.items,
            appetite = m.appetite,
            portion = m.portion,
            satiety = m.satiety,
            feelings = m.feelings.toSet(),
            feelingOther = m.feelingOther.orEmpty(),
            weightText = m.weightKg?.let { formatWeightInput(it, unit) }.orEmpty(),
            note = m.note.orEmpty(),
            photos = photos,
            gramsText = m.portionGrams?.toString().orEmpty(),
            kcalText = m.caloriesEstimate?.toString().orEmpty(),
            createdAt = m.createdAt,
        )
    }

    private fun applyFavorite(form: MealForm, fav: FavoriteMealEntity?): MealForm = if (fav == null) form else form.copy(
        title = fav.title,
        items = fav.items,
        mealType = fav.mealType ?: form.mealType,
        gramsText = fav.portionGrams?.toString() ?: form.gramsText,
        kcalText = fav.caloriesEstimate?.toString() ?: form.kcalText,
    )

    fun formatWeightInput(kg: Double, unit: WeightUnit): String =
        Units.roundTo(Units.toDisplay(kg, unit), 1).toString().replace('.', ',')

    fun update(transform: (MealForm) -> MealForm) {
        val state = _state.value
        if (!state.loaded || state.isSaving || finished || state.pendingDraft != null) return
        val form = transform(state.form)
        val owned = ownedPhotos() + form.photos.filter { it !in initialForm?.photos.orEmpty() }
        savedStateHandle[EDITOR_PHOTOS] = ArrayList(owned.distinct())
        if (form == initialForm) savedStateHandle.remove<String>(EDITOR_FORM) else savedStateHandle[EDITOR_FORM] = encode(form)
        _state.update { it.copy(form = form, dirty = form != initialForm, error = null) }
        scheduleDraft()
    }

    private fun scheduleDraft() {
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(500)
            persistDraft()
        }
    }

    private suspend fun persistDraft() = draftMutex.withLock {
        if (finished || _state.value.isSaving || _state.value.pendingDraft != null) return@withLock
        try {
            if (_state.value.dirty) meals.saveDraft(draftKind, encode(_state.value.form))
            else meals.deleteDraft(draftKind)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _state.update { it.copy(error = "draft") }
        }
    }

    fun flushDraft() {
        draftJob?.cancel()
        viewModelScope.launch { persistDraft() }
    }

    fun setDate(date: LocalDate) = update { it.copy(epochDay = date.toEpochDay()) }
    fun setTime(time: LocalTime) = update { it.copy(minuteOfDay = TimeUtils.minutesOfDay(time)) }

    fun addItem(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        update { it.copy(items = (it.items + clean).distinct()) }
    }

    fun removeItem(item: String) = update { it.copy(items = it.items - item) }

    fun toggleFeeling(f: AfterMealFeeling) = update { form ->
        val set = form.feelings.toMutableSet()
        if (!set.remove(f)) {
            // "Normal" excludes complaints and vice versa.
            if (f == AfterMealFeeling.NORMAL) set.clear() else set.remove(AfterMealFeeling.NORMAL)
            set.add(f)
        }
        form.copy(feelings = set)
    }

    fun useLastWeight() {
        val w = _state.value.lastWeight ?: return
        update { it.copy(weightText = formatWeightInput(w.weightKg, _state.value.weightUnit), alsoSaveWeight = false) }
    }

    fun applyFavorite(fav: FavoriteMealEntity) {
        viewModelScope.launch { meals.markFavoriteUsed(fav.id) }
        update { applyFavorite(it, fav) }
    }

    fun deleteFavorite(fav: FavoriteMealEntity) {
        viewModelScope.launch { meals.deleteFavorite(fav) }
    }

    /** Fills in an approximate calorie value from the offline reference table. */
    fun estimateCalories(): Boolean {
        val form = _state.value.form
        val entry = FoodReference.find(form.title.ifBlank { form.items.firstOrNull().orEmpty() }) ?: return false
        val grams = form.gramsText.toIntOrNull()
        val kcal = FoodReference.estimateKcal(entry, grams, form.portion)
        update { it.copy(kcalText = kcal.toString(), gramsText = grams?.toString() ?: entry.typicalPortionG.toString(), kcalFromReference = true) }
        return true
    }

    fun addPhotoFromUris(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.processingPhoto || _state.value.isSaving || finished || _state.value.pendingDraft != null) return
        _state.update { it.copy(processingPhoto = true) }
        viewModelScope.launch {
            var failed = false
            try {
                for (uri in uris) {
                    try {
                        val name = photoStorage.importUri(uri)
                        update { it.copy(photos = it.photos + name) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true }
                }
            } finally {
                _state.update { it.copy(processingPhoto = false, error = if (failed) "photo" else null) }
            }
        }
    }

    fun consumeCameraResult(fileName: String) {
        if (!_state.value.loaded || _state.value.pendingDraft != null || _state.value.isSaving || finished) return
        savedStateHandle[NavResults.CAMERA_PHOTO] = null
        update { it.copy(photos = it.photos + fileName) }
    }

    fun consumeCropResult(cropped: String) {
        if (!_state.value.loaded || _state.value.pendingDraft != null || _state.value.isSaving || finished) return
        val source = savedStateHandle.get<String>(NavResults.CROP_SOURCE)
        savedStateHandle[NavResults.CROPPED_PHOTO] = null
        savedStateHandle[NavResults.CROP_SOURCE] = null
        update { form ->
            val idx = form.photos.indexOf(source)
            if (idx >= 0) form.copy(photos = form.photos.toMutableList().also { it[idx] = cropped }) else form.copy(photos = form.photos + cropped)
        }
    }

    fun prepareCrop(fileName: String) {
        savedStateHandle[NavResults.CROP_SOURCE] = fileName
    }

    fun removePhoto(fileName: String) = update { it.copy(photos = it.photos - fileName) }

    fun movePhotoFirst(fileName: String) = update { it.copy(photos = listOf(fileName) + (it.photos - fileName)) }

    fun restoreDraft() {
        if (_state.value.isSaving || finished) return
        val draft = _state.value.pendingDraft ?: return
        _state.update { it.copy(pendingDraft = null) }
        update { draft }
    }

    fun dismissDraft() {
        if (_state.value.isSaving || finished) return
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            draftMutex.withLock {
                try {
                    val pending = _state.value.pendingDraft
                    meals.deleteDraft(draftKind)
                    savedStateHandle.remove<String>(EDITOR_FORM)
                    meals.deleteUnusedPhotos(pending?.photos.orEmpty() + ownedPhotos())
                    savedStateHandle.remove<ArrayList<String>>(EDITOR_PHOTOS)
                    _state.update { it.copy(pendingDraft = null, isSaving = false) }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { _state.update { it.copy(isSaving = false, error = "draft") } }
            }
        }
    }

    fun saveDraftAndClose() {
        val state = _state.value
        if (!state.loaded || state.isSaving || state.processingPhoto || state.pendingDraft != null || finished) return
        _state.update { it.copy(isSaving = true) }
        draftJob?.cancel()
        viewModelScope.launch {
            draftMutex.withLock {
                try {
                    meals.saveDraft(draftKind, encode(state.form))
                    finished = true
                    _events.value = FoodEditorEvent.Closed
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { _state.update { it.copy(isSaving = false, error = "draft") } }
            }
        }
    }

    fun discardAndClose() {
        if (_state.value.isSaving || _state.value.processingPhoto || finished) return
        _state.update { it.copy(isSaving = true) }
        draftJob?.cancel()
        viewModelScope.launch {
            draftMutex.withLock {
                try {
                    meals.deleteDraft(draftKind)
                    savedStateHandle.remove<String>(EDITOR_FORM)
                    meals.deleteUnusedPhotos(ownedPhotos() + _state.value.pendingDraft?.photos.orEmpty())
                    savedStateHandle.remove<ArrayList<String>>(EDITOR_PHOTOS)
                    finished = true
                    _events.value = FoodEditorEvent.Closed
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { _state.update { it.copy(isSaving = false, error = "draft") } }
            }
        }
    }

    fun parsedWeightKg(): Double? {
        val form = _state.value.form
        val v = Units.parseDecimal(form.weightText) ?: return null
        return Units.toKg(v, _state.value.weightUnit).takeIf { Units.isPlausibleWeightKg(it) }
    }

    fun isWeightInvalid(): Boolean = _state.value.form.weightText.isNotBlank() && parsedWeightKg() == null

    fun save() {
        val s = _state.value
        val form = s.form
        if (!s.loaded || s.isSaving || s.processingPhoto || s.pendingDraft != null || finished || isWeightInvalid()) return
        val weightKg = parsedWeightKg()
        _state.update { it.copy(isSaving = true, error = null) }
        draftJob?.cancel()
        viewModelScope.launch {
            draftMutex.withLock {
                withContext(NonCancellable) {
                    try {
                        val rt = form.recordTime
                        val entity = MealEntity(
                            id = form.mealId,
                            epochMillis = rt.epochMillis,
                            zoneId = rt.zoneId,
                            localDate = rt.epochDay,
                            mealType = form.mealType,
                            title = form.title.trim().ifBlank { null },
                            items = form.items,
                            appetite = form.appetite,
                            portion = form.portion,
                            satiety = form.satiety,
                            feelings = form.feelings.sortedBy { it.ordinal },
                            feelingOther = form.feelingOther.trim().ifBlank { null }.takeIf { AfterMealFeeling.OTHER in form.feelings },
                            weightKg = weightKg,
                            note = form.note.trim().ifBlank { null },
                            portionGrams = form.gramsText.toIntOrNull(),
                            caloriesEstimate = form.kcalText.toIntOrNull(),
                            createdAt = if (form.createdAt > 0) form.createdAt else System.currentTimeMillis(),
                        )
                        meals.save(entity, form.photos, form.alsoSaveWeight, form.saveAsFavorite, draftKind, form.saveToken)
                        finished = true
                        savedStateHandle.remove<String>(EDITOR_FORM)
                        // Only files no longer referenced by the committed record may be removed.
                        runCatching { meals.deleteUnusedPhotos(ownedPhotos()) }
                        savedStateHandle.remove<ArrayList<String>>(EDITOR_PHOTOS)
                        messages.show(R.string.food_saved)
                        _events.value = FoodEditorEvent.Saved
                    } catch (_: Exception) {
                        _state.update { it.copy(isSaving = false, error = "save") }
                    }
                }
            }
        }
    }

    fun delete() {
        val state = _state.value
        val id = state.form.mealId
        if (id == 0L || state.isSaving || state.processingPhoto || finished) return
        _state.update { it.copy(isSaving = true) }
        draftJob?.cancel()
        viewModelScope.launch {
            draftMutex.withLock {
                try {
                    val snapshot = meals.delete(id, draftKind) ?: error("Meal no longer exists")
                    finished = true
                    savedStateHandle.remove<String>(EDITOR_FORM)
                    undo.offer(undo = { meals.restore(snapshot) }, commit = { meals.purge(snapshot) })
                    _events.value = FoodEditorEvent.Deleted
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { _state.update { it.copy(isSaving = false, error = "save") } }
            }
        }
    }

    fun consumeEvent() {
        _events.value = null
    }

    fun isWeightDifferentFromLast(): Boolean {
        val kg = parsedWeightKg() ?: return false
        val last = _state.value.lastWeight ?: return true
        return kotlin.math.abs(last.weightKg - kg) >= 0.05
    }

    companion object {
        const val DRAFT_KIND = "meal"
        private const val EDITOR_FORM = "meal_editor_form"
        private const val EDITOR_PHOTOS = "meal_editor_photos"
    }
}
