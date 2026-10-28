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

    private fun editor(handle: SavedStateHandle = SavedStateHandle()): FoodEditorViewModel {
        val store = ViewModelStore().also(stores::add)
        return ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                FoodEditorViewModel(handle, meals, weights, settings, photos, UndoManager(), UserMessages()) as T
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
}
