package com.nutrix.app

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.ThemeMode
import com.nutrix.app.core.security.AppLockManager
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
import javax.inject.Inject

/** Small phone, light theme and enlarged system font: every main screen must still open. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "w320dp-h568dp-mdpi", fontScale = 1.3f)
class SmallScreenSmokeTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject lateinit var lock: AppLockManager
    @Inject lateinit var settings: SettingsRepository

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext<Context>())
        hilt.inject()
        runBlocking { settings.setThemeMode(ThemeMode.LIGHT) }
        lock.start()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() = scenario.close()

    private fun waitForText(text: String) =
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() ||
                runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text)) }.isSuccess
        }

    private fun waitForDescription(label: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty() }

    private fun openFromScrollable(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() ||
                runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text)) }.isSuccess
        }
        compose.onAllNodesWithText(text).onFirst().performSemanticsAction(SemanticsActions.OnClick)
    }

    @Test
    fun screensOpenOnSmallDevice() {
        waitForText("Питание")
        listOf("Календарь" to "Открыть день", "Аналитика" to "Аналитика", "Профиль" to "Профиль и настройки", "Главная" to "Питание").forEach { (tab, marker) ->
            compose.onAllNodesWithText(tab).onFirst().performClick()
            waitForText(marker)
        }

        openFromScrollable("Добавить еду")
        waitForText("Новый приём пищи")
        compose.onNodeWithContentDescription("Назад").performClick()

        waitForDescription("Быстрое добавление")
        compose.onNodeWithText("Профиль").performClick()
        listOf("Дневник питания", "Напитки", "Вес", "Самочувствие", "Сон и активность", "О приложении и конфиденциальность").forEach { title ->
            openFromScrollable(title)
            waitForDescription("Назад")
            compose.onNodeWithContentDescription("Назад").performClick()
            waitForText("Профиль и настройки")
        }
    }
}
