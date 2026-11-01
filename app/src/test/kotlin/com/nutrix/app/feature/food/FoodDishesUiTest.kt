package com.nutrix.app.feature.food

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
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextContains
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.MainActivity
import com.nutrix.app.UiScreenshot
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.security.AppLockManager
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.data.repository.DrinkRepository
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.domain.intake
import kotlinx.coroutines.flow.first
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
import java.time.ZoneId
import javax.inject.Inject

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "w393dp-h852dp-xhdpi")
class FoodDishesUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var meals: MealRepository
    @Inject lateinit var drinks: DrinkRepository
    @Inject lateinit var lock: AppLockManager
    private lateinit var scenario: ActivityScenario<MainActivity>
    private var mealId = 0L

    @Before fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext<Context>())
        hilt.inject()
        runBlocking {
            settings.clearAll()
            mealId = meals.save(MealEntity(epochMillis = System.currentTimeMillis(), localDate = TimeUtils.todayEpochDay(),
                zoneId = ZoneId.systemDefault().id, mealType = MealType.LUNCH, title = "Обед"), emptyList())
        }
        lock.start()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun cleanup() { scenario.close() }

    /** Composed nodes are acted on directly; the list scrolls only to bring an item into composition. */
    private fun scrollTo(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() ||
            runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag(tag)) }.isSuccess
    }

    private fun click(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() ||
                runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text)) }.isSuccess
        }
        runCatching { compose.onAllNodesWithText(text).onFirst().performScrollTo() }
        compose.onAllNodesWithText(text).onFirst().performClick()
    }

    /** The bottom save bar can cover a scrolled-to button, so its click action is invoked directly. */
    private fun tap(tag: String) {
        scrollTo(tag)
        // Buttons stay disabled until the editor has loaded the meal.
        compose.waitUntil(10_000) { SemanticsProperties.Disabled !in compose.onNodeWithTag(tag).fetchSemanticsNode().config }
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun edit(tag: String, value: String) {
        scrollTo(tag)
        compose.onNodeWithTag(tag).performTextReplacement(value)
    }

    @Test fun `dishes of one meal add up and a missing value is shown as a partial total`() {
        click("Питание")
        click("Обед")
        tap("food-split")
        scrollTo("food-dishes")
        edit("dish-name-0", "Суп")
        edit("dish-kcal-0", "250")
        tap("dish-add")
        edit("dish-name-1", "Второе")
        edit("dish-grams-1", "250")
        edit("dish-kcal-1", "400")
        tap("dish-add")
        edit("dish-name-2", "Компот")
        scrollTo("dish-totals")
        compose.onNodeWithTag("dish-totals-value").assertTextContains("≈650 ккал", substring = true)
        assertThat(compose.onAllNodesWithText("Калории: известно для 2 из 3 блюд", substring = true).fetchSemanticsNodes()).isNotEmpty()
        repeat(10) { Thread.sleep(50); compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
        scenario.onActivity { UiScreenshot.capture(it, "49-meal-dishes") }
        click("Сохранить")
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            runBlocking { meals.get(mealId)?.meal?.dishes?.size == 3 }
        }
        runBlocking {
            val meal = meals.get(mealId)!!.meal
            assertThat(meal.dishes!!.map { it.name }).containsExactly("Суп", "Второе", "Компот").inOrder()
            assertThat(meal.caloriesEstimate).isEqualTo(650)
            assertThat(meal.nutrition!!.kcalKnown).isEqualTo(2)
        }
        // The diary says the total is partial instead of presenting it as the meal's calories.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("оценены 2 из 3", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun `a dish that is a drink is saved as a drink linked to the meal and counted once`() {
        click("Питание")
        click("Обед")
        tap("food-split")
        edit("dish-name-0", "Суп")
        edit("dish-grams-0", "300")
        edit("dish-kcal-0", "250")
        tap("dish-add")
        edit("dish-name-1", "Морс")
        edit("dish-grams-1", "200")
        edit("dish-kcal-1", "80")
        edit("dish-carbs-1", "20")
        tap("dish-as-drink-1")
        // The type is suggested from the name, the volume from the served mass; the energy goes with it.
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("as-drink-energy").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("as-drink-energy").assertTextContains("≈80 ккал", substring = true)
        compose.onNodeWithTag("as-drink-confirm").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        scrollTo("meal-pending-drink-0")
        assertThat(compose.onAllNodesWithText("сохранится напитком вместе с приёмом", substring = true).fetchSemanticsNodes()).isNotEmpty()
        repeat(10) { Thread.sleep(50); compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
        scenario.onActivity { UiScreenshot.capture(it, "54-dish-as-drink") }
        click("Сохранить")
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            runBlocking { drinks.observeForMeal(mealId).first().isNotEmpty() }
        }
        runBlocking {
            val meal = meals.get(mealId)!!.meal
            assertThat(meal.dishes!!.map { it.name }).containsExactly("Суп")
            assertThat(meal.caloriesEstimate).isEqualTo(250)
            val drink = drinks.observeForMeal(mealId).first().single()
            assertThat(drink.drinkType).isEqualTo(DrinkType.JUICE)
            assertThat(drink.name).isEqualTo("Морс")
            assertThat(drink.volumeMl).isEqualTo(200)
            assertThat(drink.intake.kcal).isWithin(1e-9).of(80.0)
            assertThat(drink.intake.carbs).isWithin(1e-9).of(20.0)
        }
    }
}
