package com.nutrix.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.backup.BackupManager
import com.nutrix.app.backup.RestoreCheck
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.core.time.RecordTime
import com.nutrix.app.data.repository.DataChangeNotifier
import com.nutrix.app.data.repository.DrinkRepository
import com.nutrix.app.data.repository.HealthRepository
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.data.repository.NoteRepository
import com.nutrix.app.data.repository.SymptomRepository
import com.nutrix.app.data.repository.TimelineItem
import com.nutrix.app.data.repository.TimelineRepository
import com.nutrix.app.data.repository.WeightRepository
import com.nutrix.app.database.NutrixDatabase
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.database.entity.WeightEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Repositories, timeline and backup against a real (Robolectric) SQLite database and DataStore.
 * Kept in one class so the process-wide DataStore instance is created once.
 */
@RunWith(AndroidJUnit4::class)
class RepositoryAndBackupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun repositoriesTimelineAndBackupRoundTrip() = runBlocking {
        context.deleteDatabase(NutrixDatabase.NAME)
        var db = NutrixDatabase.build(context)
        val photos = PhotoStorage(context)
        val settings = SettingsRepository(context)
        val notifier = DataChangeNotifier()
        val meals = MealRepository(db, db.mealDao(), photos, notifier)
        val weights = WeightRepository(db.weightDao(), settings, notifier)
        val drinks = DrinkRepository(db.drinkDao(), photos, notifier)
        val symptoms = SymptomRepository(db.symptomDao(), notifier)
        val health = HealthRepository(db.healthDao(), settings, notifier)
        val notes = NoteRepository(db.noteDao(), notifier)
        val timeline = TimelineRepository(meals, drinks, weights, symptoms, health, notes)

        // --- meals with photos: editing removes dropped files, delete + undo restores ---
        val f1 = "p1.jpg".also { photos.file(it).writeBytes(byteArrayOf(1, 2, 3)) }
        val f2 = "p2.jpg".also { photos.file(it).writeBytes(byteArrayOf(4, 5, 6)) }
        val zone = ZoneId.systemDefault()
        val day = LocalDate.of(2026, 10, 25)
        val rt = RecordTime.of(day, LocalTime.of(13, 40), zone)
        val mealId = meals.save(MealEntity(epochMillis = rt.epochMillis, zoneId = rt.zoneId, localDate = rt.epochDay, mealType = MealType.LUNCH, title = "Гречка"), listOf(f1, f2))
        val saved = meals.get(mealId)!!
        meals.save(saved.meal.copy(title = "Гречка с курицей"), listOf(f2))
        assertThat(photos.exists(f1)).isFalse()
        assertThat(meals.get(mealId)!!.photos.map { it.fileName }).containsExactly(f2)

        val snapshot = meals.delete(mealId)!!
        assertThat(meals.get(mealId)).isNull()
        meals.restore(snapshot)
        assertThat(meals.get(mealId)!!.meal.title).isEqualTo("Гречка с курицей")
        assertThat(meals.get(mealId)!!.photos).hasSize(1)

        // --- other diaries feed a chronological timeline ---
        drinks.quickAdd(com.nutrix.app.core.model.DrinkType.TEA, 250, time = RecordTime.of(day, LocalTime.of(12, 15), zone))
        weights.saveManual(0, RecordTime.of(day, LocalTime.of(22, 0), zone), 54.8, null)
        symptoms.quickAdd(com.nutrix.app.core.model.SymptomType.NAUSEA, RecordTime.of(day, LocalTime.of(14, 10), zone))
        notes.save(0, RecordTime.of(day, LocalTime.of(9, 0), zone), "Утро")
        health.saveManualSleep(0, RecordTime.of(day.minusDays(1), LocalTime.of(23, 0), zone).epochMillis, RecordTime.of(day, LocalTime.of(7, 30), zone).epochMillis, zone, null)

        val items = timeline.observeDay(day.toEpochDay()).first()
        assertThat(items.map { it::class }).containsExactly(
            TimelineItem.WokeUp::class, TimelineItem.Note::class, TimelineItem.Drink::class, TimelineItem.Meal::class,
            TimelineItem.Symptom::class, TimelineItem.Weight::class,
        ).inOrder()
        val markers = timeline.observeMarkers(day.toEpochDay(), day.toEpochDay()).first()[day.toEpochDay()]!!
        assertThat(markers).hasSize(6)

        // --- imported weights: re-import updates instead of duplicating; display dedupes vs manual ---
        val external = WeightEntity(epochMillis = RecordTime.of(day, LocalTime.of(22, 2), zone).epochMillis, zoneId = zone.id, localDate = day.toEpochDay(), weightKg = 54.8, source = DataSource.HEALTH_CONNECT, externalId = "hc-w-1")
        assertThat(weights.upsertExternal(external)).isTrue()
        assertThat(weights.upsertExternal(external)).isFalse()
        assertThat(db.weightDao().count()).isEqualTo(2)
        assertThat(weights.observeAll().first()).hasSize(1)
        assertThat(weights.observeAll().first().single().source).isEqualTo(DataSource.MANUAL)

        // --- backup: plain and encrypted archives, integrity checks, restore ---
        settings.setProfile("Тест", 170, 56.0, 55.0)
        val backup = BackupManager(context, db, settings, photos)
        val plain = File(context.cacheDir, "plain.zip")
        plain.outputStream().use { backup.createBackup(it, null) }
        val ok = backup.check(plain, null)
        assertThat(ok).isInstanceOf(RestoreCheck.Ok::class.java)
        assertThat((ok as RestoreCheck.Ok).manifest.photoCount).isEqualTo(1)

        val encrypted = File(context.cacheDir, "secret.zip")
        encrypted.outputStream().use { backup.createBackup(it, "s3cret!".toCharArray()) }
        assertThat(backup.check(encrypted, null)).isEqualTo(RestoreCheck.NeedsPassword)
        assertThat(backup.check(encrypted, "wrong".toCharArray())).isEqualTo(RestoreCheck.WrongPassword)
        assertThat(backup.check(encrypted, "s3cret!".toCharArray())).isInstanceOf(RestoreCheck.Ok::class.java)

        // Tamper with one file: checksum verification must reject the archive.
        val tampered = File(context.cacheDir, "tampered.zip")
        plain.copyTo(tampered, overwrite = true)
        ZipFile(tampered).use { zip ->
            zip.removeFile(BackupManager.SETTINGS_ENTRY)
            zip.addStream("{}".byteInputStream(), ZipParameters().apply { fileNameInZip = BackupManager.SETTINGS_ENTRY })
        }
        assertThat(backup.check(tampered, null)).isEqualTo(RestoreCheck.Invalid(RestoreCheck.Reason.CHECKSUM_MISMATCH))

        // Not a backup at all.
        val junk = File(context.cacheDir, "junk.zip").apply { writeText("hello") }
        assertThat(backup.check(junk, null)).isInstanceOf(RestoreCheck.Invalid::class.java)

        // Change data, then restore the plain backup and verify the original state returns.
        meals.delete(mealId)
        settings.setProfile("Другое имя", null, null, null)
        val again = backup.check(plain, null) as RestoreCheck.Ok
        backup.restore(again)
        db = NutrixDatabase.build(context)
        val restoredMeals = db.mealDao().getRange(day.toEpochDay(), day.toEpochDay())
        assertThat(restoredMeals.single().meal.title).isEqualTo("Гречка с курицей")
        assertThat(photos.exists(restoredMeals.single().photos.single().fileName)).isTrue()
        assertThat(settings.current().userName).isEqualTo("Тест")
        assertThat(settings.current().heightCm).isEqualTo(170)
        db.close()
    }
}
