package com.nutrix.app.feature.ai

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.UiScreenshot
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
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@HiltAndroidTest
@UninstallModules(FoodAiBindings::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "w393dp-h852dp-xhdpi")
class FoodAiUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val calls = AtomicInteger()
    @Volatile private var pendingAnalysis: CompletableDeferred<Unit>? = null
    @Volatile private var quotaFailures = 0
    @BindValue @JvmField val analyzer: FoodPhotoAnalyzer = FoodPhotoAnalyzer { file, _ ->
        if (calls.incrementAndGet() <= quotaFailures) throw FoodAiException(FoodAiError.QUOTA)
        pendingAnalysis?.await()
        FoodPhotoResult(FoodEstimate(true, "Курица с овощами", listOf(FoodIngredient("Овощи", 180.0), FoodIngredient("Курица", 120.0)),
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
            requireNotNull(javaClass.getResourceAsStream("/photos/chicken-vegetables.jpg")).use { input -> photo.outputStream().use { input.copyTo(it) } }
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
        runCatching { compose.onAllNodesWithText(text).onFirst().performScrollTo() }
        compose.onAllNodesWithText(text).onFirst().assertIsEnabled().performClick()
    }

    private fun shot(name: String) {
        repeat(15) { Thread.sleep(100); compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
        scenario.onActivity { UiScreenshot.capture(it, name) }
    }

    private fun edit(tag: String, value: String) {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performTextReplacement(value)
    }

    @Test fun `analysis shows progress fills fields automatically and saves all manual corrections`() {
        pendingAnalysis = CompletableDeferred()
        click("Питание")
        click("Исходное блюдо")
        click("Определить по фото")
        compose.onNodeWithTag("food-ai-progress").assertExists()
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()
        shot("38-ai-progress")
        pendingAnalysis!!.complete(Unit)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("food-ai-success").fetchSemanticsNodes().isNotEmpty() }
        runBlocking { assertThat(meals.get(mealId)!!.meal.title).isEqualTo("Исходное блюдо") }
        click("Проверить значения")
        shot("39-ai-filled-fields")
        click("Посмотреть исходную оценку")
        shot("40-ai-source")
        click("Закрыть")
        edit("food-grams", "320")
        edit("food-kcal", "470")
        edit("food-protein", "36,5")
        edit("food-fat", "11,5")
        edit("food-carbs", "51,5")
        click("Сохранить")
        compose.waitUntil(10_000) {
            // Room resumes the editor on Android's main looper after IO. A DB-only
            // predicate does not pump Robolectric/Compose and can strand that resume.
            compose.waitForIdle()
            runBlocking { meals.get(mealId)?.meal?.nutrition?.proteinGrams == 36.5 }
        }
        runBlocking {
            val meal = meals.get(mealId)!!.meal
            assertThat(meal.title).isEqualTo("Курица с овощами")
            assertThat(meal.portionGrams).isEqualTo(320)
            assertThat(meal.caloriesEstimate).isEqualTo(470)
            assertThat(meal.nutrition!!.fatGrams).isEqualTo(11.5)
            assertThat(meal.nutrition!!.carbsGrams).isEqualTo(51.5)
        }
    }

    @Test fun `quota failure shows an error and the retry button can refill the same form`() {
        quotaFailures = 1
        click("Питание")
        click("Исходное блюдо")
        click("Определить по фото")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("food-ai-error").fetchSemanticsNodes().isNotEmpty() }
        assertThat(calls.get()).isEqualTo(1)
        shot("41-ai-quota-error")
        compose.onNodeWithTag("food-ai-error").assertTextContains("Лимит запросов или квота исчерпаны", substring = true)
        runBlocking { assertThat(meals.get(mealId)!!.meal.title).isEqualTo("Исходное блюдо") }
        click("Повторить")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("food-ai-success").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("food-ai-error").assertCountEquals(0)
        assertThat(calls.get()).isEqualTo(2)
        click("Сохранить")
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            runBlocking { meals.get(mealId)?.meal?.caloriesEstimate == 450 }
        }
        runBlocking {
            val saved = meals.get(mealId)!!.meal
            assertThat(saved.title).isEqualTo("Курица с овощами")
            assertThat(saved.portionGrams).isEqualTo(300)
            assertThat(saved.nutrition!!.proteinGrams).isEqualTo(35.0)
        }
    }
}
