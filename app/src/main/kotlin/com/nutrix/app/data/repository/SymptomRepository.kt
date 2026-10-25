package com.nutrix.app.data.repository

import com.nutrix.app.core.model.SymptomType
import com.nutrix.app.core.time.RecordTime
import com.nutrix.app.database.dao.NoteDao
import com.nutrix.app.database.dao.SymptomDao
import com.nutrix.app.database.entity.NoteEntity
import com.nutrix.app.database.entity.SymptomEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SymptomRepository @Inject constructor(
    private val dao: SymptomDao,
    private val notifier: DataChangeNotifier,
) {
    fun observeDay(day: Long): Flow<List<SymptomEntity>> = dao.observeDay(day)
    fun observeRange(from: Long, to: Long): Flow<List<SymptomEntity>> = dao.observeRange(from, to)
    fun observeRecent(limit: Int): Flow<List<SymptomEntity>> = dao.observeRecent(limit)
    fun observeDays(from: Long, to: Long): Flow<List<Long>> = dao.observeDays(from, to)
    suspend fun get(id: Long): SymptomEntity? = dao.get(id)
    suspend fun getRange(from: Long, to: Long): List<SymptomEntity> = dao.getRange(from, to)

    /** One-tap entry: just the symptom and the current time; details can be added later. */
    suspend fun quickAdd(type: SymptomType, time: RecordTime = RecordTime.now()): Long {
        val id = dao.insert(SymptomEntity(epochMillis = time.epochMillis, zoneId = time.zoneId, localDate = time.epochDay, type = type))
        notifier.notifyChanged()
        return id
    }

    suspend fun saveAll(symptoms: List<SymptomEntity>): List<Long> {
        val now = System.currentTimeMillis()
        val ids = symptoms.map { s ->
            val fixed = s.copy(localDate = s.time.epochDay, updatedAt = now)
            if (s.id == 0L) dao.insert(fixed.copy(createdAt = now)) else { dao.update(fixed); s.id }
        }
        notifier.notifyChanged()
        return ids
    }

    suspend fun delete(id: Long): SymptomEntity? {
        val s = dao.get(id) ?: return null
        dao.delete(s)
        notifier.notifyChanged()
        return s
    }

    suspend fun restore(s: SymptomEntity) {
        dao.insertWithId(s)
        notifier.notifyChanged()
    }

    suspend fun all(): List<SymptomEntity> = dao.allSymptoms()
}

@Singleton
class NoteRepository @Inject constructor(
    private val dao: NoteDao,
    private val notifier: DataChangeNotifier,
) {
    fun observeDay(day: Long): Flow<List<NoteEntity>> = dao.observeDay(day)
    fun observeRange(from: Long, to: Long): Flow<List<NoteEntity>> = dao.observeRange(from, to)
    fun observeDays(from: Long, to: Long): Flow<List<Long>> = dao.observeDays(from, to)
    suspend fun get(id: Long): NoteEntity? = dao.get(id)
    suspend fun getRange(from: Long, to: Long): List<NoteEntity> = dao.getRange(from, to)

    suspend fun save(id: Long, time: RecordTime, text: String): Long {
        val now = System.currentTimeMillis()
        val entity = NoteEntity(id = id, epochMillis = time.epochMillis, zoneId = time.zoneId, localDate = time.epochDay, text = text.trim())
        val result = if (id == 0L) dao.insert(entity) else {
            val createdAt = dao.get(id)?.createdAt ?: now
            dao.update(entity.copy(createdAt = createdAt, updatedAt = now)); id
        }
        notifier.notifyChanged()
        return result
    }

    suspend fun delete(id: Long): NoteEntity? {
        val n = dao.get(id) ?: return null
        dao.delete(n)
        notifier.notifyChanged()
        return n
    }

    suspend fun restore(n: NoteEntity) {
        dao.insertWithId(n)
        notifier.notifyChanged()
    }

    suspend fun search(query: String): List<NoteEntity> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return dao.allNotes().filter { it.text.lowercase().contains(q) }.take(200)
    }
}
