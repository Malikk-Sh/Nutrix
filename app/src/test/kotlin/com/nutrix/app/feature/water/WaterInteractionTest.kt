package com.nutrix.app.feature.water

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.MainActivity
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.security.AppLockManager
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.data.repository.DrinkRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** Quick-add feedback, goal state and the swipe-to-delete gesture on the real drinks screen. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h891dp-xxhdpi")
class WaterInteractionTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject lateinit var lock: AppLockManager
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var drinks: DrinkRepository

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext<Context>())
        hilt.inject()
        runBlocking { settings.setWaterGoal(500) }
        lock.start()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() = scenario.close()

    private fun waitForText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun todayDrinks() = runBlocking { drinks.observeDay(TimeUtils.todayEpochDay()).first() }

    // Waiting on UI nodes also idles the main looper, so view-model coroutines can finish.
    private val drinkRows = hasClickAction() and hasText("250 мл")

    private fun waitForRows(count: Int) =
        compose.waitUntil(10_000) { compose.onAllNodes(drinkRows).fetchSemanticsNodes().size == count }

    @Test
    fun `quick add reaches the goal and a left swipe deletes the drink`() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Быстрое добавление")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Быстрое добавление").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("quick-action-WATER")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("quick-action-WATER").performScrollTo().performClick()
        waitForText("Выпито сегодня")
        waitForText("+250 мл") // quick volumes arrive with the settings

        compose.onNodeWithText("+250 мл").performClick()
        waitForText("250 мл")
        // The rising "+250 мл" label is decorative: the button stays the only node with that text.
        compose.onNodeWithText("+250 мл").assertExists()
        assertThat(compose.onAllNodesWithText("Ориентир достигнут").fetchSemanticsNodes()).isEmpty()

        compose.onNodeWithText("+250 мл").performClick()
        waitForText("Ориентир достигнут")
        waitForRows(2)
        assertThat(todayDrinks()).hasSize(2)

        // The animated total can read "250 мл" too, so pick the clickable drink row.
        compose.onAllNodes(drinkRows).onFirst().performTouchInput {
            swipe(start = Offset(centerX, centerY), end = Offset(centerX - width * 3, centerY), durationMillis = 250)
        }
        waitForRows(1)
        assertThat(todayDrinks()).hasSize(1)
        // The deletion is offered for undo like the delete button does.
        waitForText("Отменить")
    }
}
