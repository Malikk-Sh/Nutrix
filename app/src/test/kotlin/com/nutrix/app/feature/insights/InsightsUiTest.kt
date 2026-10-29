package com.nutrix.app.feature.insights

import android.content.Context
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.MainActivity
import com.nutrix.app.UiScreenshot
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.security.AppLockManager
import com.nutrix.app.core.time.RecordTime
import com.nutrix.app.data.repository.DrinkRepository
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.database.entity.MealEntity
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

@HiltAndroidTest
@UninstallModules(InsightReviewBindings::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "w393dp-h852dp-xhdpi")
class InsightsUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Volatile private var sent: JsonObject? = null
    @Volatile private var requests = 0
    @BindValue @JvmField val reviewer: InsightReviewer = InsightReviewer { payload, ids ->
        requests++
        sent = payload
        InsightReview(
            summary = "Первый приём пищи сильно плавает, а напитков к обеду мало.",
            points = listOf(ReviewItem("meal_rhythm", "Начало дня меняется почти на 6 часов."), ReviewItem("unknown", "Лишнее")).filter { it.insightId in ids },
            suggestions = listOf(ReviewItem("fluid", "Попробуйте стакан воды до обеда в течение недели.")),
        ) to "gemini-test"
    }

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var meals: MealRepository
    @Inject lateinit var drinks: DrinkRepository
    @Inject lateinit var lock: AppLockManager
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext<Context>())
        hilt.inject()
        runBlocking {
            settings.setProfile("Анна", 168, 56.0, 55.0)
            settings.setWaterGoal(1800)
            settings.setGeminiKeyBlob("fixture-reviewer-does-not-read-key")
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            for (back in 1..28) {
                val day = today.minusDays(back.toLong())
                listOf((if (back % 2 == 0) 7 else 13) to 0, 20 to 30).forEach { (h, m) ->
                    val t = RecordTime.of(day, LocalTime.of(h, m), zone)
                    meals.save(MealEntity(epochMillis = t.epochMillis, zoneId = t.zoneId, localDate = t.epochDay, mealType = MealType.OTHER,
                        title = "Секретное блюдо", caloriesEstimate = if (h > 18) 900 else 400), emptyList())
                }
                drinks.quickAdd(DrinkType.WATER, 300, time = RecordTime.of(day, LocalTime.of(10, 0), zone))
                drinks.quickAdd(DrinkType.WATER, 900, time = RecordTime.of(day, LocalTime.of(18, 0), zone))
            }
        }
        lock.start()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun cleanup() { scenario.close() }

    private fun waitFor(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() ||
            runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text)) }.isSuccess
    }

    private fun click(text: String) {
        waitFor(text)
        runCatching { compose.onAllNodesWithText(text).onFirst().performScrollTo() }
        compose.onAllNodesWithText(text).onFirst().performClick()
    }

    private fun shot(name: String) {
        repeat(10) { Thread.sleep(50); compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
        scenario.onActivity { UiScreenshot.capture(it, name) }
    }

    @Test fun `observations can be hidden, restored and reviewed with consent`() {
        // The dashboard shows the most relevant observation.
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("insight-teaser").fetchSemanticsNodes().isNotEmpty() ||
            runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("insight-teaser")) }.isSuccess }
        shot("45-dashboard-insight")

        click("Аналитика")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("open-insights").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("open-insights").performClick()
        waitFor("Режим питания")
        waitFor("Напитки")
        shot("43-insights")

        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("insight-meal_rhythm"))
        compose.onAllNodesWithText("Скрыть").onFirst().performClick()
        waitFor("Скрытые наблюдения: 1")
        assertThat(runBlocking { settings.current().hiddenInsights }).containsExactly("meal_rhythm")
        click("Скрытые наблюдения: 1")
        click("Вернуть")
        // Waiting on UI nodes also idles the main looper, so the view model's write can finish.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Скрытые наблюдения: 1").fetchSemanticsNodes().isEmpty() }
        assertThat(runBlocking { settings.current().hiddenInsights }).isEmpty()

        // The offline overview is always there; the cloud review waits for a billed project (free-tier terms).
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("insight-overview"))
        waitFor("Обзор недели")
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("insight-review"))
        compose.onNodeWithTag("insight-review-unpaid").assertExists()
        assertThat(compose.onAllNodesWithText("Разобрать").fetchSemanticsNodes()).isEmpty()
        click("Мой ключ — из платного проекта")
        click("Оплата подключена")

        // Consent shows exactly what would be sent; diary text never appears in it.
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("insight-review"))
        click("Разобрать")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("insight-review-preview").fetchSemanticsNodes().isNotEmpty() }
        val preview = compose.onNodeWithTag("insight-review-preview").fetchSemanticsNode().config
            .getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.joinToString()
        assertThat(preview).contains("meal_rhythm")
        assertThat(preview).doesNotContain("Секрет")
        assertThat(preview).doesNotContain("Анна")
        assertThat(requests).isEqualTo(0)
        click("Согласен, отправить")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("insight-review-text").fetchSemanticsNodes().isNotEmpty() }
        waitFor("Начало дня меняется почти на 6 часов.")
        assertThat(requests).isEqualTo(1)
        assertThat(sent.toString()).doesNotContain("Секрет")
        assertThat(runBlocking { settings.current().aiReviewConsent }).isTrue()
        assertThat(runBlocking { settings.aiReviewCache.first() }).isNotNull()
        // The same facts are not sent again: the action disappears until the data changes.
        assertThat(compose.onAllNodesWithTag("insight-review-action").fetchSemanticsNodes()).isEmpty()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("insight-review"))
        shot("44-insights-review")

        click("Отозвать согласие")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("insight-review-text").fetchSemanticsNodes().isEmpty() }
        assertThat(runBlocking { settings.aiReviewCache.first() }).isNull()
        assertThat(runBlocking { settings.current().aiReviewConsent }).isFalse()

        // Saying the key is not from a paid project closes the cloud path again.
        click("Ключ не из платного проекта")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("insight-review-unpaid").fetchSemanticsNodes().isNotEmpty() }
        assertThat(runBlocking { settings.current().aiPaidProject }).isFalse()
        assertThat(requests).isEqualTo(1)
    }
}
