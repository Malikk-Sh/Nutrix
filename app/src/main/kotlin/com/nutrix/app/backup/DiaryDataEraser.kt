package com.nutrix.app.backup

import android.app.NotificationManager
import android.content.Context
import androidx.work.WorkManager
import androidx.work.await
import com.nutrix.app.core.DataOperationGate
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.database.NutrixDatabase
import com.nutrix.app.widget.WidgetUpdater
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Erases the diary and all app-owned temporary copies; user-exported files stay with their owner. */
@Singleton
class DiaryDataEraser @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: NutrixDatabase,
    private val photos: PhotoStorage,
    private val settings: SettingsRepository,
    private val gate: DataOperationGate,
) {
    suspend fun erase() = gate.exclusive { withContext(Dispatchers.IO + NonCancellable) {
        WorkManager.getInstance(context).cancelAllWork().await()
        check(RestoreRecovery.directory(context).deleteRecursively()) { "Cannot remove restore recovery data" }
        db.clearAllTables()
        photos.deleteAll()
        settings.clearAll()
        check(context.cacheDir.deleteRecursively()) { "Cannot remove temporary diary files" }
        context.externalCacheDir?.let { check(it.deleteRecursively()) { "Cannot remove external cache" } }
        context.getSystemService(NotificationManager::class.java).cancelAll()
        WidgetUpdater.updateAll(context)
    } }
}
