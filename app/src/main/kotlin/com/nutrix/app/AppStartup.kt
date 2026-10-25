package com.nutrix.app

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nutrix.app.backup.AutoBackupScheduler
import com.nutrix.app.core.ApplicationScope
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.core.security.AppLockManager
import com.nutrix.app.data.repository.DataChangeNotifier
import com.nutrix.app.data.repository.DrinkRepository
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.integration.sync.HealthSyncCoordinator
import com.nutrix.app.reminders.NotificationChannels
import com.nutrix.app.reminders.ReminderScheduler
import com.nutrix.app.widget.WidgetUpdater
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Process-start wiring: app lock, notification channels, background work, widget refresh. */
@Singleton
class AppStartup @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val lockManager: AppLockManager,
    private val settings: SettingsRepository,
    private val reminders: ReminderScheduler,
    private val sync: HealthSyncCoordinator,
    private val autoBackup: AutoBackupScheduler,
    private val notifier: DataChangeNotifier,
) {
    fun onAppCreate() {
        lockManager.start()
        NotificationChannels.create(context)
        scope.launch {
            val s = settings.current()
            reminders.rescheduleAll()
            sync.schedulePeriodic(s.autoSync && (s.healthConnectEnabled || s.samsungHealthEnabled), s.syncIntervalHours)
            autoBackup.schedule(s.autoBackupEnabled && s.autoBackupFolderUri != null, s.autoBackupIntervalDays)
            MaintenanceWorker.schedule(context)
        }
        scope.launch {
            notifier.changes.debounce(800).collect { WidgetUpdater.updateAll(context) }
        }
    }
}

/** Daily housekeeping: removes photo files no record references any more. */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val photos: PhotoStorage,
    private val meals: MealRepository,
    private val drinks: DrinkRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val referenced = meals.referencedPhotoFiles() + drinks.referencedPhotoFiles()
        photos.deleteUnreferenced(referenced, meals.draftPayloads())
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "maintenance",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
