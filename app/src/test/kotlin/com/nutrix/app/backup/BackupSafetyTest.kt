package com.nutrix.app.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.DataOperationGate
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.core.security.SecretCipher
import com.nutrix.app.database.NutrixDatabase
import com.nutrix.app.database.entity.MealEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupSafetyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clearPreferences() = runBlocking {
        SettingsRepository(context).clearAll()
    }

    @Test fun rejectsForeignSchemaEvenWithValidChecksums() = runBlocking {
        val db = NutrixDatabase.build(context)
        val settings = SettingsRepository(context)
        val alien = File(context.cacheDir, "foreign.db")
        SQLiteDatabase.openOrCreateDatabase(alien, null).use {
            it.execSQL("CREATE TABLE unrelated (id INTEGER PRIMARY KEY)")
            it.version = 1
        }
        val prefs = File(context.cacheDir, "settings.json").apply { writeText("{}") }
        val files = listOf(BackupManager.DB_ENTRY to alien, BackupManager.SETTINGS_ENTRY to prefs)
        val manifest = BackupManifest(appVersion = "1.0.0", dbVersion = 1, createdAt = 0,
            encrypted = false, photoCount = 0, files = files.map { (name, file) -> BackupFileEntry(name, BackupManager.sha256(file), file.length()) })
        val archive = File(context.cacheDir, "foreign.zip")
        ZipFile(archive).use { zip ->
            zip.addStream(Json.encodeToString(BackupManifest.serializer(), manifest).byteInputStream(),
                ZipParameters().apply { fileNameInZip = BackupManager.MANIFEST_ENTRY })
            files.forEach { (name, file) -> zip.addStream(file.inputStream(), ZipParameters().apply { fileNameInZip = name }) }
        }
        val result = BackupManager(context, db, settings, PhotoStorage(context)).check(archive, null)
        assertThat(result).isEqualTo(RestoreCheck.Invalid(RestoreCheck.Reason.DATABASE_CORRUPTED))
        db.close()
    }

    @Test fun rejectsModifiedStagingWithoutTouchingLiveDiary() = runBlocking {
        val db = NutrixDatabase.build(context)
        val settings = SettingsRepository(context)
        val photos = PhotoStorage(context)
        db.mealDao().insert(MealEntity(epochMillis = 0, zoneId = "UTC", localDate = 0, mealType = MealType.LUNCH, title = "original"))
        val backup = BackupManager(context, db, settings, photos)
        val archive = File(context.cacheDir, "valid.zip")
        archive.outputStream().use { backup.createBackup(it, null) }
        val ready = backup.check(archive, null) as RestoreCheck.Ok
        File(ready.dir, BackupManager.SETTINGS_ENTRY).writeText("{}") // Changed after validation.
        val failure = runCatching { backup.restore(ready) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(db.mealDao().allMeals().single().title).isEqualTo("original")
        assertThat(RestoreRecovery.directory(context).exists()).isFalse()
        backup.cleanup()
        db.close()
    }

    @Test fun malformedSettingsDoNotPartiallyReplacePreferencesOrCursors() = runBlocking {
        val settings = SettingsRepository(context)
        settings.setProfile("original", null, null, null)
        settings.setChangesToken(DataSource.HEALTH_CONNECT, "WeightRecord", "keep-on-failure")
        val result = runCatching { settings.importFromBackup(JsonObject(mapOf("b:auto_sync" to JsonPrimitive("invalid")))) }
        assertThat(result.isFailure).isTrue()
        assertThat(settings.current().userName).isEqualTo("original")
        assertThat(settings.changesToken(DataSource.HEALTH_CONNECT, "WeightRecord")).isEqualTo("keep-on-failure")
    }

    @Test fun unreadablePasswordNeverReachesDocumentCreation() = runBlocking {
        val settings = SettingsRepository(context)
        settings.setAutoBackup(true, "content://unregistered/tree/folder", 7, 5)
        settings.setBackupPasswordBlob("unreadable")
        val failure = IllegalStateException("Keystore key unavailable")
        val cipher = object : SecretCipher {
            override fun encrypt(plain: ByteArray): String = error("Not used")
            override fun decrypt(blob: String): ByteArray = throw failure
        }
        val db = NutrixDatabase.build(context)
        val backup = BackupManager(context, db, settings, PhotoStorage(context))
        val result = runCatching { AutoBackupScheduler(context, settings, backup, cipher).runNow() }
        assertThat(result.exceptionOrNull()).isSameInstanceAs(failure)
        assertThat(settings.current().lastAutoBackupMillis).isNull()
        db.close()
    }

    @Test fun restoringEncryptedBackupSettingsWithoutLocalKeyDisablesAutomaticBackups() = runBlocking {
        val settings = SettingsRepository(context)
        settings.importFromBackup(JsonObject(mapOf("b:auto_backup" to JsonPrimitive(true), "b:auto_backup_encrypted" to JsonPrimitive(true))))
        assertThat(settings.current().autoBackupEnabled).isFalse()
        assertThat(settings.autoBackupRequiresPassword()).isTrue()
    }

    @Test fun eraseRemovesExportsCapturesRestoreStagingAndSettings() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val db = NutrixDatabase.build(context)
        val settings = SettingsRepository(context)
        val photos = PhotoStorage(context)
        settings.setProfile("sensitive", null, null, null)
        listOf("exports/report.pdf", "capture/photo.jpg", "nutrix-restore-abandoned/settings.json").forEach { path ->
            File(context.cacheDir, path).apply { parentFile!!.mkdirs(); writeText("sensitive data") }
        }
        photos.file("meal.jpg").writeText("sensitive photo")
        DiaryDataEraser(context, db, photos, settings, DataOperationGate()).erase()
        assertThat(settings.current().userName).isEmpty()
        assertThat(photos.exists("meal.jpg")).isFalse()
        assertThat(File(context.cacheDir, "exports/report.pdf").exists()).isFalse()
        assertThat(File(context.cacheDir, "capture/photo.jpg").exists()).isFalse()
        assertThat(File(context.cacheDir, "nutrix-restore-abandoned").exists()).isFalse()
        db.close()
    }
}
