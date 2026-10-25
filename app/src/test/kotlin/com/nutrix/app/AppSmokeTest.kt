package com.nutrix.app

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.hasScrollAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.nutrix.app.core.security.AppLockManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * End-to-end smoke test of the real app (Hilt graph, Room, DataStore, navigation, Compose UI)
 * running on Robolectric: launches MainActivity and exercises the main flows.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h891dp-xxhdpi")
class AppSmokeTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject lateinit var lock: AppLockManager

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        hilt.inject()
        lock.start()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        runCatching {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
        }.onFailure {
            dumpTree("waiting for '$text'")
            throw it
        }
    }

    private fun dumpTree(reason: String) {
        val roots = compose.onAllNodes(androidx.compose.ui.test.isRoot())
        val text = (0 until roots.fetchSemanticsNodes().size).joinToString("\n\n") { roots[it].printToString(Int.MAX_VALUE) }
        java.io.File("build/smoke-tree.txt").writeText("$reason\n$text")
    }

    /** Waits until [text] can be found, scrolling the main scroll container if needed. */
    private fun scrollToText(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() ||
                runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text)) }.isSuccess
        }
    }

    private fun clickText(text: String) {
        waitForText(text)
        compose.onNodeWithText(text).performClick()
    }

    private fun waitForDescription(label: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun clickDescription(label: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(label).performClick()
    }

    @Test
    fun mainFlowsWork() {
        // Dashboard
        waitForText("Питание")
        compose.onAllNodesWithText("Вода").onFirst().assertExists()

        // Bottom navigation through every top-level screen
        clickText("Календарь")
        waitForText("Открыть день")
        clickText("Аналитика")
        waitForText("Дней с записями", substring = true)
        clickText("Профиль")
        waitForText("Профиль и настройки")
        clickText("Главная")
        waitForDescription("Быстрое добавление")

        // Quick add -> water screen -> one-tap volume
        clickDescription("Быстрое добавление")
        compose.onAllNodesWithText("Вода").onFirst().assertExists()
        clickDescription("Вода")
        waitForText("Выпито сегодня")
        clickText("+250 мл")
        waitForText("250 мл")
        clickDescription("Назад")
        waitForDescription("Быстрое добавление")

        // Add a meal without a photo
        clickText("Добавить еду")
        waitForText("Новый приём пищи")
        compose.onNode(hasSetTextAction() and hasText("Название блюда")).performTextInput("Гречка с курицей")
        clickText("Сохранить")
        waitForDescription("Быстрое добавление")
        scrollToText("Гречка с курицей")

        // Weight from the dashboard card
        clickDescription("Записать вес")
        waitForText("Записать вес")
        compose.onNode(hasSetTextAction() and hasText("Вес")).performTextReplacement("55,2")
        compose.onAllNodesWithText("Сохранить").onFirst().performClick()
        waitForText("55,2 кг", substring = true)
        clickDescription("Назад")

        // Note via quick add
        waitForDescription("Быстрое добавление")
        clickDescription("Быстрое добавление")
        clickDescription("Заметка")
        waitForText("Новая заметка")
        compose.onNode(hasSetTextAction()).performTextInput("Хорошо выспался")
        clickText("Сохранить")
        waitForDescription("Быстрое добавление")
        scrollToText("Хорошо выспался")

        // Symptom one-tap entry
        clickText("Профиль")
        waitForText("Профиль и настройки")
        scrollToText("Самочувствие")
        compose.onNodeWithText("Самочувствие").performSemanticsAction(SemanticsActions.OnClick)
        waitForText("Отметить одним касанием")
        clickText("Тошнота")
        waitForText("Отмечено: Тошнота")

        // Calendar shows today's entries
        clickDescription("Назад")
        clickText("Календарь")
        waitForText("Открыть день")
        scrollToText("Гречка с курицей")

        // Search finds the meal
        clickText("Главная")
        waitForDescription("Быстрое добавление")
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(0)
        clickDescription("Поиск")
        compose.onNode(hasSetTextAction()).performTextInput("греч")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Блюда").fetchSemanticsNodes().isNotEmpty() }

        // Settings screens open without crashing
        clickDescription("Назад")
        clickText("Профиль")
        listOf("Напоминания", "Health Connect и Samsung Health", "Отчёты и экспорт", "Резервное копирование", "Защита приложения").forEach { title ->
            scrollToText(title)
            // The row may sit under the translucent bottom bar after scrolling; invoke its click action directly.
            compose.onNodeWithText(title).performSemanticsAction(SemanticsActions.OnClick)
            runCatching {
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Назад").fetchSemanticsNodes().isNotEmpty() }
            }.onFailure {
                java.io.File("build/smoke-tree.txt").writeText(compose.onAllNodes(androidx.compose.ui.test.isRoot()).onFirst().printToString(Int.MAX_VALUE))
                throw it
            }
            clickDescription("Назад")
            waitForText("Профиль и настройки")
        }
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithContentDescription(label: String) =
    onAllNodes(hasContentDescription(label))
