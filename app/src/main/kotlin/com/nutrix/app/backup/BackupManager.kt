package com.nutrix.app.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.withTransaction
import com.nutrix.app.BuildConfig
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.DataOperationGate
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.database.NutrixDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    private val gate: DataOperationGate = DataOperationGate(),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val checkedDirectories = ConcurrentHashMap.newKeySet<File>()

    suspend fun createBackup(output: OutputStream, password: CharArray?): BackupManifest = gate.exclusive {
        withContext(Dispatchers.IO) {
            val staging = File(context.cacheDir, "nutrix-backup-${UUID.randomUUID()}").apply { check(mkdirs()) }
            val snapshot = NutrixDatabase.build(context, File(staging, DB_ENTRY).path)
            try {
                val stagedDb = File(staging, DB_ENTRY)
                val stagedSettings = File(staging, SETTINGS_ENTRY)
                // Copy a transactional SQLite snapshot instead of racing a live database/WAL copy.
                db.withTransaction {
                    val target = snapshot.openHelper.writableDatabase
                    target.beginTransaction()
                    try {
                        DatabaseTransfer.replace(db.openHelper.readableDatabase, target)
                        target.setTransactionSuccessful()
                    } finally { target.endTransaction() }
                    val photoDir = File(staging, PHOTOS_DIR).apply { check(mkdirs()) }
                    photos.directory.listFiles()?.filter { it.isFile }?.forEach { it.copyTo(File(photoDir, it.name)) }
                    stagedSettings.writeText(json.encodeToString(JsonObject.serializer(), settings.exportForBackup()))
                }
                snapshot.close() // Checkpoint and close the private snapshot before archiving its main file.

                val photoFiles = File(staging, PHOTOS_DIR).listFiles()?.filter { it.isFile }.orEmpty()
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
                snapshot.close()
                staging.deleteRecursively()
            }
        }
    }

    /** Copies the archive locally, decrypts and verifies it without touching current data. */
    suspend fun check(uri: Uri, password: CharArray?): RestoreCheck = withContext(Dispatchers.IO) {
        val local = File.createTempFile("nutrix-restore-", ".zip", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> local.outputStream().use { input.copyTo(it) } }
                ?: return@withContext RestoreCheck.Invalid(RestoreCheck.Reason.IO_ERROR)
            check(local, password)
        } catch (e: Exception) {
            RestoreCheck.Invalid(RestoreCheck.Reason.IO_ERROR)
        } finally { local.delete() }
    }

    fun check(archive: File, password: CharArray?): RestoreCheck {
        val dir = File(context.cacheDir, "nutrix-restore-${UUID.randomUUID()}").apply { check(mkdirs()) }
        var accepted = false
        try {
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
            if (manifest.app != "NUTRIX" || manifest.format != BackupManifest.FORMAT_VERSION) return RestoreCheck.Invalid(RestoreCheck.Reason.NOT_A_BACKUP)
            val paths = manifest.files.map { it.path }
            if (paths.distinct().size != paths.size || DB_ENTRY !in paths || SETTINGS_ENTRY !in paths ||
                paths.any { it != DB_ENTRY && it != SETTINGS_ENTRY && !it.matches(Regex("photos/[A-Za-z0-9_.-]+")) }) {
                return RestoreCheck.Invalid(RestoreCheck.Reason.NOT_A_BACKUP)
            }
            val extracted = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toSet()
            if (extracted != paths.toSet() + MANIFEST_ENTRY || manifest.photoCount != paths.count { it.startsWith("$PHOTOS_DIR/") }) {
                return RestoreCheck.Invalid(RestoreCheck.Reason.NOT_A_BACKUP)
            }
            for (entry in manifest.files) {
                val f = File(dir, entry.path)
                if (!f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) return RestoreCheck.Invalid(RestoreCheck.Reason.NOT_A_BACKUP)
                if (!f.exists() || f.length() != entry.size || sha256(f) != entry.sha256) return RestoreCheck.Invalid(RestoreCheck.Reason.CHECKSUM_MISMATCH)
            }
            val dbFile = File(dir, DB_ENTRY)
            if (!dbFile.exists()) return RestoreCheck.Invalid(RestoreCheck.Reason.NOT_A_BACKUP)
            val dbCheck = runCatching {
                SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                    val ok = sqlite.rawQuery("PRAGMA integrity_check", null).use { c -> c.moveToFirst() && c.getString(0) == "ok" }
                    val foreignKeysOk = sqlite.rawQuery("PRAGMA foreign_key_check", null).use { !it.moveToFirst() }
                    (ok && foreignKeysOk) to sqlite.version
                }
            }.getOrNull() ?: return RestoreCheck.Invalid(RestoreCheck.Reason.DATABASE_CORRUPTED)
            if (!dbCheck.first) return RestoreCheck.Invalid(RestoreCheck.Reason.DATABASE_CORRUPTED)
            if (dbCheck.second > db.openHelper.readableDatabase.version) return RestoreCheck.Invalid(RestoreCheck.Reason.NEWER_VERSION)
            if (dbCheck.second != manifest.dbVersion) return RestoreCheck.Invalid(RestoreCheck.Reason.DATABASE_CORRUPTED)
            val candidate = NutrixDatabase.build(context, dbFile.path)
            try {
                // Opening through Room validates the identity/schema and supported migrations.
                if (DatabaseTransfer.tables(candidate.openHelper.writableDatabase) != DatabaseTransfer.tables(db.openHelper.readableDatabase)) {
                    return RestoreCheck.Invalid(RestoreCheck.Reason.DATABASE_CORRUPTED)
                }
                settings.validateBackup(json.decodeFromString(JsonObject.serializer(), File(dir, SETTINGS_ENTRY).readText()))
                validatePhotoReferences(candidate, dir, paths.toSet())
            } catch (e: Exception) {
                return RestoreCheck.Invalid(RestoreCheck.Reason.DATABASE_CORRUPTED)
            } finally { candidate.close() }
            accepted = true
            checkedDirectories += dir
            return RestoreCheck.Ok(manifest, dir)
        } catch (e: Exception) {
            return RestoreCheck.Invalid(RestoreCheck.Reason.IO_ERROR)
        } finally { if (!accepted) dir.deleteRecursively() }
    }

    /** Replace rows atomically, retaining a recovery journal for the file/settings changes. */
    suspend fun restore(ok: RestoreCheck.Ok) = gate.exclusive {
        withContext(Dispatchers.IO + NonCancellable) {
            val dir = ok.dir
            require(dir in checkedDirectories && dir.isDirectory) { "Backup must be checked before restoring" }
            require(ok.manifest.files.all { entry ->
                val file = File(dir, entry.path)
                file.isFile && file.length() == entry.size && sha256(file) == entry.sha256
            }) { "Verified backup changed before restoring" }
            val settingsJson = json.decodeFromString(JsonObject.serializer(), File(dir, SETTINGS_ENTRY).readText())
            settings.validateBackup(settingsJson)
            val root = RestoreRecovery.directory(context)
            check(!root.exists() && root.mkdirs()) { "Pending restore recovery" }
            val newPhotos = File(root, "new_photos").apply { check(mkdirs()) }
            val stagedDatabase = File(root, DB_ENTRY)
            val source = NutrixDatabase.build(context, stagedDatabase.path)
            var snapshot: androidx.datastore.preferences.core.Preferences? = null
            var committed = false
            var state: RestoreRecovery.State? = null
            try {
                // Cache can be evicted even after validation. Copy the database and each declared
                // photo into durable staging; never let Room recreate a missing cache database.
                ok.manifest.files.filter { it.path == DB_ENTRY || it.path.startsWith("$PHOTOS_DIR/") }.forEach { entry ->
                    val staged = if (entry.path == DB_ENTRY) stagedDatabase else File(newPhotos, File(entry.path).name)
                    staged.outputStream().use { out -> File(dir, entry.path).inputStream().use { it.copyTo(out) }; out.fd.sync() }
                    require(staged.length() == entry.size && sha256(staged) == entry.sha256) { "Backup changed during staging" }
                }
                source.openHelper.readableDatabase // Open the durable snapshot before switching live files.
                snapshot = settings.snapshotForRecovery(File(root, "settings.preferences_pb"))
                val preparedState = RestoreRecovery.State(UUID.randomUUID().toString(), RestoreRecovery.photos(context).exists(), File(root, "settings.preferences_pb").exists())
                state = preparedState
                RestoreRecovery.writeState(root, preparedState)
                db.withTransaction {
                    val target = db.openHelper.writableDatabase
                    DatabaseTransfer.replace(source.openHelper.readableDatabase, target)
                    val current = RestoreRecovery.photos(context)
                    if (current.exists()) RestoreRecovery.move(current, File(root, "old_photos"))
                    RestoreRecovery.move(newPhotos, current)
                    settings.importFromBackup(settingsJson)
                    target.execSQL("INSERT OR REPLACE INTO drafts(kind, payload, updatedAt) VALUES (?, ?, ?)",
                        arrayOf<Any>(RestoreRecovery.MARKER, preparedState.id, System.currentTimeMillis()))
                }
                committed = true
            } catch (e: Throwable) {
                if (state != null) RestoreRecovery.rollbackPhotos(context, root, state)
                snapshot?.let { settings.restoreSnapshot(it) }
                check(root.deleteRecursively()) { "Restore rolled back; recovery cleanup pending" }
                throw e
            } finally {
                runCatching { source.close() }
                if (committed) {
                    // Cleanup errors leave a committed journal for startup, never trigger a rollback.
                    runCatching {
                        if (root.deleteRecursively()) db.openHelper.writableDatabase.execSQL(
                            "DELETE FROM drafts WHERE kind = ?", arrayOf(RestoreRecovery.MARKER),
                        )
                    }
                    checkedDirectories.remove(dir)
                    runCatching { dir.deleteRecursively() }
                }
            }
        }
    }

    private fun validatePhotoReferences(candidate: NutrixDatabase, dir: File, paths: Set<String>) {
        listOf("SELECT fileName FROM meal_photos", "SELECT photoFile FROM drinks WHERE photoFile IS NOT NULL",
            "SELECT photoFile FROM favorite_meals WHERE photoFile IS NOT NULL").forEach { sql ->
            candidate.openHelper.readableDatabase.query(sql).use { rows ->
                while (rows.moveToNext()) {
                    val name = rows.getString(0)
                    require("$PHOTOS_DIR/$name" in paths && File(dir, "$PHOTOS_DIR/$name").isFile) { "Missing diary photo" }
                }
            }
        }
    }

    fun cleanup() {
        checkedDirectories.forEach { it.deleteRecursively() }
        checkedDirectories.clear()
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
