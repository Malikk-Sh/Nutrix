package com.nutrix.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
import com.nutrix.app.core.model.AfterMealFeeling
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.model.PortionEaten
import com.nutrix.app.core.model.Satiety
import com.nutrix.app.core.model.SymptomType
import com.nutrix.app.core.security.AppLockManager
import com.nutrix.app.core.time.RecordTime
import com.nutrix.app.data.repository.DrinkRepository
import com.nutrix.app.data.repository.HealthRepository
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.data.repository.NoteRepository
import com.nutrix.app.data.repository.SymptomRepository
import com.nutrix.app.data.repository.WeightRepository
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.database.entity.SymptomEntity
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
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.sin

/**
 * Renders the real UI with native graphics (also exercising chart drawing code) and stores
 * screenshots in build/screenshots for the README. Uses illustrative sample data.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "w393dp-h852dp-xhdpi")
class ScreenshotTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject lateinit var lock: AppLockManager
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var meals: MealRepository
    @Inject lateinit var drinks: DrinkRepository
    @Inject lateinit var weights: WeightRepository
    @Inject lateinit var symptoms: SymptomRepository
    @Inject lateinit var health: HealthRepository
    @Inject lateinit var notes: NoteRepository

    private lateinit var scenario: ActivityScenario<MainActivity>
    private val outDir = File("build/screenshots").apply { mkdirs() }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext<Context>())
        hilt.inject()
        runBlocking { seed() }
        lock.start()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() = scenario.close()

    private suspend fun seed() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        settings.setProfile("Анна", 168, 56.0, 55.0)
        settings.setWaterGoal(1800)
        val dishes = listOf("Овсянка с ягодами", "Гречка с курицей", "Суп-пюре", "Творог с бананом", "Рис с рыбой", "Омлет")
        for (back in 29 downTo 0) {
            val day = today.minusDays(back.toLong())
            val wave = sin(back / 4.0)
            listOf(9 to MealType.BREAKFAST, 14 to MealType.LUNCH, 19 to MealType.DINNER).forEachIndexed { i, (hour, type) ->
                if (back == 0 && hour > 14) return@forEachIndexed
                if ((back + i) % 5 == 0 && i == 2) return@forEachIndexed
                val rt = RecordTime.of(day, LocalTime.of(hour, 10 + i * 5), zone)
                meals.save(
                    MealEntity(
                        epochMillis = rt.epochMillis, zoneId = rt.zoneId, localDate = rt.epochDay, mealType = type,
                        title = dishes[(back + i) % dishes.size], appetite = (2 + wave + i % 2).toInt().coerceIn(0, 5),
                        portion = if ((back + i) % 3 == 0) PortionEaten.P50 else PortionEaten.ALL,
                        satiety = if ((back + i) % 4 == 0) Satiety.QUICK else Satiety.NORMAL,
                        feelings = if ((back + i) % 6 == 0) listOf(AfterMealFeeling.BLOATING) else listOf(AfterMealFeeling.NORMAL),
                        caloriesEstimate = 250 + (i * 90),
                    ),
                    emptyList(),
                )
            }
            listOf(8 to 250, 11 to 300, 15 to 200, 18 to 250).forEachIndexed { i, (hour, ml) ->
                if (back == 0 && hour > 15) return@forEachIndexed
                drinks.quickAdd(if (i == 1) DrinkType.TEA else DrinkType.WATER, ml + (back % 3) * 50, time = RecordTime.of(day, LocalTime.of(hour, 0), zone))
            }
            if (back % 2 == 0) weights.saveManual(0, RecordTime.of(day, LocalTime.of(8, 0), zone), 56.4 - (29 - back) * 0.04 + wave * 0.2, null)
            val sleepEnd = RecordTime.of(day, LocalTime.of(7, (back * 7) % 50), zone).epochMillis
            health.saveManualSleep(0, sleepEnd - (6.5 + wave) .times(3_600_000).toLong(), sleepEnd, zone, null)
            if (back % 3 == 0) {
                val rt = RecordTime.of(day, LocalTime.of(15, 0), zone)
                symptoms.saveAll(listOf(SymptomEntity(epochMillis = rt.epochMillis, zoneId = rt.zoneId, localDate = rt.epochDay, type = if (back % 2 == 0) SymptomType.NAUSEA else SymptomType.EARLY_SATIETY, intensity = 2 + back % 3, durationMinutes = 20)))
            }
        }
        notes.save(0, RecordTime.of(today, LocalTime.of(10, 30), zone), "Хорошо выспалась, утром прогулка")
    }

    private fun waitForText(text: String) =
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /** Lets background work (Room queries, Vico model transactions on Dispatchers.Default) and animations finish. */
    private fun settle() = repeat(15) {
        Thread.sleep(100)
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        settle()
        scenario.onActivity { activity ->
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun captureMainScreens() {
        waitForText("Питание")
        shot("01-dashboard")

        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Сегодня"))
        shot("02-dashboard-timeline")
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Питание"))

        compose.onAllNodesWithText("Добавить еду").onFirst().performSemanticsAction(SemanticsActions.OnClick)
        waitForText("Новый приём пищи")
        shot("03-food-editor")
        compose.onNodeWithContentDescription("Назад").performClick()

        compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription("Быстрое добавление")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Быстрое добавление").performClick()
        compose.waitForIdle()
        shot("04-quick-add")
        compose.onNodeWithContentDescription("Быстрое добавление").performClick()

        compose.onNodeWithText("Календарь").performClick()
        waitForText("Открыть день")
        waitForText("Гречка с курицей")
        shot("05-calendar")

        compose.onNodeWithText("Аналитика").performClick()
        waitForText("Динамика веса")
        shot("06-analytics")
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Сравнения"))
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Аппетит и продолжительность сна"))
        shot("07-correlations")

        compose.onNodeWithText("Главная").performClick()
        compose.onAllNodesWithText("Вода").onFirst().performSemanticsAction(SemanticsActions.OnClick)
        waitForText("Выпито сегодня")
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Сегодня ещё нет записей").fetchSemanticsNodes().isEmpty() }
        shot("08-water")
        compose.onNodeWithContentDescription("Назад").performClick()

        compose.onNodeWithText("Профиль").performClick()
        waitForText("Профиль и настройки")
        shot("09-profile")
    }
}
