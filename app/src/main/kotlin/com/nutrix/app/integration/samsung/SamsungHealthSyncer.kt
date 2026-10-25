package com.nutrix.app.integration.samsung

import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.integration.SyncError
import com.nutrix.app.integration.SyncReport
import com.nutrix.app.integration.sync.ExternalImporter
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Samsung Health synchronisation. The SDK has no cheap change feed for every type we use,
 * so each run re-reads a recent window and reconciles it: new/edited records are upserted,
 * records deleted in Samsung Health are removed locally.
 */
@Singleton
class SamsungHealthSyncer @Inject constructor(
    private val gateway: SamsungHealthGateway,
    private val importer: ExternalImporter,
    private val settings: SettingsRepository,
) {
    private val source = DataSource.SAMSUNG_HEALTH

    suspend fun sync(): SyncReport {
        if (!gateway.sdkBundled) return SyncReport(error = SyncError.SDK_NOT_BUNDLED)
        if (gateway.status() != SamsungStatus.READY) return SyncReport(error = SyncError.NOT_AVAILABLE)
        return try {
            val categories = gateway.grantedCategories()
            if (categories.isEmpty()) return SyncReport(error = SyncError.NO_PERMISSIONS)
            val initialDone = settings.syncState(source).first().initialImportDone
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val from = today.minusDays(if (initialDone) 30 else 90)
            val fromMillis = from.atStartOfDay(zone).toInstant().toEpochMilli()
            val toMillis = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            var report = SyncReport()

            if (SamsungDataCategory.WEIGHT in categories) {
                val items = gateway.readWeights(from, today)
                report += SyncReport(
                    upserted = importer.importWeights(source, items),
                    deleted = importer.reconcileWeights(source, fromMillis, toMillis, items.map { it.id }.toSet()),
                )
            }
            if (SamsungDataCategory.SLEEP in categories) {
                val items = gateway.readSleep(from, today)
                report += SyncReport(
                    upserted = importer.importSleep(source, items),
                    deleted = importer.reconcileSleep(source, fromMillis, toMillis, items.map { it.id }.toSet()),
                )
            }
            if (SamsungDataCategory.EXERCISE in categories) {
                val items = gateway.readExercises(from, today)
                report += SyncReport(
                    upserted = importer.importExercises(source, items),
                    deleted = importer.reconcileExercises(source, fromMillis, toMillis, items.map { it.id }.toSet()),
                )
            }
            val dailyFrom = if (initialDone) today.minusDays(7) else from
            report = report.copy(days = importer.importDaily(source, gateway.readDaily(dailyFrom, today)))
            importer.notifyChanged()
            report
        } catch (e: SamsungHealthException) {
            SyncReport(error = if (e.status == SamsungStatus.NOT_AUTHORIZED) SyncError.NO_PERMISSIONS else SyncError.PLATFORM_ERROR)
        } catch (e: Exception) {
            SyncReport(error = SyncError.PLATFORM_ERROR)
        }
    }
}
