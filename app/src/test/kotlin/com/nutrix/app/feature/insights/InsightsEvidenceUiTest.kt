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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.MainActivity
import com.nutrix.app.UiScreenshot
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.security.AppLockManager
import com.nutrix.app.core.time.RecordTime
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.domain.insights.InsightTopic
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
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
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "w393dp-h852dp-xhdpi")
class InsightsEvidenceUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var meals: MealRepository
    @Inject lateinit var lock: AppLockManager
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext<Context>())
        hilt.inject()
        runBlocking {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            suspend fun meal(day: LocalDate, h: Int, title: String) {
                val t = RecordTime.of(day, LocalTime.of(h, 0), zone)
                meals.save(MealEntity(epochMillis = t.epochMillis, zoneId = t.zoneId, localDate = t.epochDay, mealType = MealType.OTHER, title = title), emptyList())
            }
            for (back in 1..56) {
                val day = today.minusDays(back.toLong())
                val late = back % 2 == 0
                val dinner = if (late) "Поздний ужин" else "Ужин"
                // Late dinners in both four-week windows; long gaps only in the earlier one.
                if (back > 28) listOf(8 to "Завтрак", 15 to "Обед").forEach { (h, t) -> meal(day, h, t) }
                else listOf(8 to "Завтрак", 12 to "Обед", 16 to "Перекус").forEach { (h, t) -> meal(day, h, t) }
                meal(day, if (late) 22 else 19, dinner)
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

    private fun scrollTo(tag: String) = compose.waitUntil(15_000) {
        runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag(tag)) }.isSuccess
    }

    @Test fun `repeating patterns show their days and topics come first`() {
        waitFor("Аналитика")
        compose.onAllNodesWithText("Аналитика").onFirst().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("open-insights").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("open-insights").performClick()

        // Late dinners happened in both windows: the card says so and shows the earlier weeks.
        scrollTo("insight-late_meals")
        compose.onNodeWithTag("insight-repeats-late_meals").assertExists()
        compose.onNodeWithTag("insight-previous-late_meals").assertExists()
        repeat(10) { Thread.sleep(50); compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
        scenario.onActivity { UiScreenshot.capture(it, "48-insights-history") }
        // Long gaps were only in the earlier weeks, although meals were recorded since.
        scrollTo("insight-faded")
        assertThat(compose.onAllNodesWithTag("insight-long_gaps").fetchSemanticsNodes()).isEmpty()

        // Every counted evening can be opened, the late ones and the ones they are compared with.
        fun openEvidence(button: String) {
            scrollTo(button)
            compose.onNodeWithTag(button).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("insight-evidence").fetchSemanticsNodes().isNotEmpty() }
        }
        openEvidence("insight-days-late_meals")
        compose.onNodeWithTag("insight-evidence-match").assertExists()
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("insight-evidence").performScrollToNode(hasTestTag("insight-evidence-other")) }.isSuccess
        }
        repeat(10) { Thread.sleep(50); compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
        scenario.onActivity { UiScreenshot.capture(it, "47-insights-evidence") }
        // A line opens the meal itself: the latest late dinner.
        compose.onNodeWithTag("insight-evidence").performScrollToNode(hasTestTag("insight-evidence-record"))
        compose.onAllNodesWithTag("insight-evidence-record").onFirst().performClick()
        waitFor("Поздний ужин")
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        // The date opens the whole day.
        openEvidence("insight-days-late_meals")
        val latest = LocalDate.now().minusDays(2)
        compose.onAllNodesWithText(com.nutrix.app.core.time.Formatters.dayMonthShort(latest), substring = true).onFirst().performClick()
        waitFor("Поздний ужин")
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        // A pattern that did not come back shows the earlier weeks it was based on.
        openEvidence("insight-faded-days-long_gaps")
        compose.onNodeWithTag("insight-evidence-match").assertExists()
        // Its days open like any other (the sheet closes with the jump).
        compose.onAllNodesWithTag("insight-evidence-day").onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("insight-evidence").fetchSemanticsNodes().isEmpty() }
        waitFor("Обед")
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        // Choosing a topic is remembered and moves its observations up.
        scrollTo("insight-topics-toggle")
        compose.onNodeWithTag("insight-topics-toggle").performClick()
        waitFor("Режим питания")
        compose.onAllNodesWithText("Режим питания").onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Что мне интересно: Режим питания").fetchSemanticsNodes().isNotEmpty() }
        assertThat(runBlocking { settings.current().insightTopics }).containsExactly(InsightTopic.MEALS.name)
    }
}
