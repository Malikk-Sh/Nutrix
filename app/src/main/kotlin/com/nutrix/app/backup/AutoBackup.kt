package com.nutrix.app.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.security.SecretCipher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Periodic backups into a folder the user picked (Storage Access Framework). */
@Singleton
class AutoBackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val backup: BackupManager,
    private val cipher: SecretCipher,
) {
    fun schedule(enabled: Boolean, intervalDays: Int) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(intervalDays.coerceIn(1, 30).toLong(), TimeUnit.DAYS).build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    suspend fun setPassword(password: String?) {
        settings.setBackupPasswordBlob(password?.takeIf { it.isNotEmpty() }?.let { cipher.encrypt(it.toByteArray()) })
    }

    suspend fun hasPassword(): Boolean = settings.backupPasswordBlob() != null

    /** Writes one backup into the configured folder and prunes old automatic backups. */
    suspend fun runNow(): Boolean {
        val s = settings.current()
        val tree = s.autoBackupFolderUri?.let(Uri::parse) ?: return false
        // A missing/unreadable key must never downgrade an encrypted backup to plaintext.
        val password = settings.backupPasswordBlob()?.let { String(cipher.decrypt(it)).toCharArray() }
        check(!settings.autoBackupRequiresPassword() || (password != null && password.isNotEmpty())) { "Backup password unavailable; set it again" }
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = "$PREFIX${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))}.zip"
        val doc = DocumentsContract.createDocument(resolver, parent, "application/zip", name) ?: return false
        try {
            resolver.openOutputStream(doc)?.use { backup.createBackup(it, password) } ?: error("Cannot write backup")
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw e
        } finally {
            password?.fill('\u0000')
        }
        prune(tree, s.autoBackupKeep)
        settings.setLastAutoBackup(System.currentTimeMillis())
        return true
    }

    private fun prune(tree: Uri, keep: Int) {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val found = mutableListOf<Pair<String, String>>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val n = c.getString(1) ?: continue
                if (n.startsWith(PREFIX) && n.endsWith(".zip")) found += id to n
            }
        }
        found.sortedByDescending { it.second }.drop(keep.coerceAtLeast(1)).forEach { (id, _) ->
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
    }

    companion object {
        const val WORK_NAME = "auto_backup"
        const val PREFIX = "nutrix-auto-"
    }
}

@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val scheduler: AutoBackupScheduler,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = if (runCatching { scheduler.runNow() }.getOrDefault(false)) Result.success() else Result.retry()
}
