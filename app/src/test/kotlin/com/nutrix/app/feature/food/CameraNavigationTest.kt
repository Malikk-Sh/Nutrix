package com.nutrix.app.feature.food

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.UiScreenshot
import com.nutrix.app.MainActivity
import com.nutrix.app.R
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.core.security.AppLockManager
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.core.ui.theme.NutrixTheme
import com.nutrix.app.data.repository.DrinkRepository
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.feature.ai.*
import com.nutrix.app.navigation.AppActions
import com.nutrix.app.navigation.NutrixNavHost
import dagger.hilt.android.testing.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import javax.inject.Inject

/** Real navigation/editor/Room flow. Only the hardware shutter and remote Gemini are replaced. */
@HiltAndroidTest
@UninstallModules(FoodAiBindings::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "w393dp-h852dp-xhdpi")
class CameraNavigationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @Inject lateinit var photos: PhotoStorage
    @Inject lateinit var meals: MealRepository
    @Inject lateinit var drinks: DrinkRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var lock: AppLockManager
    @Volatile private var analyzedPhoto: String? = null
    @BindValue @JvmField val analyzer = FoodPhotoAnalyzer { file, _ ->
        // Exercise the real stored-JPEG upload preparation as well as result delivery.
        val bytes = photos.prepareForAi(file)
        assertThat(bytes.size).isGreaterThan(100)
        assertThat(bytes.take(2)).containsExactly(0xff.toByte(), 0xd8.toByte()).inOrder()
        analyzedPhoto = file
        FoodPhotoResult(FoodEstimate(true, "Гречка с курицей", emptyList(), 300.0, 450.0, 35.0, 10.0, 50.0, ""), GeminiProtocol.DEFAULT_MODEL, file)
    }
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var capturedPhoto: String

    @Before fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext<Context>())
        hilt.inject()
        runBlocking {
            settings.clearAll()
            settings.setGeminiKeyBlob("fixture-analyzer-does-not-read-key")
            val capture = photos.newCaptureFile()
            requireNotNull(javaClass.getResourceAsStream("/photos/chicken-vegetables.jpg")).use { input -> capture.outputStream().use { input.copyTo(it) } }
            capturedPhoto = photos.importFile(capture)
            assertThat(capture.exists()).isFalse()
        }
        lock.start()
    }

    private fun launch(action: String) {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            activity.setContent {
                var pending by remember { mutableStateOf<String?>(action) }
                NutrixTheme {
                    NutrixNavHost(pending, { pending = null }, cameraContent = { captured, back ->
                        Box(Modifier.fillMaxSize()) {
                            Button(onClick = { captured(capturedPhoto) }, modifier = Modifier.align(Alignment.Center).testTag("test-shutter")) { Text("Сделать снимок") }
                            Button(onClick = back, modifier = Modifier.align(Alignment.BottomCenter).testTag("test-cancel-camera")) { Text("Отменить съёмку") }
                        }
                    })
                }
            }
        }
    }

    @After fun cleanup() { if (::scenario.isInitialized) scenario.close() }

    private fun click(text: String) {
        try { compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() ||
                runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text)) }.isSuccess
        } } catch (error: Throwable) {
            val roots = compose.onAllNodes(isRoot())
            java.io.File("build/camera-navigation-${text.hashCode()}.txt").writeText("Waiting for: $text\n" +
                (0 until roots.fetchSemanticsNodes().size).joinToString("\n") { roots[it].printToString(Int.MAX_VALUE) })
            throw AssertionError("While clicking '$text'", error)
        }
        compose.waitUntil(15_000) { runCatching { compose.onAllNodesWithText(text).onFirst().assertIsEnabled() }.isSuccess }
        compose.onAllNodesWithText(text).onFirst().assertIsEnabled().performClick()
    }

    private fun capture() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("test-shutter").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("test-shutter").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("photo:$capturedPhoto").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun analyzeAndSave() {
        click("Определить по фото")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("food-ai-success").fetchSemanticsNodes().isNotEmpty() }
        click("Сохранить")
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Новый приём пищи").fetchSemanticsNodes().isEmpty() &&
                runBlocking { meals.getRange(TimeUtils.todayEpochDay(), TimeUtils.todayEpochDay()).isNotEmpty() }
        }
    }

    @Test fun cameraPhotoReachesGeminiAndSavesOnceAfterReturningAndCancellingAnotherCapture() {
        launch(AppActions.ADD_FOOD_CAMERA)
        capture()
        click("Камера")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("test-cancel-camera").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("test-cancel-camera").performClick()
        compose.onAllNodesWithTag("photo:$capturedPhoto").onFirst().assertExists()
        analyzeAndSave()
        assertThat(analyzedPhoto).isEqualTo(capturedPhoto)
        runBlocking {
            val stored = meals.getRange(TimeUtils.todayEpochDay(), TimeUtils.todayEpochDay()).single()
            assertThat(stored.sortedPhotos.map { it.fileName }).containsExactly(capturedPhoto)
            assertThat(stored.meal.portionGrams).isEqualTo(300)
            assertThat(stored.meal.nutrition!!.proteinGrams).isEqualTo(35.0)
        }
    }

    @Test fun croppedCameraPhotoReplacesOriginalAndIsSentToGemini() {
        launch(AppActions.ADD_FOOD_CAMERA)
        capture()
        compose.onAllNodesWithTag("photo:$capturedPhoto").onFirst().performClick()
        click("Обрезать")
        compose.waitForIdle()
        scenario.onActivity { UiScreenshot.capture(it, "42-crop") }
        click("Готово")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("photo:$capturedPhoto").fetchSemanticsNodes().isEmpty() }
        analyzeAndSave()
        assertThat(analyzedPhoto).isNotEqualTo(capturedPhoto)
        runBlocking {
            val stored = meals.getRange(TimeUtils.todayEpochDay(), TimeUtils.todayEpochDay()).single()
            assertThat(stored.sortedPhotos.map { it.fileName }).containsExactly(analyzedPhoto)
            assertThat(photos.exists(analyzedPhoto)).isTrue()
        }
    }

    @Test fun cameraPhotoIsSavedWithDrink() {
        launch(AppActions.ADD_WATER)
        click("Добавить напиток")
        click("Камера")
        capture()
        // A result redelivered after navigation/recreation must not delete its own file.
        click("Камера")
        capture()
        assertThat(photos.exists(capturedPhoto)).isTrue()
        click("Сохранить")
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Новый напиток").fetchSemanticsNodes().isEmpty() &&
                runBlocking { drinks.getRange(TimeUtils.todayEpochDay(), TimeUtils.todayEpochDay()).isNotEmpty() }
        }
        runBlocking {
            assertThat(drinks.getRange(TimeUtils.todayEpochDay(), TimeUtils.todayEpochDay()).single().photoFile).isEqualTo(capturedPhoto)
        }
    }
}
