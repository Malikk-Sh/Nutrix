package com.nutrix.app.feature.food

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.DishSource
import com.nutrix.app.core.model.MealDish
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.data.repository.DataChangeNotifier
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.data.repository.UndoManager
import com.nutrix.app.data.repository.UserMessages
import com.nutrix.app.data.repository.WeightRepository
import com.nutrix.app.database.NutrixDatabase
import com.nutrix.app.database.entity.FavoriteMealEntity
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.feature.ai.FoodEstimate
import com.nutrix.app.feature.ai.FoodIngredient
import com.nutrix.app.feature.ai.FoodPhotoAnalyzer
import com.nutrix.app.feature.ai.FoodPhotoResult
import com.nutrix.app.navigation.FoodEditorRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class MealDishesTest {
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

    private fun editor(handle: SavedStateHandle = SavedStateHandle(), analyzer: FoodPhotoAnalyzer = FoodPhotoAnalyzer { file, _ -> result(file) }): FoodEditorViewModel {
        val store = ViewModelStore().also(stores::add)
        return ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                FoodEditorViewModel(handle, meals, weights, settings, photos, UndoManager(), UserMessages(), analyzer) as T
        })[FoodEditorViewModel::class.java]
    }

    private fun route(mealId: Long = 0, favoriteId: Long = 0, repeatMealId: Long = 0) = SavedStateHandle(
        mapOf("mealId" to mealId, "favoriteId" to favoriteId, "openCamera" to false, "repeatMealId" to repeatMealId),
    )

    private fun result(file: String, name: String = "Суп", kcal: Double = 250.0) = FoodPhotoResult(
        FoodEstimate(true, name, listOf(FoodIngredient("Бульон", null)), 300.0, kcal, 10.0, 8.0, 20.0, null), "gemini-test", file,
    )

    private suspend fun FoodEditorViewModel.loaded() = state.first { it.loaded }

    private suspend fun FoodEditorViewModel.saved(): MealEntity {
        save()
        events.first { it == FoodEditorEvent.Saved }
        return meals.observeRecent(1).first().single().meal
    }

    @Test fun `soup, main and a drink are one meal of 750 kcal and removing one dish leaves the rest`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = editor()
        vm.loaded()
        vm.update { it.copy(title = "Обед") }
        repeat(3) { vm.addDish() }
        val ids = vm.state.value.form.dishes.map { it.id }
        listOf(Triple("Суп", "300", "250"), Triple("Второе", "250", "400"), Triple("Компот", "200", "100")).forEachIndexed { i, (name, grams, kcal) ->
            vm.updateDish(ids[i]) { it.copy(name = name, gramsText = grams, kcalText = kcal) }
        }
        vm.removeDish(ids[1])
        vm.updateDish(ids[2]) { it.copy(share = 0.5) }
        val saved = vm.saved()
        assertThat(db.mealDao().count()).isEqualTo(1)
        assertThat(saved.dishes!!.map { it.name }).containsExactly("Суп", "Компот").inOrder()
        // 250 + half of 100.
        assertThat(saved.caloriesEstimate).isEqualTo(300)
        assertThat(saved.portionGrams).isEqualTo(400)
        assertThat(saved.title).isEqualTo("Обед")
    }

    @Test fun `a second recognised photo never silently replaces the first one`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = editor(analyzer = FoodPhotoAnalyzer { file, _ -> if (file == "a.jpg") result(file, "Суп", 250.0) else result(file, "Плов", 400.0) })
        vm.loaded()
        vm.update { it.copy(photos = listOf("a.jpg", "b.jpg")) }
        // The first result fills the empty meal as before.
        vm.analyzePhoto("a.jpg")
        vm.state.first { it.aiResult != null && !it.analyzingPhoto }
        assertThat(vm.state.value.form.kcalText).isEqualTo("250")
        assertThat(vm.state.value.aiChoice).isNull()
        // The second one waits for a choice and changes nothing yet.
        vm.analyzePhoto("b.jpg")
        vm.state.first { it.aiChoice != null && !it.analyzingPhoto }
        assertThat(vm.state.value.form.kcalText).isEqualTo("250")
        assertThat(vm.state.value.form.hasDishes).isFalse()
        // "Another dish": the first estimate becomes a dish as it was, the new one is added next to it.
        vm.applyAiAsNewDish()
        val dishes = vm.state.value.form.dishes
        assertThat(dishes.map { it.name to it.kcalText }).containsExactly("Суп" to "250", "Плов" to "400").inOrder()
        assertThat(dishes[0].source).isEqualTo(DishSource.MEAL_ESTIMATE)
        assertThat(dishes[1].source).isEqualTo(DishSource.PHOTO)
        assertThat(dishes[1].photos).containsExactly("b.jpg")
        assertThat(vm.state.value.form.kcalText).isEmpty()
        assertThat(vm.saved().caloriesEstimate).isEqualTo(650)
    }

    @Test fun `the same dish seen again updates it instead of counting it twice`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = editor(analyzer = FoodPhotoAnalyzer { file, _ -> result(file, "Суп", 280.0) })
        vm.loaded()
        vm.update { it.copy(photos = listOf("a.jpg", "b.jpg")) }
        vm.addDish()
        val soup = vm.state.value.form.dishes.single()
        vm.updateDish(soup.id) { it.copy(name = "Мой суп", kcalText = "250", photos = listOf("a.jpg")) }
        vm.analyzePhoto("b.jpg")
        vm.state.first { it.aiChoice != null && !it.analyzingPhoto }
        vm.applyAiToDish(soup.id)
        val updated = vm.state.value.form.dishes.single()
        assertThat(updated.name).isEqualTo("Мой суп")
        assertThat(updated.kcalText).isEqualTo("280")
        assertThat(updated.photos).containsExactly("a.jpg", "b.jpg").inOrder()
        assertThat(vm.saved().caloriesEstimate).isEqualTo(280)
    }

    @Test fun `replacing a whole-meal estimate is a choice too`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = editor(analyzer = FoodPhotoAnalyzer { file, _ -> result(file, "Суп", 310.0) })
        vm.loaded()
        vm.update { it.copy(photos = listOf("a.jpg"), title = "Обед", kcalText = "500") }
        vm.analyzePhoto("a.jpg")
        vm.state.first { it.aiChoice != null && !it.analyzingPhoto }
        assertThat(vm.state.value.form.kcalText).isEqualTo("500")
        vm.applyAiReplace()
        assertThat(vm.state.value.form.kcalText).isEqualTo("310")
        assertThat(vm.state.value.form.title).isEqualTo("Обед")
        assertThat(vm.state.value.aiChoice).isNull()
    }

    @Test fun `an existing meal opens with its dishes, keeps them, and its photo removal unlinks them`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val id = meals.save(MealEntity(epochMillis = 0, zoneId = "UTC", localDate = 0, mealType = MealType.LUNCH, title = "Обед",
            dishes = listOf(MealDish(name = "Суп", kcal = 250.0, photos = listOf("a.jpg")), MealDish(name = "Хлеб", kcal = 80.0))), listOf("a.jpg"))
        val vm = editor(route(mealId = id))
        vm.loaded()
        val form = vm.state.value.form
        assertThat(form.dishes.map { it.name }).containsExactly("Суп", "Хлеб").inOrder()
        // The stored totals are derived and not offered as inputs.
        assertThat(form.kcalText).isEmpty()
        vm.removePhoto("a.jpg")
        assertThat(vm.state.value.form.dishes.first().photos).isEmpty()
        val saved = vm.saved()
        assertThat(saved.caloriesEstimate).isEqualTo(330)
        assertThat(saved.dishes!!.first().photos).isEmpty()
    }

    @Test fun `a favorite of dishes adds them again, and repeating a meal copies its dishes without photos`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val favId = meals.saveFavorite(FavoriteMealEntity(title = "Мой завтрак", dishes = listOf(MealDish(name = "Каша", kcal = 300.0), MealDish(name = "Чай", kcal = 20.0))))
        val vm = editor(route(favoriteId = favId))
        vm.loaded()
        assertThat(vm.state.value.form.dishes.map { it.name to it.source }).containsExactly("Каша" to DishSource.FAVORITE, "Чай" to DishSource.FAVORITE).inOrder()
        assertThat(vm.saved().caloriesEstimate).isEqualTo(320)

        val mealId = meals.save(MealEntity(epochMillis = 0, zoneId = "UTC", localDate = 0, mealType = MealType.LUNCH, title = "Обед",
            dishes = listOf(MealDish(name = "Суп", kcal = 250.0, photos = listOf("a.jpg")))), listOf("a.jpg"))
        val repeat = editor(route(repeatMealId = mealId))
        repeat.loaded()
        val copy = repeat.state.value.form.dishes.single()
        assertThat(copy.name).isEqualTo("Суп")
        assertThat(copy.photos).isEmpty()
        assertThat(copy.id).isNotEqualTo(meals.get(mealId)!!.meal.dishes!!.single().id)
    }

    @Test fun `saving a meal of dishes as a favorite keeps the dishes but not the photos`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = editor()
        vm.loaded()
        vm.update { it.copy(title = "Ужин", photos = listOf("a.jpg"), saveAsFavorite = true) }
        vm.addDish()
        val d = vm.state.value.form.dishes.single()
        vm.updateDish(d.id) { it.copy(name = "Рыба", kcalText = "350", photos = listOf("a.jpg")) }
        vm.saved()
        val fav = meals.observeFavorites().first().single()
        assertThat(fav.dishes!!.single().name).isEqualTo("Рыба")
        assertThat(fav.dishes!!.single().photos).isEmpty()
        assertThat(fav.caloriesEstimate).isEqualTo(350)
    }

    @Test fun `deleting and undoing a meal restores its dishes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val id = meals.save(MealEntity(epochMillis = 0, zoneId = "UTC", localDate = 0, mealType = MealType.LUNCH,
            dishes = listOf(MealDish(name = "Суп", kcal = 250.0))), emptyList())
        val snapshot = meals.delete(id)!!
        assertThat(db.mealDao().count()).isEqualTo(0)
        meals.restore(snapshot)
        assertThat(meals.get(id)!!.meal.dishes!!.single().name).isEqualTo("Суп")
        assertThat(meals.get(id)!!.meal.caloriesEstimate).isEqualTo(250)
    }
}
