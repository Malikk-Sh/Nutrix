package com.nutrix.app.feature.food

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.data.repository.*
import com.nutrix.app.database.NutrixDatabase
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.feature.ai.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FoodEditorSafetyTest {
    private lateinit var db: NutrixDatabase
    private lateinit var meals: MealRepository
    private lateinit var weights: WeightRepository
    private lateinit var settings: SettingsRepository
    private lateinit var photos: PhotoStorage
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(NutrixDatabase.NAME)
        db = NutrixDatabase.build(context)
        settings = SettingsRepository(context).also { it.clearAll() }
        photos = PhotoStorage(context).also { it.deleteAll() }
        val notifier = DataChangeNotifier()
        meals = MealRepository(db, db.mealDao(), photos, notifier)
        weights = WeightRepository(db.weightDao(), settings, notifier)
    }

    @After fun cleanup() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
        db.close()
    }

    private fun editor(handle: SavedStateHandle = SavedStateHandle(), analyzer: FoodPhotoAnalyzer = FoodPhotoAnalyzer { _, _ ->
        throw FoodAiException(FoodAiError.KEY_MISSING)
    }): FoodEditorViewModel {
        val store = ViewModelStore().also(stores::add)
        return ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                FoodEditorViewModel(handle, meals, weights, settings, photos, UndoManager(), UserMessages(), analyzer, com.nutrix.app.data.repository.DrinkRepository(db.drinkDao(), photos, com.nutrix.app.data.repository.DataChangeNotifier())) as T
        })[FoodEditorViewModel::class.java]
    }

    private suspend fun awaitDraft(kind: String = FoodEditorViewModel.DRAFT_KIND): String =
        db.invalidationTracker.createFlow("drafts").map { meals.getDraft(kind)?.payload }.first { it != null }!!

    @Test fun `double tap saves meal weight and favorite exactly once`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = editor()
        vm.state.first { it.loaded }
        vm.update { it.copy(title = "Обед", weightText = "65", alsoSaveWeight = true, saveAsFavorite = true) }
        vm.save()
        vm.save()
        vm.events.first { it == FoodEditorEvent.Saved }
        vm.save()
        assertThat(db.mealDao().count()).isEqualTo(1)
        assertThat(db.weightDao().count()).isEqualTo(1)
        assertThat(meals.observeFavorites().first()).hasSize(1)
        assertThat(meals.getDraft(FoodEditorViewModel.DRAFT_KIND)).isNull()
    }

    @Test fun `favorite failure rolls back meal and weight and keeps form and draft for retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = editor()
        vm.state.first { it.loaded }
        vm.update { it.copy(title = "Обед", weightText = "65", alsoSaveWeight = true, saveAsFavorite = true) }
        awaitDraft()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_favorite BEFORE INSERT ON favorite_meals BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        vm.save()
        vm.state.first { it.error == "save" }
        assertThat(db.mealDao().count()).isEqualTo(0)
        assertThat(db.weightDao().count()).isEqualTo(0)
        assertThat(vm.state.value.form.title).isEqualTo("Обед")
        assertThat(meals.getDraft(FoodEditorViewModel.DRAFT_KIND)).isNotNull()
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_favorite")
        vm.save()
        vm.events.first { it == FoodEditorEvent.Saved }
        assertThat(db.mealDao().count()).isEqualTo(1)
        assertThat(db.weightDao().count()).isEqualTo(1)
    }

    @Test fun `automatic draft survives recreation and discard cannot resurrect it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = editor()
        vm.state.first { it.loaded }
        vm.update { it.copy(title = "Незаконченный ужин", note = "Сохранить автоматически") }
        awaitDraft()
        stores.first().clear()
        val restored = editor()
        restored.state.first { it.loaded }
        assertThat(restored.state.value.pendingDraft?.title).isEqualTo("Незаконченный ужин")
        restored.restoreDraft()
        restored.update { it.copy(title = "Изменённый ужин") }
        restored.discardAndClose()
        restored.events.first { it == FoodEditorEvent.Closed }
        advanceTimeBy(1000)
        runCurrent()
        assertThat(meals.getDraft(FoodEditorViewModel.DRAFT_KIND)).isNull()
    }

    @Test fun `saved state recovers input before debounce writes draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val handle = SavedStateHandle()
        val vm = editor(handle)
        vm.state.first { it.loaded }
        vm.update { it.copy(title = "Мгновенное восстановление") }
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        stores.first().clear()
        val restored = editor(restoredHandle)
        restored.state.first { it.loaded }
        assertThat(restored.state.value.pendingDraft?.title).isEqualTo("Мгновенное восстановление")
    }

    @Test fun `stale Android state cannot resurrect a draft after a committed save`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val handle = SavedStateHandle()
        val vm = editor(handle)
        vm.state.first { it.loaded }
        vm.update { it.copy(title = "Уже сохранённый обед", weightText = "65", alsoSaveWeight = true) }
        // Simulate Android retaining the last onStop bundle while a save subsequently commits.
        val stale = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        vm.save()
        vm.events.first { it == FoodEditorEvent.Saved }
        stores.first().clear()
        val restored = editor(stale)
        restored.events.first { it == FoodEditorEvent.Saved }
        restored.save()
        assertThat(db.mealDao().count()).isEqualTo(1)
        assertThat(db.weightDao().count()).isEqualTo(1)
        assertThat(meals.getDraft(FoodEditorViewModel.DRAFT_KIND)).isNull()
    }

    @Test fun `discarding edit removes draft photos and preserves original meal photo`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val original = "original.jpg".also { photos.file(it).writeText("original") }
        val extra = "extra.jpg".also { photos.file(it).writeText("new photo") }
        val id = meals.save(MealEntity(epochMillis = System.currentTimeMillis(), zoneId = "UTC",
            localDate = TimeUtils.todayEpochDay(), mealType = MealType.LUNCH), listOf(original))
        val vm = editor(SavedStateHandle(mapOf("mealId" to id)))
        vm.state.first { it.loaded }
        vm.update { it.copy(title = "Правка", photos = listOf(original, extra)) }
        awaitDraft("meal-edit-$id")
        vm.discardAndClose()
        vm.events.first { it == FoodEditorEvent.Closed }
        assertThat(photos.exists(original)).isTrue()
        assertThat(photos.exists(extra)).isFalse()
        assertThat(meals.get(id)?.meal?.title).isNull()
        assertThat(meals.getDraft("meal-edit-$id")).isNull()
    }
    private fun foodResult(file: String = "food.jpg") = FoodPhotoResult(
        FoodEstimate(true, "Гречка с курицей", listOf(FoodIngredient("Гречка", 180.0), FoodIngredient("Курица", 120.0)),
            300.0, 450.0, 35.1, 10.0, 50.0, "Масло неизвестно"), "gemini-3.1-flash-lite", file,
    )

    @Test fun `AI fills editable fields automatically and manual corrections persist with favorite`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = editor(analyzer = FoodPhotoAnalyzer { file, hint ->
            assertThat(hint).isEqualTo("Без соуса")
            foodResult(file)
        })
        vm.state.first { it.loaded }
        vm.update { it.copy(photos = listOf("food.jpg"), title = "Моё название", aiHint = "Без соуса", weightText = "65", note = "Моя заметка", saveAsFavorite = true) }
        vm.analyzePhoto("food.jpg")
        vm.state.first { it.aiResult != null && !it.analyzingPhoto }
        assertThat(vm.state.value.form.title).isEqualTo("Гречка с курицей")
        assertThat(vm.state.value.form.gramsText).isEqualTo("300")
        assertThat(vm.state.value.form.kcalText).isEqualTo("450")
        assertThat(vm.state.value.form.weightText).isEqualTo("65")
        assertThat(vm.state.value.form.note).isEqualTo("Моя заметка")
        assertThat(db.mealDao().count()).isEqualTo(0)
        vm.update { it.copy(gramsText = "320", kcalText = "470", proteinText = "36,5", fatText = "11,5", carbsText = "51,5") }
        vm.save()
        vm.events.first { it == FoodEditorEvent.Saved }
        val saved = meals.observeRecent(1).first().single().meal
        assertThat(saved.nutrition!!.proteinGrams).isEqualTo(36.5)
        assertThat(saved.nutrition!!.fatGrams).isEqualTo(11.5)
        assertThat(saved.nutrition!!.carbsGrams).isEqualTo(51.5)
        assertThat(saved.portionGrams).isEqualTo(320)
        assertThat(saved.caloriesEstimate).isEqualTo(470)
        assertThat(saved.nutrition!!.estimateModel).isEqualTo("gemini-3.1-flash-lite")
        assertThat(saved.weightKg).isEqualTo(65.0)
        assertThat(saved.items).containsExactly("Гречка", "Курица")
        assertThat(meals.observeFavorites().first().single().nutrition).isEqualTo(saved.nutrition)
    }

    @Test fun `removing selected photo cancels analysis and rejects even an uncancellable late result`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pending = CompletableDeferred<FoodPhotoResult>()
        var calls = 0
        val vm = editor(analyzer = FoodPhotoAnalyzer { _, _ -> calls++; withContext(NonCancellable) { pending.await() } })
        vm.state.first { it.loaded }
        vm.update { it.copy(photos = listOf("food.jpg"), title = "Не менять") }
        vm.analyzePhoto("food.jpg")
        vm.analyzePhoto("food.jpg")
        runCurrent()
        assertThat(calls).isEqualTo(1)
        vm.save()
        assertThat(db.mealDao().count()).isEqualTo(0)
        vm.removePhoto("food.jpg")
        pending.complete(foodResult())
        runCurrent()
        assertThat(vm.state.value.aiResult).isNull()
        assertThat(vm.state.value.analyzingPhoto).isFalse()
        assertThat(vm.state.value.form.title).isEqualTo("Не менять")
    }

    @Test fun `API failure leaves form untouched and a manual retry can succeed`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var calls = 0
        val vm = editor(analyzer = FoodPhotoAnalyzer { file, _ ->
            calls++
            if (calls == 1) throw FoodAiException(FoodAiError.QUOTA)
            foodResult(file)
        })
        vm.state.first { it.loaded }
        vm.update { it.copy(photos = listOf("food.jpg"), title = "Исходное") }
        vm.analyzePhoto("food.jpg")
        vm.state.first { it.aiError == FoodAiError.QUOTA && !it.analyzingPhoto }
        assertThat(vm.state.value.form.title).isEqualTo("Исходное")
        advanceTimeBy(5_000)
        runCurrent()
        assertThat(calls).isEqualTo(1)
        vm.analyzePhoto("food.jpg")
        vm.state.first { it.aiResult != null && !it.analyzingPhoto }
        assertThat(vm.state.value.form.title).isEqualTo("Гречка с курицей")
        assertThat(calls).isEqualTo(2)
    }

    @Test fun `transient failures retry once with progress and a manual retry can then succeed`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var calls = 0
        val vm = editor(analyzer = FoodPhotoAnalyzer { file, hint ->
            assertThat(file).isEqualTo("food.jpg")
            assertThat(hint).isEqualTo("Без соуса")
            calls++
            if (calls <= 2) throw FoodAiException(FoodAiError.SERVER)
            foodResult(file)
        })
        vm.state.first { it.loaded }
        vm.update { it.copy(photos = listOf("food.jpg"), title = "Исходное", aiHint = "Без соуса") }
        vm.analyzePhoto("food.jpg")
        runCurrent()
        assertThat(vm.state.value.analyzingPhoto).isTrue()
        assertThat(vm.state.value.aiRetrying).isTrue()
        assertThat(vm.state.value.aiAttempt).isEqualTo(1)
        assertThat(vm.state.value.form.title).isEqualTo("Исходное")
        advanceTimeBy(FoodEditorViewModel.AI_RETRY_DELAY_MS)
        runCurrent()
        assertThat(calls).isEqualTo(2)
        assertThat(vm.state.value.aiAttempt).isEqualTo(2)
        assertThat(vm.state.value.aiError).isEqualTo(FoodAiError.SERVER)
        assertThat(vm.state.value.analyzingPhoto).isFalse()
        assertThat(vm.state.value.aiRetrying).isFalse()
        assertThat(vm.state.value.form.kcalText).isEmpty()
        advanceTimeBy(10_000)
        runCurrent()
        assertThat(calls).isEqualTo(2)
        vm.analyzePhoto("food.jpg")
        vm.state.first { it.aiResult != null && !it.analyzingPhoto }
        assertThat(calls).isEqualTo(3)
        assertThat(vm.state.value.aiError).isNull()
        assertThat(vm.state.value.form.kcalText).isEqualTo("450")
    }

    @Test fun `cancellation during retry delay prevents the second upload and keeps input`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var calls = 0
        val vm = editor(analyzer = FoodPhotoAnalyzer { _, _ ->
            calls++
            throw FoodAiException(FoodAiError.TIMEOUT)
        })
        vm.state.first { it.loaded }
        vm.update { it.copy(photos = listOf("food.jpg"), title = "Ручное блюдо", gramsText = "180") }
        vm.analyzePhoto("food.jpg")
        runCurrent()
        assertThat(vm.state.value.aiRetrying).isTrue()
        vm.cancelAiAnalysis()
        advanceTimeBy(5_000)
        runCurrent()
        assertThat(calls).isEqualTo(1)
        assertThat(vm.state.value.analyzingPhoto).isFalse()
        assertThat(vm.state.value.aiError).isNull()
        assertThat(vm.state.value.form.title).isEqualTo("Ручное блюдо")
        assertThat(vm.state.value.form.gramsText).isEqualTo("180")
    }

    @Test fun `key and invalid response errors do not repeat uploads automatically`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var calls = 0
        var reason = FoodAiError.KEY_INVALID
        val vm = editor(analyzer = FoodPhotoAnalyzer { _, _ -> calls++; throw FoodAiException(reason) })
        vm.state.first { it.loaded }
        vm.update { it.copy(photos = listOf("food.jpg"), gramsText = "180") }
        vm.analyzePhoto("food.jpg")
        vm.state.first { it.aiError == FoodAiError.KEY_INVALID && !it.analyzingPhoto }
        advanceTimeBy(5_000)
        runCurrent()
        assertThat(calls).isEqualTo(1)
        reason = FoodAiError.INVALID_RESPONSE
        vm.analyzePhoto("food.jpg")
        vm.state.first { it.aiError == FoodAiError.INVALID_RESPONSE && !it.analyzingPhoto }
        advanceTimeBy(5_000)
        runCurrent()
        assertThat(calls).isEqualTo(2)
        assertThat(vm.state.value.form.gramsText).isEqualTo("180")
    }

    @Test fun `a late successful response preserves fields edited while the request was running`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pending = CompletableDeferred<FoodPhotoResult>()
        val vm = editor(analyzer = FoodPhotoAnalyzer { _, _ -> pending.await() })
        vm.state.first { it.loaded }
        vm.update { it.copy(photos = listOf("food.jpg"), title = "Исходное", weightText = "65") }
        vm.analyzePhoto("food.jpg")
        runCurrent()
        vm.update { it.copy(title = "Исправленное вручную", gramsText = "280", kcalText = "440", proteinText = "33,5", note = "Сохранить заметку") }
        pending.complete(foodResult())
        vm.state.first { it.aiResult != null && !it.analyzingPhoto }
        val form = vm.state.value.form
        assertThat(form.title).isEqualTo("Исправленное вручную")
        assertThat(form.gramsText).isEqualTo("280")
        assertThat(form.kcalText).isEqualTo("440")
        assertThat(form.proteinText).isEqualTo("33,5")
        assertThat(form.fatText).isEqualTo("10,0")
        assertThat(form.carbsText).isEqualTo("50,0")
        assertThat(form.weightText).isEqualTo("65")
        assertThat(form.note).isEqualTo("Сохранить заметку")
    }

    @Test fun `invalid macros prevent save while unknown macros remain null`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = editor()
        vm.state.first { it.loaded }
        vm.update { it.copy(title = "Обед", proteinText = "-1") }
        assertThat(vm.isNutritionInvalid()).isTrue()
        vm.save()
        assertThat(db.mealDao().count()).isEqualTo(0)
        vm.update { it.copy(proteinText = "12,5", fatText = "", carbsText = "") }
        vm.save()
        vm.events.first { it == FoodEditorEvent.Saved }
        val nutrition = meals.observeRecent(1).first().single().meal.nutrition!!
        assertThat(nutrition.proteinGrams).isEqualTo(12.5)
        assertThat(nutrition.fatGrams).isNull()
        assertThat(nutrition.carbsGrams).isNull()
    }

}
