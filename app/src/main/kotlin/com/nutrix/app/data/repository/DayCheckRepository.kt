package com.nutrix.app.data.repository

import com.nutrix.app.database.dao.DayCheckDao
import com.nutrix.app.database.entity.DayCheckEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Days the user marked as fully recorded. */
@Singleton
class DayCheckRepository @Inject constructor(
    private val dao: DayCheckDao,
    private val notifier: DataChangeNotifier,
) {
    fun observeRange(from: Long, to: Long): Flow<Set<Long>> = dao.observeRange(from, to).map { it.toSet() }
    suspend fun getRange(from: Long, to: Long): Set<Long> = dao.getRange(from, to).toSet()

    suspend fun setConfirmed(day: Long, confirmed: Boolean) {
        if (confirmed) dao.upsert(DayCheckEntity(day)) else dao.delete(day)
        notifier.notifyChanged()
    }
}
