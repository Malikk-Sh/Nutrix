package com.nutrix.app.feature.plan

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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.MainActivity
import com.nutrix.app.UiScreenshot
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.PlanSource
import com.nutrix.app.core.security.AppLockManager
import com.nutrix.app.core.time.RecordTime
import com.nutrix.app.data.repository.IntakePlanRepository
import com.nutrix.app.data.repository.WeightRepository
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
import javax.inject.Inject

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "w393dp-h852dp-xhdpi")
class IntakePlanUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var weights: WeightRepository
    @Inject lateinit var plans: IntakePlanRepository
    @Inject lateinit var lock: AppLockManager
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext<Context>())
        hilt.inject()
        runBlocking {
            settings.clearAll()
            settings.setProfile("Анна", 168, null, null)
            weights.saveManual(0, RecordTime.now(), 56.0, null)
        }
        lock.start()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun cleanup() { scenario.close() }

    private fun waitFor(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() ||
            runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text, substring = true)) }.isSuccess
    }

    private fun scrollTo(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() &&
            runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag(tag)) }.isSuccess
    }

    private fun click(text: String) {
        waitFor(text)
        runCatching { compose.onAllNodesWithText(text).onFirst().performScrollTo() }
        compose.onAllNodesWithText(text).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun gone(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty() }

    @Test fun `a maintenance range is shown with its basis, accepted from today and replaced by a specialist's guideline`() {
        click("Профиль")
        click("Ориентиры питания")
        waitFor("Выберите цель")
        click("Поддерживать вес")
        waitFor("Для расчёта не хватает")
        scrollTo("plan-birth-year")
        compose.onNodeWithTag("plan-birth-year").performTextReplacement((LocalDate.now().year - 30).toString())
        click("Женский")
        click("Низкая")
        // 1299 kcal at rest × 1.4–1.6, with the formula's error: never the resting value itself.
        waitFor("950 ккал")
        waitFor("Вероятный диапазон: 1")
        waitFor("Расход в покое по формуле ≈1")
        repeat(10) { Thread.sleep(50); compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
        scenario.onActivity { UiScreenshot.capture(it, "52-intake-plan") }
        scrollTo("plan-accept")
        compose.onNodeWithTag("plan-accept").performSemanticsAction(SemanticsActions.OnClick)
        waitFor("Этот ориентир действует с")
        runBlocking {
            val saved = plans.all().single()
            assertThat(saved.source).isEqualTo(PlanSource.FORMULA)
            assertThat(saved.kcalMin).isEqualTo(1650)
            assertThat(saved.kcalMax).isEqualTo(2300)
            assertThat(saved.basis!!.weightKg).isEqualTo(56.0)
        }

        // Pregnancy: the formula is not offered any more, a specialist's guideline is.
        click("Беременность или кормление грудью")
        waitFor("Во время беременности и кормления грудью")
        assertThat(compose.onAllNodesWithTag("plan-accept").fetchSemanticsNodes()).isEmpty()
        click("Беременность или кормление грудью")
        gone("Во время беременности и кормления грудью")

        scrollTo("manual-kcal-min")
        compose.onNodeWithTag("manual-kcal-min").performTextReplacement("1800")
        compose.onNodeWithTag("manual-kcal-max").performTextReplacement("2000")
        scrollTo("manual-carbs-min")
        compose.onNodeWithTag("manual-protein-min").performTextReplacement("60")
        compose.onNodeWithTag("manual-protein-max").performTextReplacement("90")
        compose.onNodeWithTag("manual-fat-min").performTextReplacement("10")
        compose.onNodeWithTag("manual-fat-max").performTextReplacement("20")
        compose.onNodeWithTag("manual-carbs-min").performTextReplacement("50")
        compose.onNodeWithTag("manual-carbs-max").performTextReplacement("60")
        waitFor("меньше нижней границы калорий")
        compose.onNodeWithTag("manual-carbs-min").performTextReplacement("200")
        compose.onNodeWithTag("manual-carbs-max").performTextReplacement("260")
        compose.onNodeWithTag("manual-fat-min").performTextReplacement("50")
        compose.onNodeWithTag("manual-fat-max").performTextReplacement("70")
        gone("меньше нижней границы калорий")
        scrollTo("manual-save")
        compose.onNodeWithTag("manual-save").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(15_000) { runBlocking { plans.all().single().source == PlanSource.MANUAL } }
        // Saved again today: today's guideline is replaced, there is still one.
        assertThat(runBlocking { plans.all().single().kcalMax }).isEqualTo(2000)

        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("plan-active"))
        waitFor("Ориентир специалиста или ваш")
    }
}
