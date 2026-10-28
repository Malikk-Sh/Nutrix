package com.nutrix.app.data.repository

import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.database.dao.HealthDao
import com.nutrix.app.database.entity.ActivityDailyEntity
import com.nutrix.app.database.entity.ExerciseEntity
import com.nutrix.app.database.entity.SleepEntity
import com.nutrix.app.domain.ActivityMerger
import com.nutrix.app.domain.DailyActivity
import com.nutrix.app.domain.SleepDeduplicator
import com.nutrix.app.domain.ExerciseDeduplicator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Sleep, steps, heart rate and exercise. Most rows come from Health Connect / Samsung Health. */
@Singleton
class HealthRepository @Inject constructor(
    private val dao: HealthDao,
    private val settings: SettingsRepository,
    private val notifier: DataChangeNotifier,
) {
    private val priority = settings.settings.map { it.sourcePriority }

    fun observeSleepRange(from: Long, to: Long): Flow<List<SleepEntity>> =
        combine(dao.observeSleepRange(from, to), priority) { list, p -> SleepDeduplicator.dedupe(list, p) }

    fun observeLatestSleep(): Flow<SleepEntity?> =
        combine(dao.observeRecentSleep(10), priority) { list, p -> SleepDeduplicator.dedupe(list, p).maxByOrNull { it.endMillis } }


    fun observeLatestSleepDay(): Flow<List<SleepEntity>> = observeLatestSleep().flatMapLatest { latest ->
        if (latest == null) flowOf(emptyList()) else observeSleepRange(latest.localDate, latest.localDate)
    }

    fun observeRecentSleep(limit: Int): Flow<List<SleepEntity>> =
        combine(dao.observeRecentSleep(limit * 2), priority) { list, p ->
            SleepDeduplicator.dedupe(list, p).sortedByDescending { it.endMillis }.take(limit)
        }

    suspend fun getSleepRange(from: Long, to: Long): List<SleepEntity> =
        SleepDeduplicator.dedupe(dao.getSleepRange(from, to), settings.current().sourcePriority)

    fun observeSleepDays(from: Long, to: Long): Flow<List<Long>> =
        observeSleepRange(from, to + 1).map { sessions ->
            sessions.flatMap { listOf(it.localDate, TimeUtils.epochDay(it.startMillis, it.zoneId)) }
                .distinct().filter { it in from..to }
        }

    suspend fun getSleep(id: Long): SleepEntity? = dao.getSleep(id)

    suspend fun saveManualSleep(id: Long, startMillis: Long, endMillis: Long, zone: ZoneId, note: String?): Long {
        require(endMillis > startMillis)
        val previous = if (id != 0L) dao.getSleep(id) else null
        if (id != 0L) require(previous?.source == DataSource.MANUAL) { "Imported records are read-only" }
        val entity = SleepEntity(
            id = id,
            startMillis = startMillis,
            endMillis = endMillis,
            zoneId = zone.id,
            localDate = TimeUtils.epochDay(endMillis, zone.id),
            durationMinutes = ((endMillis - startMillis) / 60_000L).toInt(),
            source = DataSource.MANUAL,
            note = note?.takeIf { it.isNotBlank() },
            createdAt = previous?.createdAt ?: System.currentTimeMillis(),
        )
        val result = if (id == 0L) dao.insertSleep(entity) else {
            dao.updateSleep(entity.copy(updatedAt = System.currentTimeMillis()))
            id
        }
        notifier.notifyChanged()
        return result
    }

    suspend fun deleteSleep(id: Long): SleepEntity? {
        val s = dao.getSleep(id) ?: return null
        dao.deleteSleep(s)
        notifier.notifyChanged()
        return s
    }

    suspend fun restoreSleep(s: SleepEntity) {
        dao.insertSleepWithId(s)
        notifier.notifyChanged()
    }

    suspend fun upsertExternalSleep(record: SleepEntity): Boolean {
        val existing = dao.findSleep(record.source, requireNotNull(record.externalId))
        return when {
            existing == null -> { dao.insertSleep(record); true }
            existing.startMillis != record.startMillis || existing.endMillis != record.endMillis ||
                existing.durationMinutes != record.durationMinutes -> {
                dao.updateSleep(record.copy(id = existing.id, createdAt = existing.createdAt)); true
            }
            else -> false
        }
    }

    suspend fun deleteExternalSleep(source: DataSource, externalId: String): Boolean = dao.deleteSleepExternal(source, externalId) > 0

    // Activity
    fun observeActivityRange(from: Long, to: Long): Flow<List<DailyActivity>> =
        combine(dao.observeActivityRange(from, to), priority) { rows, p -> ActivityMerger.merge(rows, p) }

    fun observeActivityDay(day: Long): Flow<DailyActivity?> =
        combine(dao.observeActivityDay(day), priority) { rows, p -> if (rows.isEmpty()) null else ActivityMerger.mergeDay(day, rows, p) }

    suspend fun getActivityRange(from: Long, to: Long): List<DailyActivity> =
        ActivityMerger.merge(dao.getActivityRange(from, to), settings.current().sourcePriority)

    fun observeActivityDays(from: Long, to: Long): Flow<List<Long>> = dao.observeActivityDays(from, to)

    suspend fun upsertActivity(row: ActivityDailyEntity) = dao.upsertActivity(row)
    suspend fun getActivity(day: Long, source: DataSource): ActivityDailyEntity? = dao.getActivity(day, source)

    // Exercise
    fun observeExercises(from: Long, to: Long): Flow<List<ExerciseEntity>> =
        combine(dao.observeExerciseRange(from, to), priority) { rows, p -> ExerciseDeduplicator.dedupe(rows, p) }
    suspend fun getExercises(from: Long, to: Long): List<ExerciseEntity> =
        ExerciseDeduplicator.dedupe(dao.getExerciseRange(from, to), settings.current().sourcePriority)

    suspend fun upsertExternalExercise(record: ExerciseEntity): Boolean {
        val existing = dao.findExercise(record.source, requireNotNull(record.externalId))
        dao.upsertExercise(if (existing != null) record.copy(id = existing.id, createdAt = existing.createdAt) else record)
        return existing == null || existing.startMillis != record.startMillis || existing.endMillis != record.endMillis
    }

    suspend fun deleteExternalExercise(source: DataSource, externalId: String): Boolean = dao.deleteExerciseExternal(source, externalId) > 0

    suspend fun sleepExternalIdsBetween(source: DataSource, fromMillis: Long, toMillis: Long) = dao.sleepExternalIdsBetween(source, fromMillis, toMillis)
    suspend fun exerciseExternalIdsBetween(source: DataSource, fromMillis: Long, toMillis: Long) = dao.exerciseExternalIdsBetween(source, fromMillis, toMillis)

    /** Removes everything imported from a source (used when the user disconnects an integration). */
    suspend fun deleteAllFromSource(source: DataSource) {
        dao.deleteSleepFromSource(source)
        dao.deleteActivityFromSource(source)
        dao.deleteExercisesFromSource(source)
        notifier.notifyChanged()
    }

    fun notifyChanged() = notifier.notifyChanged()
}
