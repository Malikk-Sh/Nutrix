package com.nutrix.app.feature.water

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.data.repository.DataChangeNotifier
import com.nutrix.app.data.repository.DrinkRepository
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.core.model.DrinkNutrition
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.time.RecordTime
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.domain.intake
import com.nutrix.app.data.repository.UndoManager
import com.nutrix.app.data.repository.UserMessages
import com.nutrix.app.database.NutrixDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.nutrix.app.core.time.TimeUtils
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DrinkEditorSafetyTest {
    private lateinit var db: NutrixDatabase
    private lateinit var drinks: DrinkRepository
    private lateinit var photos: PhotoStorage
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(NutrixDatabase.NAME)
        db = NutrixDatabase.build(context)
        photos = PhotoStorage(context).also { it.deleteAll() }
        drinks = DrinkRepository(db.drinkDao(), photos, DataChangeNotifier())
    }

    @After fun cleanup() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
        db.close()
    }

    private fun editor(handle: SavedStateHandle = SavedStateHandle()): Pair<DrinkEditorViewModel, ViewModelStore> {
        val store = ViewModelStore().also(stores::add)
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                DrinkEditorViewModel(handle, drinks, MealRepository(db, db.mealDao(), photos, DataChangeNotifier()), photos, UndoManager(), UserMessages()) as T
        })[DrinkEditorViewModel::class.java]
        return vm to store
    }

    private fun route(drinkId: Long = 0, mealId: Long = 0) = SavedStateHandle(mapOf("drinkId" to drinkId, "mealId" to mealId))

    @Test fun `tea starts without calories, milk stays unknown, and a chosen value survives a type change`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (vm, _) = editor()
        vm.setType(DrinkType.TEA)
        assertThat(vm.form.value.nutrition).isEqualTo(DrinkNutrition.NONE)
        vm.setType(DrinkType.MILK)
        assertThat(vm.form.value.nutrition).isNull()
        val milk = DrinkNutrition(52.0, 2.8, 2.5, 4.7)
        vm.setEnergy(EnergyInput.of(milk))
        vm.setType(DrinkType.OTHER)
        assertThat(vm.form.value.nutrition).isEqualTo(milk)
        vm.update { it.copy(volumeText = "200") }
        vm.save()
        vm.closed.first { it }
        val saved = withContext(Dispatchers.IO) { drinks.observeDay(TimeUtils.todayEpochDay()).first { it.isNotEmpty() } }.single()
        assertThat(saved.nutrition).isEqualTo(milk)
        assertThat(saved.intake.kcal).isEqualTo(104.0)
    }

    @Test fun `a half-typed value blocks saving and an old drink opens as unknown`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (vm, _) = editor()
        vm.setType(DrinkType.JUICE)
        vm.setEnergy(EnergyInput(EnergyMode.VALUE, kcalText = "4,5,"))
        assertThat(vm.form.value.energyInvalid).isTrue()
        vm.save()
        testScheduler.advanceUntilIdle()
        assertThat(db.drinkDao().count()).isEqualTo(0)
        // Saved before drinks had energy: tea is not silently read as "without calories".
        val old = withContext(Dispatchers.IO) { drinks.quickAdd(DrinkType.TEA, 250, nutrition = null) }
        val (edit, _) = editor(route(drinkId = old))
        edit.loaded.first { it }
        assertThat(edit.form.value.energy.mode).isEqualTo(EnergyMode.UNKNOWN)
    }

    @Test fun `a drink added from a meal is linked to it until it is moved to another day`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val meals = MealRepository(db, db.mealDao(), photos, DataChangeNotifier())
        val now = RecordTime.now()
        val mealId = withContext(Dispatchers.IO) {
            meals.save(MealEntity(epochMillis = now.epochMillis, zoneId = now.zoneId, localDate = now.epochDay, mealType = MealType.LUNCH), emptyList())
        }
        val (vm, _) = editor(route(mealId = mealId))
        vm.loaded.first { it }
        assertThat(vm.form.value.mealId).isEqualTo(mealId)
        vm.setType(DrinkType.JUICE)
        vm.setEnergy(EnergyInput(EnergyMode.VALUE, kcalText = "45"))
        vm.save()
        vm.closed.first { it }
        val drink = withContext(Dispatchers.IO) { drinks.observeForMeal(mealId).first { it.isNotEmpty() } }.single()
        val (edit, _) = editor(route(drinkId = drink.id))
        edit.loaded.first { it }
        edit.update { it.copy(date = it.date.minusDays(1)) }
        edit.save()
        edit.closed.first { it }
        testScheduler.advanceUntilIdle()
        assertThat(withContext(Dispatchers.IO) { drinks.get(drink.id) }!!.mealId).isNull()
    }

    private fun photo(name: String): String = name.also { photos.file(it).writeBytes(byteArrayOf(1, 2, 3)) }

    @Test fun `double tap saves a new drink exactly once`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (vm, _) = editor()
        vm.update { it.copy(volumeText = "300") }
        vm.save()
        vm.save()
        vm.closed.first { it }
        vm.save()
        testScheduler.advanceUntilIdle()
        assertThat(db.drinkDao().count()).isEqualTo(1)
    }

    @Test fun `leaving during a save keeps the photo and the drink`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (vm, store) = editor()
        val file = photo("drink-photo.jpg")
        assertThat(vm.consumeCamera(file)).isTrue()
        vm.save()
        // Back is pressed while the write is still queued, then the screen goes away.
        vm.cancel()
        store.clear()
        testScheduler.advanceUntilIdle()
        // The write continues on Room's thread after the view model is gone.
        val saved = withContext(Dispatchers.IO) { drinks.observeDay(TimeUtils.todayEpochDay()).first { it.isNotEmpty() } }.single()
        assertThat(saved.photoFile).isEqualTo(file)
        assertThat(photos.exists(file)).isTrue()
    }

    @Test fun `cancel without saving removes only the new photo`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (vm, _) = editor()
        val file = photo("unsaved.jpg")
        vm.consumeCamera(file)
        vm.cancel()
        testScheduler.advanceUntilIdle()
        assertThat(photos.exists(file)).isFalse()
        assertThat(db.drinkDao().count()).isEqualTo(0)
    }
}
