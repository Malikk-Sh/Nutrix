package com.nutrix.app.integration.healthconnect

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.WeightRecord
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.integration.SyncError
import com.nutrix.app.integration.SyncReport
import com.nutrix.app.integration.sync.ExternalImporter
import com.nutrix.app.integration.sync.SyncWindow
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

/**
 * Health Connect synchronisation:
 *  - first run imports a window of history (30 days, or 1 year with the history permission);
 *  - later runs read the Changes API per record type (upserts + deletions);
 *  - daily aggregates (steps, energy, heart rate) are recomputed for the last week each run.
 */
@Singleton
class HealthConnectSyncer @Inject constructor(
    private val manager: HealthConnectManager,
    private val importer: ExternalImporter,
    private val settings: SettingsRepository,
) {
    private val source = DataSource.HEALTH_CONNECT

    private val changeTypes: List<KClass<out Record>> = listOf(WeightRecord::class, SleepSessionRecord::class, ExerciseSessionRecord::class)

    suspend fun sync(background: Boolean): SyncReport {
        if (manager.availability() != HcAvailability.AVAILABLE) return SyncReport(error = SyncError.NOT_AVAILABLE)
        val granted = try {
            manager.grantedPermissions()
        } catch (e: Exception) {
            return SyncReport(error = SyncError.PLATFORM_ERROR)
        }
        if (granted.none { it in manager.corePermissions }) return SyncReport(error = SyncError.NO_PERMISSIONS)
        if (background && manager.backgroundPermission !in granted) return SyncReport(error = SyncError.BACKGROUND_NOT_ALLOWED)

        return try {
            val state = settings.syncState(source).first()
            val historyDays = if (manager.historyPermission in granted) 365L else 30L
            val now = Instant.now()
            var report = SyncReport()
            for (type in changeTypes) {
                if (HealthPermission.getReadPermission(type) !in granted) continue
                val key = type.simpleName ?: continue
                val token = settings.changesToken(source, key)
                report += if (!state.initialImportDone || token == null) {
                    fullImport(type, now.minusSeconds(historyDays * 86_400), now)
                } else {
                    when (val batch = manager.readChanges(token)) {
                        HealthConnectManager.ChangeBatch.TokenExpired -> fullImport(type, now.minusSeconds(historyDays * 86_400L), now)
                        is HealthConnectManager.ChangeBatch.Changes -> {
                            val upserted = applyUpserts(batch.upserts)
                            val deleted = batch.deletedIds.count { importer.deleteById(source, it) }
                            settings.setChangesToken(source, key, batch.nextToken)
                            SyncReport(upserted = upserted, deleted = deleted)
                        }
                    }
                }
            }
            val today = LocalDate.now(ZoneId.systemDefault())
            val dailyFrom = SyncWindow.start(today, state.lastSyncMillis, state.initialImportDone, historyDays)
            val days = importer.importDaily(source, manager.readDaily(dailyFrom, today, granted))
            report = report.copy(days = days)
            importer.notifyChanged()
            report
        } catch (e: SecurityException) {
            SyncReport(error = SyncError.PERMISSION_REVOKED)
        } catch (e: IllegalStateException) {
            SyncReport(error = SyncError.NOT_AVAILABLE)
        } catch (e: Exception) {
            SyncReport(error = SyncError.PLATFORM_ERROR)
        }
    }

    /** Imports a window and stores a fresh change token so later runs are incremental. */
    private suspend fun fullImport(type: KClass<out Record>, from: Instant, to: Instant): SyncReport {
        val key = type.simpleName ?: return SyncReport()
        // Take the token first so changes made during the import are not lost.
        val token = manager.changesToken(type)
        val upserted = when (type) {
            WeightRecord::class -> {
                val items = manager.readWeights(from, to)
                importer.reconcileWeights(source, from.toEpochMilli(), to.toEpochMilli(), items.map { it.id }.toSet())
                importer.importWeights(source, items)
            }
            SleepSessionRecord::class -> {
                val items = manager.readSleep(from, to)
                importer.reconcileSleep(source, from.toEpochMilli(), to.toEpochMilli(), items.map { it.id }.toSet())
                importer.importSleep(source, items)
            }
            ExerciseSessionRecord::class -> {
                val items = manager.readExercises(from, to)
                importer.reconcileExercises(source, from.toEpochMilli(), to.toEpochMilli(), items.map { it.id }.toSet())
                importer.importExercises(source, items)
            }
            else -> 0
        }
        settings.setChangesToken(source, key, token)
        return SyncReport(upserted = upserted)
    }

    private suspend fun applyUpserts(records: List<Record>): Int {
        var count = 0
        records.forEach { r ->
            count += when (r) {
                is WeightRecord -> importer.importWeights(source, listOf(manager.toExternal(r)))
                is SleepSessionRecord -> importer.importSleep(source, listOfNotNull(manager.toExternal(r)))
                is ExerciseSessionRecord -> importer.importExercises(source, listOf(manager.toExternal(r)))
                else -> 0
            }
        }
        return count
    }
}
