package com.nutrix.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.activity.compose.setContent
import com.nutrix.app.core.ui.theme.NutrixTheme
import com.nutrix.app.feature.lock.LockScreen
import com.nutrix.app.feature.food.CameraScreen
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.AfterMealFeeling
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.model.MealNutrition
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.core.model.PortionEaten
import com.nutrix.app.core.model.Satiety
import com.nutrix.app.core.model.SymptomType
import com.nutrix.app.core.model.ThemeMode
import com.nutrix.app.core.security.AppLockManager
import com.nutrix.app.core.time.TimeUtils
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
import org.robolectric.shadows.ShadowDialog
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
    @Inject lateinit var photos: PhotoStorage
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
        settings.setThemeMode(ThemeMode.DARK)
        settings.setWaterGoal(1800)
        data class Fixture(val title: String, val image: String, val grams: Int, val kcal: Int, val protein: Double, val fat: Double, val carbs: Double)
        val dishes = listOf(
            Fixture("Курица с овощами", "chicken-vegetables", 324, 380, 65.801003, 7.762, 9.06),
            Fixture("Паста с говядиной", "beef-pasta", 545, 950, 74.772202, 45.303986, 54.133282),
            Fixture("Лосось с овощами", "salmon-vegetables", 219, 258, 18.995329, 15.969777, 9.881201)
        )
        dishes.forEach { fixture ->
            val file = photos.file(fixture.image + ".jpg")
            requireNotNull(javaClass.getResourceAsStream("/photos/" + fixture.image + ".jpg")).use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        for (back in 29 downTo 0) {
            val day = today.minusDays(back.toLong())
            val wave = sin(back / 4.0)
            listOf(9 to MealType.BREAKFAST, 14 to MealType.LUNCH, 19 to MealType.DINNER).forEachIndexed { i, (hour, type) ->
                if (back == 0 && hour > 14) return@forEachIndexed
                if ((back + i) % 5 == 0 && i == 2) return@forEachIndexed
                val rt = RecordTime.of(day, LocalTime.of(hour, 10 + i * 5), zone)
                val fixture = dishes[(back + i) % dishes.size]
                val id = meals.save(
                    MealEntity(
                        epochMillis = rt.epochMillis, zoneId = rt.zoneId, localDate = rt.epochDay, mealType = type,
                        title = fixture.title, appetite = (2 + wave + i % 2).toInt().coerceIn(0, 5),
                        portion = if ((back + i) % 3 == 0) PortionEaten.P50 else PortionEaten.ALL,
                        satiety = if ((back + i) % 4 == 0) Satiety.QUICK else Satiety.NORMAL,
                        feelings = if ((back + i) % 6 == 0) listOf(AfterMealFeeling.BLOATING) else listOf(AfterMealFeeling.NORMAL),
                        caloriesEstimate = fixture.kcal, portionGrams = fixture.grams,
                        nutrition = MealNutrition(fixture.protein, fixture.fat, fixture.carbs),
                    ),
                    listOf(requireNotNull(photos.duplicate(fixture.image + ".jpg"))),
                )
                if (back == 0) meals.get(id)?.let { meals.favoriteFromMeal(it.meal, it.sortedPhotos.firstOrNull()?.fileName) }
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
        scenario.onActivity { UiScreenshot.capture(it, name) }
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
        compose.onNodeWithContentDescription("Закрыть").performClick()

        compose.onNodeWithText("Календарь").performClick()
        waitForText("Открыть день")
        waitForText("Паста с говядиной")
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

        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf(
            R.string.settings_food_diary to "10-food-diary",
            R.string.settings_weight to "11-weight",
            R.string.settings_symptoms to "12-symptoms",
            R.string.settings_sleep_activity to "13-sleep-activity",
            R.string.settings_integrations to "14-integrations",
            R.string.settings_reminders to "15-reminders",
            R.string.settings_reports to "16-reports",
            R.string.settings_backup to "17-backup",
            R.string.settings_security to "18-security",
            R.string.ai_settings to "19-gemini",
            R.string.settings_about to "20-about",
        ).forEach { (label, name) ->
            val text = context.getString(label)
            compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
            compose.onAllNodesWithText(text).onFirst().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Назад")).fetchSemanticsNodes().isNotEmpty() }
            shot(name)
            compose.onNodeWithContentDescription("Назад").performClick()
            waitForText("Профиль и настройки")
        }
        fun clickText(text: String) {
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() ||
                    runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text)) }.isSuccess
            }
            compose.onAllNodesWithText(text).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        }
        fun back() { compose.onNodeWithContentDescription("Назад").performClick(); compose.waitForIdle() }
        fun fromProfile(label: Int) {
            waitForText("Профиль и настройки")
            val text = context.getString(label)
            compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
            clickText(text)
            compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription("Назад")).fetchSemanticsNodes().isNotEmpty() }
        }

        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Анна"))
        clickText("Анна")
        waitForText(context.getString(R.string.profile_name))
        shot("21-profile-editor")
        back()

        fromProfile(R.string.settings_food_diary)
        clickText("Паста с говядиной")
        waitForText(context.getString(R.string.food_edit_title))
        shot("22-food-photo")
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("food-kcal"))
        shot("23-food-nutrition")
        back()
        compose.onAllNodesWithContentDescription(context.getString(R.string.alignment_meal_actions)).onFirst().performClick()
        waitForText(context.getString(R.string.food_repeat))
        shot("24-meal-actions")
        compose.onNodeWithContentDescription("Закрыть").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.food_favorites_title)).performClick()
        waitForText(context.getString(R.string.food_favorites_title))
        shot("25-favorites")
        back()
        compose.onNodeWithContentDescription(context.getString(R.string.action_search)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Курица")
        shot("26-search")
        back()
        back()

        fromProfile(R.string.settings_water)
        clickText(context.getString(R.string.drink_add_action))
        waitForText(context.getString(R.string.drink_add_title))
        compose.onNodeWithText(context.getString(R.string.action_save)).assertIsDisplayed()
        shot("27-drink-editor")
        back()
        back()

        compose.onNodeWithContentDescription("Быстрое добавление").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("quick-action-NOTE").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("quick-action-NOTE").performScrollTo().performClick()
        waitForText(context.getString(R.string.note_add_title))
        shot("28-note-editor")
        back()

        fromProfile(R.string.settings_symptoms)
        clickText(context.getString(R.string.symptoms_add_action))
        waitForText(context.getString(R.string.symptom_add_title))
        clickText("Тошнота")
        shot("29-symptom-editor")
        back()
        back()

        fromProfile(R.string.settings_sleep_activity)
        clickText(context.getString(R.string.sleep_add))
        waitForText(context.getString(R.string.sleep_add_title))
        shot("30-sleep-editor")
        back()
        back()

        compose.onNodeWithText("Календарь").performClick()
        waitForText("Открыть день")
        clickText("Открыть день")
        shot("31-day")
        back()
        compose.onNodeWithText("Профиль").performClick()
        fromProfile(R.string.settings_weight)
        clickText(context.getString(R.string.weight_add))
        val weightField = hasSetTextAction() and hasText("Вес")
        compose.waitUntil(15_000) { compose.onAllNodes(weightField).fetchSemanticsNodes().size == 1 }
        compose.onNode(weightField).performTextReplacement("0")
        compose.onAllNodesWithText("Сохранить").onFirst().assertIsNotEnabled()
        shot("32-weight-error")
        clickText("Отмена")
        back()

        fromProfile(R.string.settings_reminders)
        compose.onAllNodes(isToggleable())[1].performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.alignment_configure) + ": " + context.getString(R.string.reminder_water)).performClick()
        waitForText(context.getString(R.string.reminder_mode_fixed))
        shot("33-reminder-settings")
        clickText(context.getString(R.string.action_done))
        back()

        waitForText("Профиль и настройки")
        val deleteLabel = context.getString(R.string.settings_delete_all)
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(deleteLabel))
        clickText(deleteLabel)
        waitForText(context.getString(R.string.settings_delete_all_title))
        shot("34-delete-confirmation")
        clickText("Отмена")

        runBlocking { meals.getRange(TimeUtils.todayEpochDay() - 100, TimeUtils.todayEpochDay() + 1).forEach { meals.delete(it.meal.id) } }
        fromProfile(R.string.settings_food_diary)
        waitForText(context.getString(R.string.food_empty_title))
        shot("35-food-empty")

        // These render the actual permission/lock composables without simulating camera hardware.
        scenario.onActivity { it.setContent { NutrixTheme { CameraScreen({}, {}) } } }
        shot("36-camera-access")
        scenario.onActivity { it.setContent { NutrixTheme { androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) { LockScreen(lock, biometricEnabled = false) } } } }
        shot("37-lock")

    }

    @Test
    fun captureLightTheme() {
        runBlocking { settings.setThemeMode(ThemeMode.LIGHT) }
        waitForText("Питание")
        shot("light-01-dashboard")
        compose.onNodeWithText("Календарь").performClick()
        waitForText("Открыть день")
        shot("light-02-calendar")
        compose.onNodeWithText("Аналитика").performClick()
        waitForText("Динамика веса")
        shot("light-03-analytics")
        compose.onNodeWithText("Профиль").performClick()
        waitForText("Профиль и настройки")
        shot("light-04-profile")
    }
}
