package com.nutrix.app.reminders

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performSemanticsAction
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.MainActivity
import com.nutrix.app.UiScreenshot
import com.nutrix.app.core.datastore.ReminderType
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.AfterMealFeeling
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.security.AppLockManager
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.time.RecordTime
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.data.repository.WeightRepository
import com.nutrix.app.database.entity.MealEntity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
class AdaptiveRemindersTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var meals: MealRepository
    @Inject lateinit var weights: WeightRepository
    @Inject lateinit var afterMeal: AfterMealCheckIn
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var lock: AppLockManager

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.systemDefault()

    @Before fun setup() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setWorkerFactory(workerFactory).setExecutor(SynchronousExecutor()).build(),
        )
        runBlocking {
            settings.setQuietHours(false, 22 * 60, 8 * 60)
            val defaults = settings.current().reminders
            settings.setReminders(defaults.map { if (it.type == ReminderType.MEAL || it.type == ReminderType.AFTER_MEAL) it.copy(enabled = true) else it })
        }
    }

    private suspend fun meal(day: LocalDate, time: LocalTime, title: String = "Каша"): Long {
        val t = RecordTime.of(day, time, zone)
        return meals.save(MealEntity(epochMillis = t.epochMillis, zoneId = t.zoneId, localDate = t.epochDay, mealType = MealType.OTHER, title = title), emptyList())
    }

    private fun minutes(m: Int) = Formatters.time(TimeUtils.timeOfMinutes(m))

    @Test fun `reminders follow the learned routine and get quieter when ignored`() {
        runBlocking {
            val today = LocalDate.now()
            for (back in 1..14) {
                meal(today.minusDays(back.toLong()), LocalTime.of(8, 30))
                meal(today.minusDays(back.toLong()), LocalTime.of(13, 0))
            }
        }
        lock.start()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun waitText(text: String) = compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() ||
                runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text)) }.isSuccess
        }
        fun summary(): String = compose.onNodeWithTag("reminder-summary-MEAL").fetchSemanticsNode().config
            .getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString()
        waitText("Профиль")
        compose.onAllNodesWithText("Профиль").onFirst().performClick()
        waitText("Напоминания")
        compose.onAllNodesWithText("Напоминания").onFirst().performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reminder-summary-MEAL").fetchSemanticsNodes().isNotEmpty() }

        // Learned times replace the defaults: 45 minutes after the usual meals.
        val learned = "${minutes(9 * 60 + 15)} · ${minutes(13 * 60 + 45)} · по распорядку"
        compose.waitUntil(10_000) { summary() == learned }

        // Three reminders in a row left unanswered: once a day only, until the user brings it back.
        runBlocking { repeat(3) { settings.recordReminderIgnored(ReminderType.MEAL) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reminder-quieted-MEAL").fetchSemanticsNodes().isNotEmpty() }
        assertThat(summary()).isEqualTo("${minutes(9 * 60 + 15)} · по распорядку")
        repeat(10) { Thread.sleep(50); compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
        scenario.onActivity { UiScreenshot.capture(it, "46-reminders-adaptive") }
        compose.onAllNodesWithText("Вернуть обычный режим").onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reminder-quieted-MEAL").fetchSemanticsNodes().isEmpty() }
        assertThat(runBlocking { settings.reminderIgnores.first()[ReminderType.MEAL] }).isEqualTo(0)

        // The editor explains what was learned; switching adaptation off brings the user's own times back.
        compose.onAllNodesWithContentDescription("Настроить: Поесть").onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reminder-routine-MEAL").fetchSemanticsNodes().isNotEmpty() }
        waitText("Обычно вы едите около ${minutes(8 * 60 + 30)}, ${minutes(13 * 60)}")
        compose.onNodeWithTag("reminder-adaptive-MEAL").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reminder-routine-MEAL").fetchSemanticsNodes().isEmpty() }
        assertThat(runBlocking { settings.current().reminders.first { it.type == ReminderType.MEAL }.adaptive }).isFalse()
        waitText("Добавить время")
        scenario.close()
    }

    @Test fun `after-meal question comes once a day and records only an explicit answer`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val nm = context.getSystemService(NotificationManager::class.java)
        val wm = WorkManager.getInstance(context)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        val name = ReminderScheduler.workName(ReminderType.AFTER_MEAL)
        fun runPending() {
            val info = wm.getWorkInfosForUniqueWork(name).get().single { it.state == WorkInfo.State.ENQUEUED }
            driver.setInitialDelayMet(info.id)
            val deadline = System.currentTimeMillis() + 10_000
            while (wm.getWorkInfoById(info.id).get()?.state?.isFinished != true) {
                check(System.currentTimeMillis() < deadline) { "check-in did not finish" }
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
        }
        fun question(): Notification? = shadowOf(nm).allNotifications.firstOrNull {
            it.extras.getString(Notification.EXTRA_TITLE) == "Как прошла еда?"
        }

        val now = LocalTime.now()
        val id = runBlocking { meal(LocalDate.now(), now.minusMinutes(10).takeIf { it.isBefore(now) } ?: now, "Омлет") }
        runBlocking { afterMeal.plan(meals.get(id)!!.meal) }
        runPending()
        val notification = requireNotNull(question()) { "no after-meal question" }
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).contains("Омлет")
        assertThat(notification.actions.map { it.title.toString() }).containsExactly("Нормально", "Отметить", "Не помню").inOrder()
        // Nothing is assumed: the meal stays unrated until the user answers.
        assertThat(runBlocking { meals.get(id)!!.meal.feelings }).isEmpty()

        // A second meal the same day gets no second question.
        nm.cancelAll()
        val second = runBlocking { meal(LocalDate.now(), now, "Суп") }
        runBlocking { afterMeal.plan(meals.get(second)!!.meal) }
        runPending()
        assertThat(question()).isNull()

        // "Normal" from the notification marks only that meal. Robolectric does not route explicit
        // broadcasts to manifest receivers, so the same intent is delivered to a registered one.
        val answer = shadowOf(notification.actions[0].actionIntent).savedIntent
        assertThat(answer.component?.className).isEqualTo(ReminderActionReceiver::class.java.name)
        context.registerReceiver(ReminderActionReceiver(), IntentFilter(answer.action), Context.RECEIVER_NOT_EXPORTED)
        context.sendBroadcast(Intent(answer).setComponent(null))
        val deadline = System.currentTimeMillis() + 10_000
        while (runBlocking { meals.get(id)!!.meal.feelings }.isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "answer was not saved" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertThat(runBlocking { meals.get(id)!!.meal.feelings }).containsExactly(AfterMealFeeling.NORMAL)
        assertThat(runBlocking { meals.get(second)!!.meal.feelings }).isEmpty()
    }

    @Test fun `a quick normal never overwrites a recorded feeling`() {
        runBlocking {
            val id = meal(LocalDate.now(), LocalTime.of(12, 0))
            val meal = meals.get(id)!!.meal
            meals.save(meal.copy(feelings = listOf(AfterMealFeeling.PAIN)), emptyList())
            assertThat(meals.markFeelingNormal(id)).isFalse()
            assertThat(meals.get(id)!!.meal.feelings).containsExactly(AfterMealFeeling.PAIN)
        }
    }
}
