package com.nutrix.app.integration.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.integration.SyncError
import com.nutrix.app.integration.SyncReport
import com.nutrix.app.integration.healthconnect.HealthConnectSyncer
import com.nutrix.app.integration.samsung.SamsungHealthSyncer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Runs enabled health integrations, records their status and schedules periodic syncs. */
@Singleton
class HealthSyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val healthConnect: HealthConnectSyncer,
    private val samsung: SamsungHealthSyncer,
) {
    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    val lastSuccessfulSync: Flow<Long?> = combine(
        settings.syncState(DataSource.HEALTH_CONNECT),
        settings.syncState(DataSource.SAMSUNG_HEALTH),
        settings.settings,
    ) { hc, sh, s ->
        listOfNotNull(
            hc.lastSyncMillis.takeIf { s.healthConnectEnabled },
            sh.lastSyncMillis.takeIf { s.samsungHealthEnabled },
        ).maxOrNull()
    }

    suspend fun syncAll(manual: Boolean, background: Boolean = false): Map<DataSource, SyncReport> = mutex.withLock {
        _running.value = true
        try {
            val s = settings.current()
            val results = mutableMapOf<DataSource, SyncReport>()
            if (s.healthConnectEnabled) results[DataSource.HEALTH_CONNECT] = record(DataSource.HEALTH_CONNECT, healthConnect.sync(background))
            if (s.samsungHealthEnabled) results[DataSource.SAMSUNG_HEALTH] = record(DataSource.SAMSUNG_HEALTH, samsung.sync())
            results
        } finally {
            _running.value = false
        }
    }

    suspend fun syncSource(source: DataSource): SyncReport = mutex.withLock {
        _running.value = true
        try {
            when (source) {
                DataSource.HEALTH_CONNECT -> record(source, healthConnect.sync(background = false))
                DataSource.SAMSUNG_HEALTH -> record(source, samsung.sync())
                DataSource.MANUAL -> SyncReport()
            }
        } finally {
            _running.value = false
        }
    }

    /** Called when the app comes to the foreground. */
    suspend fun syncIfStale(maxAgeMillis: Long = 15 * 60 * 1000L) {
        val s = settings.current()
        if (!s.healthConnectEnabled && !s.samsungHealthEnabled) return
        val now = System.currentTimeMillis()
        val hc = settings.syncState(DataSource.HEALTH_CONNECT)
        val sh = settings.syncState(DataSource.SAMSUNG_HEALTH)
        val last = combine(hc, sh) { a, b -> listOfNotNull(a.lastSyncMillis, b.lastSyncMillis).minOrNull() }
        val oldest = last.first()
        if (oldest == null || now - oldest > maxAgeMillis) syncAll(manual = false)
    }

    private suspend fun record(source: DataSource, report: SyncReport): SyncReport {
        val err = report.error
        if (err == null) {
            settings.setSyncResult(
                source,
                millis = System.currentTimeMillis(),
                error = null,
                summary = "${report.upserted}/${report.deleted}/${report.days}",
                initialDone = true,
            )
        } else if (err != SyncError.BACKGROUND_NOT_ALLOWED) {
            settings.setSyncResult(source, millis = null, error = err.name, summary = null)
        }
        return report
    }

    fun schedulePeriodic(enabled: Boolean, intervalHours: Int) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(intervalHours.coerceIn(1, 24).toLong(), TimeUnit.HOURS)
            .addTag(WORK_NAME)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    companion object {
        const val WORK_NAME = "health_sync"
    }
}

@HiltWorker
class HealthSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: HealthSyncCoordinator,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        coordinator.syncAll(manual = false, background = true)
        return Result.success()
    }
}
