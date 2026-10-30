package com.nutrix.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.core.model.EntryCategory
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.data.repository.*
import com.nutrix.app.database.NutrixDatabase
import com.nutrix.app.database.entity.ExerciseEntity
import com.nutrix.app.database.entity.SleepEntity
import com.nutrix.app.domain.Metric
import com.nutrix.app.feature.reports.ReportDataLoader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class HealthVisibilityTest {
    @Test fun `manual naps reach timeline markers recent sleep analytics and reports`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(NutrixDatabase.NAME)
        val db = NutrixDatabase.build(context)
        try {
            val settings = SettingsRepository(context).also { it.clearAll() }
            val notifier = DataChangeNotifier()
            val photos = PhotoStorage(context)
            val meals = MealRepository(db, db.mealDao(), photos, notifier)
            val drinks = DrinkRepository(db.drinkDao(), photos, notifier)
            val weights = WeightRepository(db.weightDao(), settings, notifier)
            val symptoms = SymptomRepository(db.symptomDao(), notifier)
            val health = HealthRepository(db.healthDao(), settings, notifier)
            val notes = NoteRepository(db.noteDao(), notifier)
            val timeline = TimelineRepository(meals, drinks, weights, symptoms, health, notes)
            val day = LocalDate.of(2026, 10, 27)
            val zone = ZoneId.of("Europe/Moscow")
            fun millis(hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
            val night = SleepEntity(startMillis = day.minusDays(1).atTime(23, 0).atZone(zone).toInstant().toEpochMilli(), endMillis = millis(7), zoneId = zone.id,
                localDate = day.toEpochDay(), durationMinutes = 480, source = DataSource.SAMSUNG_HEALTH, externalId = "night")
            health.upsertExternalSleep(night)
            val nap = health.saveManualSleep(0, millis(14), millis(15), zone, "Дневной сон")
            val evening = health.saveManualSleep(0, millis(19), millis(20), zone, "Вечерний сон")
            val sessions = health.observeSleepRange(day.toEpochDay(), day.toEpochDay()).first()
            assertThat(sessions.map { it.id }).containsAtLeast(nap, evening)
            assertThat(health.observeRecentSleep(30).first()).hasSize(3)
            assertThat(health.observeLatestSleep().first()?.id).isEqualTo(evening)
            assertThat(health.observeLatestSleepDay().first()).hasSize(3)
            assertThat(timeline.observeDay(day.toEpochDay()).first().filterIsInstance<TimelineItem.WokeUp>()).hasSize(3)
            assertThat(timeline.observeDay(day.toEpochDay()).first().filterIsInstance<TimelineItem.FellAsleep>()).hasSize(2)
            assertThat(timeline.observeDay(day.toEpochDay() - 1).first().filterIsInstance<TimelineItem.FellAsleep>()).hasSize(1)
            assertThat(timeline.observeMarkers(day.toEpochDay() - 1, day.toEpochDay()).first()[day.toEpochDay() - 1]).contains(EntryCategory.SLEEP)
            assertThat(timeline.observeMarkers(day.toEpochDay(), day.toEpochDay()).first()[day.toEpochDay()])
                .contains(EntryCategory.SLEEP)
            val reports = ReportDataLoader(meals, drinks, weights, symptoms, health, notes, settings, DayCheckRepository(db.dayCheckDao(), notifier), IntakePlanRepository(db.intakePlanDao(), notifier)).load(day, day)
            assertThat(reports.sleeps).hasSize(3)
            assertThat(reports.series.getValue(Metric.SLEEP_DURATION).points.single().value).isEqualTo(10.0)

            // Existing raw duplicates disappear immediately on read, without reimporting history.
            val walk = ExerciseEntity(startMillis = millis(12), endMillis = millis(12) + 14 * 60_000,
                zoneId = zone.id, localDate = day.toEpochDay(), exerciseType = 79,
                source = DataSource.SAMSUNG_HEALTH, externalId = "s-walk", kcal = 50.0)
            health.upsertExternalExercise(walk)
            health.upsertExternalExercise(walk.copy(source = DataSource.HEALTH_CONNECT, externalId = "hc-walk", kcal = null))
            assertThat(health.observeExercises(day.toEpochDay(), day.toEpochDay()).first().single().kcal).isEqualTo(50.0)
            assertThat(timeline.observeDay(day.toEpochDay()).first().filterIsInstance<TimelineItem.Exercise>()).hasSize(1)
            assertThat(ReportDataLoader(meals, drinks, weights, symptoms, health, notes, settings, DayCheckRepository(db.dayCheckDao(), notifier), IntakePlanRepository(db.intakePlanDao(), notifier)).load(day, day).exercises).hasSize(1)
            assertThat(db.healthDao().getExerciseRange(day.toEpochDay(), day.toEpochDay())).hasSize(2)
        } finally { db.close() }
    }
}
