package com.nutrix.app.feature.food

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.AfterMealFeeling
import com.nutrix.app.core.model.DishSource
import com.nutrix.app.core.model.MealNutrition
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.model.PortionEaten
import com.nutrix.app.core.model.Satiety
import com.nutrix.app.core.model.WeightUnit
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.core.time.RecordTime
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.core.util.Units
import com.nutrix.app.data.repository.DrinkRepository
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.data.repository.UndoManager
import com.nutrix.app.data.repository.UserMessages
import com.nutrix.app.R
import com.nutrix.app.data.repository.WeightRepository
import com.nutrix.app.database.entity.DrinkEntity
import com.nutrix.app.database.entity.FavoriteMealEntity
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.database.entity.WeightEntity
import com.nutrix.app.feature.ai.FoodPhotoAnalyzer
import com.nutrix.app.feature.ai.FoodPhotoResult
import com.nutrix.app.feature.ai.FoodAiError
import com.nutrix.app.feature.ai.FoodAiException
import com.nutrix.app.feature.ai.isTransient
import kotlin.math.roundToInt
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
import kotlinx.coroutines.flow.flowOf
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
    val proteinText: String = "",
    val fatText: String = "",
    val carbsText: String = "",
    val nutritionInfo: MealNutrition? = null,
    val aiHint: String = "",
    val saveAsFavorite: Boolean = false,
    val createdAt: Long = 0,
    /**
     * Dishes of the meal. Empty keeps the whole-meal estimate above (grams, kcal, macros); with dishes the
     * meal's totals are computed from them and the whole-meal fields are not used, so nothing counts twice.
     */
    val dishes: List<DishForm> = emptyList(),
) {
    val hasDishes: Boolean get() = dishes.isNotEmpty()
    /** A whole-meal estimate has been entered (by hand, from the reference table or from a photo). */
    val hasMealEstimate: Boolean get() = listOf(gramsText, kcalText, proteinText, fatText, carbsText).any(String::isNotBlank)
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
    val analyzingPhoto: Boolean = false,
    val aiAttempt: Int = 0,
    val aiRetrying: Boolean = false,
    val aiResult: FoodPhotoResult? = null,
    /** A recognised photo that would change values already in the meal: applied only after the user picks how. */
    val aiChoice: FoodPhotoResult? = null,
    val aiError: FoodAiError? = null,
    val hasGeminiKey: Boolean = false,
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
    private val foodAi: FoodPhotoAnalyzer,
    private val drinks: DrinkRepository,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<FoodEditorRoute>()

    /** Drinks recorded separately and linked to this meal; they keep their own volume and energy. */
    val linkedDrinks: StateFlow<List<DrinkEntity>> =
        (if (route.mealId != 0L) drinks.observeForMeal(route.mealId) else flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val json = Json { ignoreUnknownKeys = true }
    private var initialForm: MealForm? = null
    private val draftMutex = Mutex()
    private var draftJob: Job? = null
    private var finished = false
    private var aiJob: Job? = null
    private var aiRequest = 0L
    private var requestedPhoto: String? = null
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

    val favorites = meals.observeFavorites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch { settings.hasGeminiKey.collect { key -> _state.update { it.copy(hasGeminiKey = key) } } }
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
                    if (source == null) base
                    else if (!source.dishes.isNullOrEmpty()) base.copy(
                        title = source.title.orEmpty(),
                        dishes = source.dishes.map { DishForm.copyOf(it, it.source) },
                    )
                    else base.copy(
                        title = source.title.orEmpty(),
                        items = source.items,
                        gramsText = source.portionGrams?.toString().orEmpty(),
                        kcalText = source.caloriesEstimate?.toString().orEmpty(),
                        proteinText = macroText(source.nutrition?.proteinGrams),
                        fatText = macroText(source.nutrition?.fatGrams),
                        carbsText = macroText(source.nutrition?.carbsGrams),
                        nutritionInfo = source.nutrition,
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
        val dishes = m.dishes.orEmpty()
        if (dishes.isNotEmpty()) {
            // The stored totals are derived from the dishes; the editor recomputes them instead of showing them as inputs.
            return fromEntity(m.copy(dishes = null, portionGrams = null, caloriesEstimate = null, nutrition = null, items = emptyList()), photos, unit)
                .copy(dishes = dishes.map(DishForm::of))
        }
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
            proteinText = macroText(m.nutrition?.proteinGrams),
            fatText = macroText(m.nutrition?.fatGrams),
            carbsText = macroText(m.nutrition?.carbsGrams),
            nutritionInfo = m.nutrition,
            createdAt = m.createdAt,
        )
    }

    private fun applyFavorite(form: MealForm, fav: FavoriteMealEntity?): MealForm = when {
        fav == null -> form
        // A favorite made of dishes adds them; a meal that already has dishes or an estimate keeps them.
        !fav.dishes.isNullOrEmpty() -> asDishes(form).let { f ->
            f.copy(title = f.title.ifBlank { fav.title }, mealType = fav.mealType ?: f.mealType,
                dishes = f.dishes + fav.dishes.map { DishForm.copyOf(it, DishSource.FAVORITE) })
        }
        form.hasDishes -> form.copy(dishes = form.dishes + favoriteDish(fav))
        else -> applyWholeFavorite(form, fav)
    }

    private fun favoriteDish(fav: FavoriteMealEntity) = DishForm(
        name = fav.title, components = fav.items, gramsText = fav.portionGrams?.toString().orEmpty(), kcalText = fav.caloriesEstimate?.toString().orEmpty(),
        proteinText = macroText(fav.nutrition?.proteinGrams), fatText = macroText(fav.nutrition?.fatGrams), carbsText = macroText(fav.nutrition?.carbsGrams),
        source = DishSource.FAVORITE,
    )

    private fun applyWholeFavorite(form: MealForm, fav: FavoriteMealEntity): MealForm = form.copy(
        title = fav.title,
        items = fav.items,
        mealType = fav.mealType ?: form.mealType,
        gramsText = fav.portionGrams?.toString() ?: form.gramsText,
        kcalText = fav.caloriesEstimate?.toString() ?: form.kcalText,
        proteinText = macroText(fav.nutrition?.proteinGrams),
        fatText = macroText(fav.nutrition?.fatGrams),
        carbsText = macroText(fav.nutrition?.carbsGrams),
        nutritionInfo = fav.nutrition,
    )

    fun formatWeightInput(kg: Double, unit: WeightUnit): String =
        Units.roundTo(Units.toDisplay(kg, unit), 1).toString().replace('.', ',')

    fun update(transform: (MealForm) -> MealForm) {
        val state = _state.value
        if (!state.loaded || state.isSaving || finished || state.pendingDraft != null) return
        val form = transform(state.form)
        if (requestedPhoto != null && (requestedPhoto !in form.photos || form.aiHint != state.form.aiHint)) cancelAiAnalysis()
        val owned = ownedPhotos() + form.photos.filter { it !in initialForm?.photos.orEmpty() }
        savedStateHandle[EDITOR_PHOTOS] = ArrayList(owned.distinct())
        if (form == initialForm) savedStateHandle.remove<String>(EDITOR_FORM) else savedStateHandle[EDITOR_FORM] = encode(form)
        _state.update { it.copy(form = form, dirty = form != initialForm, error = null,
            aiResult = it.aiResult?.takeIf { result -> result.photoFile in form.photos }) }
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

    fun consumeCameraResult(fileName: String): Boolean {
        if (!_state.value.loaded || _state.value.pendingDraft != null || _state.value.isSaving || finished) return false
        if (fileName !in _state.value.form.photos) update { it.copy(photos = it.photos + fileName) }
        return true
    }

    fun consumeCropResult(cropped: String): Boolean {
        if (!_state.value.loaded || _state.value.pendingDraft != null || _state.value.isSaving || finished) return false
        val source = savedStateHandle.get<String>(NavResults.CROP_SOURCE)
        savedStateHandle[NavResults.CROP_SOURCE] = null
        if (cropped !in _state.value.form.photos) update { form ->
            val idx = form.photos.indexOf(source)
            if (idx >= 0) form.copy(photos = form.photos.toMutableList().also { it[idx] = cropped }) else form.copy(photos = form.photos + cropped)
        }
        return true
    }

    fun prepareCrop(fileName: String) {
        savedStateHandle[NavResults.CROP_SOURCE] = fileName
    }

    fun removePhoto(fileName: String) = update { form ->
        form.copy(photos = form.photos - fileName, dishes = form.dishes.map { d -> d.copy(photos = d.photos - fileName) })
    }

    // Dishes

    /**
     * Switches to dishes. A whole-meal estimate already entered becomes the first dish as it is (no split is
     * invented and nothing is re-read with the eaten share), so the two can never be added together.
     */
    private fun asDishes(form: MealForm): MealForm {
        // Products typed for the whole meal belong to it too; only a bare name stays the meal's name.
        if (form.hasDishes || (!form.hasMealEstimate && form.items.isEmpty())) return form
        val first = DishForm(
            name = form.title.ifBlank { form.items.firstOrNull().orEmpty() }, components = form.items,
            gramsText = form.gramsText, kcalText = form.kcalText, proteinText = form.proteinText, fatText = form.fatText, carbsText = form.carbsText,
            source = DishSource.MEAL_ESTIMATE, estimateModel = form.nutritionInfo?.estimateModel, uncertainty = form.nutritionInfo?.uncertainty,
        )
        return form.copy(dishes = listOf(first), gramsText = "", kcalText = "", proteinText = "", fatText = "", carbsText = "",
            kcalFromReference = false, nutritionInfo = null, items = emptyList())
    }

    fun addDish() = update { asDishes(it).let { f -> f.copy(dishes = f.dishes + DishForm()) } }

    fun updateDish(id: String, transform: (DishForm) -> DishForm) =
        update { form -> form.copy(dishes = form.dishes.map { if (it.id == id) transform(it) else it }) }

    /** Removing a dish removes only its numbers; the others and the meal stay as they are. */
    fun removeDish(id: String) = update { form -> form.copy(dishes = form.dishes.filterNot { it.id == id }) }

    fun toggleDishPhoto(id: String, photo: String) = updateDish(id) { d ->
        if (photo in d.photos) d.copy(photos = d.photos - photo) else d.copy(photos = d.photos + photo)
    }

    fun addFavoriteAsDish(fav: FavoriteMealEntity) {
        viewModelScope.launch { meals.markFavoriteUsed(fav.id) }
        update { form ->
            val f = asDishes(form)
            f.copy(title = f.title.ifBlank { fav.title },
                dishes = f.dishes + (fav.dishes?.takeIf { it.isNotEmpty() }?.map { DishForm.copyOf(it, DishSource.FAVORITE) } ?: listOf(favoriteDish(fav))))
        }
    }

    private fun dishFromAi(result: FoodPhotoResult): DishForm {
        val e = result.estimate
        return DishForm(
            name = e.dishName, components = e.ingredients.map { it.name }.distinct(),
            gramsText = e.portionGrams?.roundToInt()?.coerceAtLeast(1)?.toString().orEmpty(), kcalText = e.caloriesKcal?.roundToInt()?.toString().orEmpty(),
            proteinText = macroText(e.proteinGrams), fatText = macroText(e.fatGrams), carbsText = macroText(e.carbsGrams),
            source = DishSource.PHOTO, photos = listOf(result.photoFile), estimateModel = result.model, uncertainty = e.uncertainty,
        )
    }

    /** The recognised photo becomes a new dish; an earlier whole-meal estimate becomes the first dish. */
    fun applyAiAsNewDish() {
        val result = _state.value.aiChoice ?: return
        update { form -> asDishes(form).let { f -> f.copy(title = f.title.ifBlank { result.estimate.dishName }, dishes = f.dishes + dishFromAi(result)) } }
        _state.update { it.copy(aiChoice = null) }
    }

    /** Another view of a dish already in the meal: its numbers are replaced, its name and photos kept. */
    fun applyAiToDish(id: String) {
        val result = _state.value.aiChoice ?: return
        val fresh = dishFromAi(result)
        updateDish(id) { d ->
            fresh.copy(id = d.id, name = d.name.ifBlank { fresh.name }, photos = (d.photos + result.photoFile).distinct())
        }
        _state.update { it.copy(aiChoice = null) }
    }

    /** Replaces the whole-meal estimate, as the only change the user asked for. */
    fun applyAiReplace() {
        val result = _state.value.aiChoice ?: return
        if (_state.value.form.hasDishes) return
        fillAiFields(result, MealForm(), overwrite = true)
        _state.update { it.copy(aiChoice = null) }
    }

    fun dismissAiChoice() = _state.update { it.copy(aiChoice = null) }

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
        cancelAiAnalysis()
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
        cancelAiAnalysis()
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

    fun analyzePhoto(fileName: String) {
        val s = _state.value
        if (!s.loaded || s.isSaving || s.processingPhoto || s.analyzingPhoto || s.pendingDraft != null || finished) return
        if (fileName !in s.form.photos) { _state.update { it.copy(aiError = FoodAiError.PHOTO_UNAVAILABLE) }; return }
        val request = ++aiRequest
        requestedPhoto = fileName
        _state.update { it.copy(analyzingPhoto = true, aiAttempt = 1, aiRetrying = false, aiResult = null, aiError = null) }
        aiJob = viewModelScope.launch {
            try {
                for (attempt in 1..MAX_AI_ATTEMPTS) {
                    if (request != aiRequest || finished) return@launch
                    _state.update { it.copy(aiAttempt = attempt, aiRetrying = false) }
                    try {
                        val result = foodAi.analyze(fileName, s.form.aiHint)
                        if (request != aiRequest || finished) return@launch
                        if (result.photoFile != fileName || result.photoFile !in _state.value.form.photos) {
                            throw FoodAiException(FoodAiError.PHOTO_CHANGED)
                        }
                        // A result never silently replaces values already in the meal: the user chooses how to use it.
                        if (s.form.hasDishes || s.form.hasMealEstimate) {
                            _state.update { it.copy(aiResult = result, aiChoice = result, aiError = null) }
                        } else {
                            fillAiFields(result, s.form)
                            _state.update { it.copy(aiResult = result, aiError = null) }
                        }
                        return@launch
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        if (request != aiRequest || finished) return@launch
                        val reason = (error as? FoodAiException)?.reason ?: FoodAiError.INVALID_RESPONSE
                        if (attempt < MAX_AI_ATTEMPTS && reason.isTransient) {
                            _state.update { it.copy(aiRetrying = true) }
                            delay(AI_RETRY_DELAY_MS)
                        } else {
                            _state.update { it.copy(aiError = reason) }
                            return@launch
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            finally {
                if (request == aiRequest) _state.update { it.copy(analyzingPhoto = false, aiRetrying = false) }
            }
        }
    }

    fun cancelAiAnalysis() {
        aiRequest++
        aiJob?.cancel()
        aiJob = null
        requestedPhoto = null
        _state.update { it.copy(analyzingPhoto = false, aiAttempt = 0, aiRetrying = false, aiResult = null, aiChoice = null, aiError = null) }
    }

    private fun fillAiFields(result: FoodPhotoResult, requestedForm: MealForm, overwrite: Boolean = false) {
        val e = result.estimate
        // Inputs changed by the user while a request was in flight take precedence, unless the user chose to replace them.
        fun <T> keep(now: T, then: T, new: T) = if (overwrite || now == then) new else now
        update { it.copy(
            title = if (overwrite) it.title.ifBlank { e.dishName } else keep(it.title, requestedForm.title, e.dishName),
            items = keep(it.items, requestedForm.items, e.ingredients.map { ingredient -> ingredient.name }.distinct()),
            gramsText = keep(it.gramsText, requestedForm.gramsText, e.portionGrams?.roundToInt()?.coerceAtLeast(1)?.toString().orEmpty()),
            kcalText = keep(it.kcalText, requestedForm.kcalText, e.caloriesKcal?.roundToInt()?.toString().orEmpty()),
            kcalFromReference = false,
            proteinText = keep(it.proteinText, requestedForm.proteinText, macroText(e.proteinGrams)),
            fatText = keep(it.fatText, requestedForm.fatText, macroText(e.fatGrams)),
            carbsText = keep(it.carbsText, requestedForm.carbsText, macroText(e.carbsGrams)),
            nutritionInfo = MealNutrition(estimateModel = result.model, estimatedAt = System.currentTimeMillis(), uncertainty = e.uncertainty),
        ) }
    }

    private fun macroText(value: Double?): String = value?.let { Units.roundTo(it, 1).toString().replace('.', ',') }.orEmpty()
    private fun macroValue(text: String): Double? = Units.parseDecimal(text)?.takeIf { it.isFinite() && it in 0.0..10_000.0 }
    fun isNutritionInvalid(): Boolean = _state.value.form.let { form ->
        listOf(form.proteinText, form.fatText, form.carbsText).any { it.isNotBlank() && macroValue(it) == null } || form.dishes.any { it.invalid }
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
        if (!s.loaded || s.isSaving || s.processingPhoto || s.pendingDraft != null || s.analyzingPhoto || finished || isWeightInvalid() || isNutritionInvalid()) return
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
                            nutrition = (form.nutritionInfo ?: MealNutrition()).copy(
                                proteinGrams = macroValue(form.proteinText), fatGrams = macroValue(form.fatText), carbsGrams = macroValue(form.carbsText),
                            ).takeIf { form.nutritionInfo != null || listOf(form.proteinText, form.fatText, form.carbsText).any(String::isNotBlank) },
                            createdAt = if (form.createdAt > 0) form.createdAt else System.currentTimeMillis(),
                            dishes = form.dishes.map(DishForm::toDish).takeIf { it.isNotEmpty() },
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
        cancelAiAnalysis()
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
        const val MAX_AI_ATTEMPTS = 2
        const val AI_RETRY_DELAY_MS = 2_000L
        const val DRAFT_KIND = "meal"
        private const val EDITOR_FORM = "meal_editor_form"
        private const val EDITOR_PHOTOS = "meal_editor_photos"
    }
}
