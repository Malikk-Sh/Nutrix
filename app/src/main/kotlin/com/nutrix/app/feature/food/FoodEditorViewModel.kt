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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/** Everything the editor holds; serialisable so it can be stored as a draft. */
@Serializable
data class MealForm(
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
        val draft = if (route.mealId == 0L && route.favoriteId == 0L && route.repeatMealId == 0L) {
            meals.getDraft(DRAFT_KIND)?.let { runCatching { json.decodeFromString(MealForm.serializer(), it.payload) }.getOrNull() }
        } else null
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
        _state.update { s ->
            val newForm = transform(s.form)
            s.copy(form = newForm, dirty = newForm != initialForm)
        }
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
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(processingPhoto = true) }
            val names = uris.mapNotNull { uri -> runCatching { photoStorage.importUri(uri) }.getOrNull() }
            update { it.copy(photos = it.photos + names) }
            _state.update { it.copy(processingPhoto = false, error = if (names.size < uris.size) "photo" else null) }
        }
    }

    fun consumeCameraResult(fileName: String) {
        savedStateHandle[NavResults.CAMERA_PHOTO] = null
        update { it.copy(photos = it.photos + fileName) }
    }

    fun consumeCropResult(cropped: String) {
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
        val draft = _state.value.pendingDraft ?: return
        _state.update { it.copy(form = draft.copy(mealId = 0), pendingDraft = null, dirty = true) }
    }

    fun dismissDraft() {
        viewModelScope.launch { meals.deleteDraft(DRAFT_KIND) }
        _state.update { it.copy(pendingDraft = null) }
    }

    fun saveDraftAndClose() {
        viewModelScope.launch {
            meals.saveDraft(DRAFT_KIND, json.encodeToString(MealForm.serializer(), _state.value.form))
            _events.value = FoodEditorEvent.Closed
        }
    }

    fun discardAndClose() {
        viewModelScope.launch {
            // Photos taken for an abandoned new meal are not referenced by anything: remove them.
            val form = _state.value.form
            if (form.mealId == 0L) form.photos.forEach(photoStorage::delete)
            _events.value = FoodEditorEvent.Closed
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
        if (isWeightInvalid()) return
        viewModelScope.launch {
            val rt = form.recordTime
            val weightKg = parsedWeightKg()
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
            val id = meals.save(entity, form.photos)
            if (form.alsoSaveWeight && weightKg != null) {
                weights.saveManual(0, rt, weightKg, null)
            }
            if (form.saveAsFavorite) {
                meals.favoriteFromMeal(entity.copy(id = id), form.photos.firstOrNull())
            }
            if (form.mealId == 0L) meals.deleteDraft(DRAFT_KIND)
            messages.show(R.string.food_saved)
            _events.value = FoodEditorEvent.Saved
        }
    }

    fun delete() {
        val id = _state.value.form.mealId
        if (id == 0L) return
        viewModelScope.launch {
            val snapshot = meals.delete(id) ?: return@launch
            undo.offer(undo = { meals.restore(snapshot) }, commit = { meals.purge(snapshot) })
            _events.value = FoodEditorEvent.Deleted
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
    }
}
