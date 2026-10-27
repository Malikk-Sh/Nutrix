package com.nutrix.app.feature.ai

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.MainActivity
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.core.security.AppLockManager
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.database.entity.MealEntity
import dagger.hilt.android.testing.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId
import javax.inject.Inject

@HiltAndroidTest
@UninstallModules(FoodAiBindings::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "w393dp-h852dp-xhdpi")
class FoodAiUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @BindValue @JvmField val analyzer: FoodPhotoAnalyzer = FoodPhotoAnalyzer { file, _ ->
        FoodPhotoResult(FoodEstimate(true, "Гречка с курицей", listOf(FoodIngredient("Гречка", 180.0), FoodIngredient("Курица", 120.0)),
            300.0, 450.0, 35.0, 10.0, 50.0, "Оценка по фото"), GeminiProtocol.DEFAULT_MODEL, file)
    }
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var meals: MealRepository
    @Inject lateinit var photos: PhotoStorage
    @Inject lateinit var lock: AppLockManager
    private lateinit var scenario: ActivityScenario<MainActivity>
    private var mealId = 0L

    @Before fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext<Context>())
        hilt.inject()
        runBlocking {
            settings.clearAll()
            settings.setGeminiKeyBlob("fixture-analyzer-does-not-read-key")
            val photo = photos.file("ai-ui.jpg")
            val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.GREEN)
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            mealId = meals.save(MealEntity(epochMillis = System.currentTimeMillis(), localDate = TimeUtils.todayEpochDay(),
                zoneId = ZoneId.systemDefault().id, mealType = MealType.LUNCH, title = "Исходное блюдо"), listOf(photo.name))
        }
        lock.start()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }
    @After fun cleanup() { scenario.close() }

    private fun click(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() ||
                runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text)) }.isSuccess
        }
        compose.onAllNodesWithText(text).onFirst().assertIsEnabled().performClick()
    }

    @Test fun `photo review apply and save work through the real editor UI`() {
        click("Питание")
        click("Исходное блюдо")
        click("Определить по фото")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Проверьте оценку").fetchSemanticsNodes().isNotEmpty() }
        runBlocking { assertThat(meals.get(mealId)!!.meal.title).isEqualTo("Исходное блюдо") }
        click("Заменить данные блюда этой оценкой")
        click("Сохранить")
        compose.waitUntil(10_000) {
            // Room resumes the editor on Android's main looper after IO. A DB-only
            // predicate does not pump Robolectric/Compose and can strand that resume.
            compose.waitForIdle()
            runBlocking { meals.get(mealId)?.meal?.nutrition?.proteinGrams == 35.0 }
        }
        runBlocking {
            val meal = meals.get(mealId)!!.meal
            assertThat(meal.title).isEqualTo("Гречка с курицей")
            assertThat(meal.portionGrams).isEqualTo(300)
            assertThat(meal.caloriesEstimate).isEqualTo(450)
        }
    }
}
