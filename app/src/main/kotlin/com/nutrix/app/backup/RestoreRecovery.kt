package com.nutrix.app.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import androidx.datastore.preferences.preferencesDataStoreFile
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.database.NutrixDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * The SQLite transaction is the commit point for database, photos and settings together.
 * Its draft marker tells startup whether to keep the new files or roll them back. Recovery
 * runs in Application.attachBaseContext, before Room, DataStore or providers can open them.
 */
internal object RestoreRecovery {
    const val MARKER = "__nutrix_restore_transaction__"
    private const val JOURNAL = "restore_transaction"

    @Serializable
    data class State(val id: String, val hadPhotos: Boolean, val hadSettings: Boolean)

    fun directory(context: Context): File = File(context.noBackupFilesDir, JOURNAL)
    fun photos(context: Context): File = File(context.filesDir, PhotoStorage.DIR)

    fun writeState(root: File, state: State) {
        val atomic = AtomicFile(File(root, "state.json"))
        val out = atomic.startWrite()
        try {
            out.write(Json.encodeToString(State.serializer(), state).toByteArray())
            atomic.finishWrite(out)
        } catch (e: Throwable) {
            atomic.failWrite(out)
            throw e
        }
    }

    fun move(source: File, target: File) {
        if (!source.renameTo(target)) throw IOException("Cannot move ${source.name}")
    }

    fun rollbackPhotos(context: Context, root: File, state: State) {
        val previous = File(root, "old_photos")
        val current = photos(context)
        if (previous.exists()) {
            check(current.deleteRecursively()) { "Cannot remove restored photos" }
            move(previous, current)
        } else if (!state.hadPhotos && !File(root, "new_photos").exists()) {
            check(current.deleteRecursively()) { "Cannot remove restored photos" }
        }
    }

    fun recover(context: Context) {
        val root = directory(context)
        if (!root.exists()) return
        val stateFile = AtomicFile(File(root, "state.json"))
        if (!stateFile.baseFile.exists() && !File(root, "state.json.bak").exists()) {
            check(root.deleteRecursively()) { "Cannot remove abandoned restore staging" }
            return // No mutation is allowed before the durable journal is written.
        }
        val state = stateFile.openRead().use { Json.decodeFromString(State.serializer(), it.reader().readText()) }
        val database = context.getDatabasePath(NutrixDatabase.NAME)
        val committed = database.exists() && SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT payload FROM drafts WHERE kind = ?", arrayOf(MARKER)).use {
                it.moveToFirst() && it.getString(0) == state.id
            }
        }
        if (!committed) {
            rollbackPhotos(context, root, state)
            val target = context.preferencesDataStoreFile("settings")
            File(target.path + ".tmp").delete()
            if (state.hadSettings) {
                target.parentFile?.mkdirs()
                File(root, "settings.preferences_pb").inputStream().use { input ->
                    target.outputStream().use { out -> input.copyTo(out); out.fd.sync() }
                }
            } else {
                check(!target.exists() || target.delete()) { "Cannot roll back settings" }
            }
        }
        // Keep the marker until after journal cleanup, so an interrupted cleanup is idempotent.
        check(root.deleteRecursively()) { "Cannot remove restore journal" }
        if (committed) SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DELETE FROM drafts WHERE kind = ? AND payload = ?", arrayOf(MARKER, state.id))
        }
    }
}
