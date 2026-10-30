package com.nutrix.app.feature.analytics

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.MainActivity
import com.nutrix.app.UiScreenshot
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.DrinkNutrition
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.model.MealNutrition
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.security.AppLockManager
import com.nutrix.app.core.time.RecordTime
import com.nutrix.app.data.repository.DayCheckRepository
import com.nutrix.app.data.repository.IntakePlanRepository
import com.nutrix.app.core.model.PlanSource
import com.nutrix.app.database.entity.IntakePlanEntity
import com.nutrix.app.data.repository.DrinkRepository
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.database.entity.MealEntity
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
class IntakeUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var meals: MealRepository
    @Inject lateinit var drinks: DrinkRepository
    @Inject lateinit var checks: DayCheckRepository
    @Inject lateinit var plans: IntakePlanRepository
    @Inject lateinit var lock: AppLockManager
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val today = LocalDate.now()

    @Before fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext<Context>())
        hilt.inject()
        runBlocking {
            settings.clearAll()
            val zone = ZoneId.systemDefault()
            fun at(back: Long, h: Int) = RecordTime.of(today.minusDays(back), LocalTime.of(h, 0), zone)
            suspend fun meal(back: Long, h: Int, kcal: Int?) = at(back, h).let { t ->
                meals.save(MealEntity(epochMillis = t.epochMillis, zoneId = t.zoneId, localDate = t.epochDay, mealType = MealType.OTHER,
                    title = "Обед", caloriesEstimate = kcal, nutrition = kcal?.let { MealNutrition(proteinGrams = it / 20.0) }), emptyList())
            }
            // Days 1-3: complete (1500 kcal of food + 90 kcal of juice) and confirmed; water never counts.
            for (back in 1L..3L) {
                meal(back, 9, 600); meal(back, 14, 900)
                drinks.quickAdd(DrinkType.JUICE, 200, time = at(back, 15), nutrition = DrinkNutrition(45.0))
                drinks.quickAdd(DrinkType.WATER, 500, time = at(back, 11))
                checks.setConfirmed(today.minusDays(back).toEpochDay(), true)
            }
            // Day 4: confirmed, but the milk has no value, so the day is only "known".
            meal(4, 13, 800)
            drinks.quickAdd(DrinkType.MILK, 250, time = at(4, 16))
            checks.setConfirmed(today.minusDays(4).toEpochDay(), true)
            // Day 5: everything estimated but not confirmed. Today: still running.
            meal(5, 13, 2000)
            meal(0, 9, 400)
            // A guideline from ten days ago: complete days are compared with it, the others are not.
            plans.startToday(IntakePlanEntity(startDay = 0, source = PlanSource.MANUAL, kcalMin = 1500, kcalMax = 1700), today.minusDays(10).toEpochDay())
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
        runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag(tag)) }.isSuccess
    }

    /** Taps the bar of the day [back] days ago in the last-30-days chart (today is the last slot). */
    private fun tapDay(back: Int) {
        compose.onNodeWithTag("intake-bars").performScrollTo().performTouchInput {
            val left = 44.dp.toPx()
            val slot = (width - left) / 30
            click(Offset(left + slot * (29 - back + 0.5f), height / 2f))
        }
        compose.waitForIdle()
    }

    @Test fun `averages use complete days only and a day shows what it is made of`() {
        waitFor("Аналитика")
        compose.onAllNodesWithText("Аналитика").onFirst().performClick()
        scrollTo("intake-card")
        // (1500 + 90) on three complete days; the partial, unconfirmed and running days are left out.
        compose.onNodeWithTag("intake-average").assertTextContains("590 ккал", substring = true)
        waitFor("среднее по 3 полным дням из 29 завершённых")
        waitFor("Ориентир: 1")
        waitFor("в диапазоне 3, ниже 0, выше 0")

        tapDay(4)
        scrollTo("intake-day")
        waitFor("Оценено не всё")
        waitFor("Оценено 1 из 2 записей")
        assertThat(compose.onAllNodesWithText("не указано").fetchSemanticsNodes()).isNotEmpty()

        tapDay(1)
        scrollTo("intake-day")
        waitFor("Полный день")
        waitFor("Еда ≈1")
        repeat(10) { Thread.sleep(50); compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
        scenario.onActivity { UiScreenshot.capture(it, "50-intake-chart") }

        // Taking the confirmation back removes the day from the average at once.
        scrollTo("intake-confirm")
        compose.onNodeWithTag("intake-confirm").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("среднее по 2 полным дням", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("среднее по 2 полным дням", substring = true)) }.isSuccess
        }
        assertThat(runBlocking { checks.getRange(today.minusDays(1).toEpochDay(), today.minusDays(1).toEpochDay()) }).isEmpty()

        // A drink of the day opens its own editor, where its energy is entered.
        tapDay(2)
        scrollTo("intake-day")
        compose.onAllNodesWithText("Сок", substring = true).onFirst().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("drink-energy").fetchSemanticsNodes().isNotEmpty() ||
            runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("drink-energy")) }.isSuccess }
        waitFor("≈90 ккал в 200 мл")
        repeat(10) { Thread.sleep(50); compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
        scenario.onActivity { UiScreenshot.capture(it, "51-drink-energy") }
    }
}
