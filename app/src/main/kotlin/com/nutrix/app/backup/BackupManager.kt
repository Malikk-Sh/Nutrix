package com.nutrix.app.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.nutrix.app.BuildConfig
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.database.NutrixDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BackupManifest(
    val format: Int = FORMAT_VERSION,
    val app: String = "NUTRIX",
    val appVersion: String,
    val dbVersion: Int,
    val createdAt: Long,
    val encrypted: Boolean,
    val photoCount: Int,
    val files: List<BackupFileEntry>,
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

@Serializable
data class BackupFileEntry(val path: String, val sha256: String, val size: Long)

sealed interface RestoreCheck {
    data class Ok(val manifest: BackupManifest, internal val dir: File) : RestoreCheck
    data object NeedsPassword : RestoreCheck
    data object WrongPassword : RestoreCheck
    data class Invalid(val reason: Reason) : RestoreCheck

    enum class Reason { NOT_A_BACKUP, CHECKSUM_MISMATCH, DATABASE_CORRUPTED, NEWER_VERSION, IO_ERROR }
}

/**
 * Full backup: database, photos, settings (without secrets) and templates (which live in the
 * database). Optional AES-256 ZIP encryption with a user password; the password is never stored
 * unless the user enables encrypted automatic backups (then it is Keystore-encrypted).
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: NutrixDatabase,
    private val settings: SettingsRepository,
    private val photos: PhotoStorage,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    suspend fun createBackup(output: OutputStream, password: CharArray?): BackupManifest = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "backup_staging").apply { deleteRecursively(); mkdirs() }
        try {
            // Flush WAL into the main file so a single-file copy is consistent.
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
            val dbFile = context.getDatabasePath(NutrixDatabase.NAME)
            val stagedDb = File(staging, DB_ENTRY)
            dbFile.copyTo(stagedDb, overwrite = true)
            val stagedSettings = File(staging, SETTINGS_ENTRY)
            stagedSettings.writeText(json.encodeToString(JsonObject.serializer(), settings.exportForBackup()))

            val photoFiles = photos.directory.listFiles()?.filter { it.isFile }.orEmpty()
            val entries = mutableListOf<Pair<String, File>>()
            entries += DB_ENTRY to stagedDb
            entries += SETTINGS_ENTRY to stagedSettings
            photoFiles.forEach { entries += "$PHOTOS_DIR/${it.name}" to it }

            val manifest = BackupManifest(
                appVersion = BuildConfig.VERSION_NAME,
                dbVersion = db.openHelper.readableDatabase.version,
                createdAt = System.currentTimeMillis(),
                encrypted = password != null && password.isNotEmpty(),
                photoCount = photoFiles.size,
                files = entries.map { (path, file) -> BackupFileEntry(path, sha256(file), file.length()) },
            )
            val encrypt = manifest.encrypted
            ZipOutputStream(output, if (encrypt) password else null).use { zip ->
                fun put(name: String, write: (OutputStream) -> Unit) {
                    val params = ZipParameters().apply {
                        fileNameInZip = name
                        compressionLevel = CompressionLevel.NORMAL
                        if (encrypt) {
                            isEncryptFiles = true
                            encryptionMethod = EncryptionMethod.AES
                            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                        }
                    }
                    zip.putNextEntry(params)
                    write(zip)
                    zip.closeEntry()
                }
                put(MANIFEST_ENTRY) { it.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray()) }
                entries.forEach { (name, file) -> put(name) { out -> file.inputStream().use { it.copyTo(out) } } }
            }
            manifest
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Copies the archive locally, decrypts and verifies it without touching current data. */
    suspend fun check(uri: Uri, password: CharArray?): RestoreCheck = withContext(Dispatchers.IO) {
        val local = File(context.cacheDir, "restore.zip")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> local.outputStream().use { input.copyTo(it) } }
                ?: return@withContext RestoreCheck.Invalid(RestoreCheck.Reason.IO_ERROR)
            check(local, password)
        } catch (e: Exception) {
            RestoreCheck.Invalid(RestoreCheck.Reason.IO_ERROR)
        }
    }

    fun check(archive: File, password: CharArray?): RestoreCheck {
        val dir = File(context.cacheDir, "restore_dir").apply { deleteRecursively(); mkdirs() }
        val zip = ZipFile(archive, password)
        try {
            if (!zip.isValidZipFile) return RestoreCheck.Invalid(RestoreCheck.Reason.NOT_A_BACKUP)
            if (zip.isEncrypted && (password == null || password.isEmpty())) return RestoreCheck.NeedsPassword
            zip.extractAll(dir.path)
        } catch (e: ZipException) {
            return if (e.type == ZipException.Type.WRONG_PASSWORD || zip.isEncrypted) RestoreCheck.WrongPassword
            else RestoreCheck.Invalid(RestoreCheck.Reason.NOT_A_BACKUP)
        } finally {
            runCatching { zip.close() }
        }
        val manifestFile = File(dir, MANIFEST_ENTRY)
        if (!manifestFile.exists()) return RestoreCheck.Invalid(RestoreCheck.Reason.NOT_A_BACKUP)
        val manifest = runCatching { json.decodeFromString(BackupManifest.serializer(), manifestFile.readText()) }.getOrNull()
            ?: return RestoreCheck.Invalid(RestoreCheck.Reason.NOT_A_BACKUP)
        if (manifest.app != "NUTRIX" || manifest.format > BackupManifest.FORMAT_VERSION) return RestoreCheck.Invalid(RestoreCheck.Reason.NOT_A_BACKUP)
        for (entry in manifest.files) {
            val f = File(dir, entry.path)
            if (!f.canonicalPath.startsWith(dir.canonicalPath)) return RestoreCheck.Invalid(RestoreCheck.Reason.NOT_A_BACKUP)
            if (!f.exists() || f.length() != entry.size || sha256(f) != entry.sha256) return RestoreCheck.Invalid(RestoreCheck.Reason.CHECKSUM_MISMATCH)
        }
        val dbFile = File(dir, DB_ENTRY)
        if (!dbFile.exists()) return RestoreCheck.Invalid(RestoreCheck.Reason.NOT_A_BACKUP)
        val dbCheck = runCatching {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                val ok = sqlite.rawQuery("PRAGMA integrity_check", null).use { c -> c.moveToFirst() && c.getString(0) == "ok" }
                ok to sqlite.version
            }
        }.getOrNull() ?: return RestoreCheck.Invalid(RestoreCheck.Reason.DATABASE_CORRUPTED)
        if (!dbCheck.first) return RestoreCheck.Invalid(RestoreCheck.Reason.DATABASE_CORRUPTED)
        if (dbCheck.second > db.openHelper.readableDatabase.version) return RestoreCheck.Invalid(RestoreCheck.Reason.NEWER_VERSION)
        return RestoreCheck.Ok(manifest, dir)
    }

    /**
     * Replaces all current data with a verified backup. The Room database is closed, so the
     * process must be restarted right after (see [com.nutrix.app.AppRestarter]).
     */
    suspend fun restore(ok: RestoreCheck.Ok) = withContext(Dispatchers.IO) {
        val dir = ok.dir
        val settingsJson = File(dir, SETTINGS_ENTRY).takeIf { it.exists() }?.readText()
        db.close()
        val target = context.getDatabasePath(NutrixDatabase.NAME)
        listOf(target, File(target.path + "-wal"), File(target.path + "-shm"), File(target.path + "-journal")).forEach { it.delete() }
        File(dir, DB_ENTRY).copyTo(target, overwrite = true)
        photos.deleteAll()
        File(dir, PHOTOS_DIR).listFiles()?.forEach { it.copyTo(File(photos.directory, it.name), overwrite = true) }
        if (settingsJson != null) {
            runCatching { settings.importFromBackup(json.decodeFromString(JsonObject.serializer(), settingsJson)) }
        }
        dir.deleteRecursively()
        File(context.cacheDir, "restore.zip").delete()
    }

    fun cleanup() {
        File(context.cacheDir, "restore_dir").deleteRecursively()
        File(context.cacheDir, "restore.zip").delete()
    }

    companion object {
        const val MANIFEST_ENTRY = "manifest.json"
        const val DB_ENTRY = "nutrix.db"
        const val SETTINGS_ENTRY = "settings.json"
        const val PHOTOS_DIR = "photos"

        fun sha256(file: File): String = file.inputStream().use { sha256(it) }

        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
