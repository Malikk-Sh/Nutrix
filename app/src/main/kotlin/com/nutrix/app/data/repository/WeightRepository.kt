package com.nutrix.app.data.repository

import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.core.time.RecordTime
import com.nutrix.app.database.dao.WeightDao
import com.nutrix.app.database.entity.WeightEntity
import com.nutrix.app.domain.WeightDeduplicator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WeightRepository @Inject constructor(
    private val dao: WeightDao,
    private val settings: SettingsRepository,
    private val notifier: DataChangeNotifier,
) {
    /** All measurements with cross-source duplicates collapsed, newest first. */
    fun observeAll(): Flow<List<WeightEntity>> =
        combine(dao.observeAll(), settings.settings.map { it.sourcePriority }) { list, priority ->
            WeightDeduplicator.dedupe(list, priority).sortedByDescending { it.epochMillis }
        }

    fun observeRange(from: Long, to: Long): Flow<List<WeightEntity>> =
        combine(dao.observeRange(from, to), settings.settings.map { it.sourcePriority }) { list, priority ->
            WeightDeduplicator.dedupe(list, priority)
        }

    fun observeDay(day: Long): Flow<List<WeightEntity>> = observeRange(day, day)
    fun observeLatest(): Flow<WeightEntity?> = observeAll().map { it.firstOrNull() }
    fun observeDays(from: Long, to: Long): Flow<List<Long>> = dao.observeDays(from, to)

    suspend fun latest(): WeightEntity? = dao.latest()
    suspend fun get(id: Long): WeightEntity? = dao.get(id)

    suspend fun getRange(from: Long, to: Long): List<WeightEntity> =
        WeightDeduplicator.dedupe(dao.getRange(from, to), settings.current().sourcePriority)

    suspend fun findLikelyDuplicate(time: RecordTime, weightKg: Double, ignoreId: Long = 0): WeightEntity? {
        val nearby = dao.getBetweenMillis(time.epochMillis - 3_600_000L, time.epochMillis + 3_600_000L)
        return WeightDeduplicator.findLikelyDuplicate(nearby, time.epochMillis, weightKg, ignoreId)
    }

    suspend fun saveManual(id: Long, time: RecordTime, weightKg: Double, note: String?): Long {
        val now = System.currentTimeMillis()
        val result = if (id == 0L) {
            dao.insert(
                WeightEntity(
                    epochMillis = time.epochMillis,
                    zoneId = time.zoneId,
                    localDate = time.epochDay,
                    weightKg = weightKg,
                    source = DataSource.MANUAL,
                    note = note?.takeIf { it.isNotBlank() },
                ),
            )
        } else {
            val existing = dao.get(id) ?: return 0
            require(existing.source == DataSource.MANUAL) { "Imported records are read-only" }
            dao.update(
                existing.copy(
                    epochMillis = time.epochMillis,
                    zoneId = time.zoneId,
                    localDate = time.epochDay,
                    weightKg = weightKg,
                    note = note?.takeIf { it.isNotBlank() },
                    updatedAt = now,
                ),
            )
            id
        }
        notifier.notifyChanged()
        return result
    }

    suspend fun delete(id: Long): WeightEntity? {
        val w = dao.get(id) ?: return null
        dao.delete(w)
        notifier.notifyChanged()
        return w
    }

    suspend fun restore(w: WeightEntity) {
        dao.insertWithId(w)
        notifier.notifyChanged()
    }

    /** Inserts or updates a record imported from a health platform. */
    suspend fun upsertExternal(record: WeightEntity): Boolean {
        val externalId = requireNotNull(record.externalId)
        val existing = dao.findExternal(record.source, externalId)
        return if (existing == null) {
            dao.insert(record)
            true
        } else if (existing.weightKg != record.weightKg || existing.epochMillis != record.epochMillis) {
            dao.update(record.copy(id = existing.id, createdAt = existing.createdAt, updatedAt = System.currentTimeMillis()))
            true
        } else false
    }

    suspend fun deleteExternal(source: DataSource, externalId: String): Boolean = dao.deleteExternal(source, externalId) > 0

    suspend fun deleteAllFromSource(source: DataSource) = dao.deleteAllFromSource(source)

    suspend fun externalIdsBetween(source: DataSource, fromMillis: Long, toMillis: Long): List<String> =
        dao.externalIdsBetween(source, fromMillis, toMillis)

    suspend fun search(query: String): List<WeightEntity> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return dao.allWeights().filter { it.note.orEmpty().lowercase().contains(q) }.take(200)
    }
}
