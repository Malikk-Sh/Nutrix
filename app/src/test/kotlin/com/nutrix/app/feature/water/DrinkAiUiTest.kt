package com.nutrix.app.feature.water

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.MainActivity
import com.nutrix.app.UiScreenshot
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.core.security.AppLockManager
import com.nutrix.app.data.repository.DrinkRepository
import com.nutrix.app.feature.ai.DrinkAiBindings
import com.nutrix.app.feature.ai.DrinkEstimate
import com.nutrix.app.feature.ai.DrinkPhotoAnalyzer
import com.nutrix.app.feature.ai.DrinkPhotoResult
import com.nutrix.app.domain.intake
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@HiltAndroidTest
@UninstallModules(DrinkAiBindings::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "w393dp-h852dp-xhdpi")
class DrinkAiUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val calls = AtomicInteger()

    /** The drink prompt's answer for a café latte; the photo itself never leaves the test. */
    @BindValue @JvmField val analyzer: DrinkPhotoAnalyzer = DrinkPhotoAnalyzer { file, _ ->
        calls.incrementAndGet()
        DrinkPhotoResult(DrinkEstimate(true, "Латте", "COFFEE", 350.0, 175.0, 9.0, 8.5, 14.0, listOf("молоко 2,5 %"), "Объём стакана оценён по виду"),
            "gemini-test", file)
    }

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var drinks: DrinkRepository
    @Inject lateinit var photos: PhotoStorage
    @Inject lateinit var lock: AppLockManager
    private lateinit var scenario: ActivityScenario<MainActivity>
    private var drinkId = 0L

    @Before fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext<Context>())
        hilt.inject()
        runBlocking {
            settings.clearAll()
            settings.setGeminiKeyBlob("fixture-analyzer-does-not-read-key")
            val photo = photos.file("latte-ui.jpg")
            requireNotNull(javaClass.getResourceAsStream("/photos/chicken-vegetables.jpg")).use { input -> photo.outputStream().use { input.copyTo(it) } }
            // Saved before drinks had energy: its value is unknown, so the estimate is applied only by choice.
            val id = drinks.quickAdd(DrinkType.OTHER, 250, name = "Из кафе", nutrition = null)
            drinks.save(drinks.get(id)!!.copy(photoFile = photo.name))
            drinkId = id
        }
        lock.start()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun cleanup() { scenario.close() }

    private fun waitFor(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() ||
            runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text, substring = true)) }.isSuccess
    }

    private fun tap(tag: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() ||
                runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag(tag)) }.isSuccess
        }
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun click(text: String) {
        waitFor(text)
        runCatching { compose.onAllNodesWithText(text).onFirst().performScrollTo() }
        compose.onAllNodesWithText(text).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    @Test fun `a drink photo is recognised with the drink prompt and replaces values only when applied`() {
        click("Вода")
        click("Из кафе")
        tap("drink-ai-action")
        waitFor("Латте (Кофе)")
        waitFor("Добавки: молоко 2,5 %")
        assertThat(calls.get()).isEqualTo(1)
        // The drink had values already: nothing changes until the estimate is applied.
        assertThat(compose.onAllNodesWithTag("drink-energy-source").fetchSemanticsNodes()).isEmpty()
        tap("drink-ai-apply")
        waitFor("Оценка Gemini по фото (gemini-test)")
        waitFor("≈175 ккал в 350 мл")
        repeat(10) { Thread.sleep(50); compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
        scenario.onActivity { UiScreenshot.capture(it, "53-drink-ai") }
        click("Сохранить")
        compose.waitUntil(15_000) { runBlocking { drinks.get(drinkId)?.nutrition != null } }
        val saved = runBlocking { drinks.get(drinkId)!! }
        assertThat(saved.drinkType).isEqualTo(DrinkType.COFFEE)
        assertThat(saved.volumeMl).isEqualTo(350)
        assertThat(saved.nutrition!!.estimateModel).isEqualTo("gemini-test")
        assertThat(saved.intake.kcal).isWithin(0.5).of(175.0)
        assertThat(saved.intake.protein).isWithin(0.2).of(9.0)
    }
}
