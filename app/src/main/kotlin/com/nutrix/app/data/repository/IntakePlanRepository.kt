package com.nutrix.app.data.repository

import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.database.dao.IntakePlanDao
import com.nutrix.app.database.entity.IntakePlanEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** The history of calorie guidelines; each applies from its start day until the next one. */
@Singleton
class IntakePlanRepository @Inject constructor(
    private val dao: IntakePlanDao,
    private val notifier: DataChangeNotifier,
) {
    fun observeAll(): Flow<List<IntakePlanEntity>> = dao.observeAll()
    suspend fun all(): List<IntakePlanEntity> = dao.all()

    /** Starts [plan] today; a plan saved earlier today is replaced, earlier days keep theirs. */
    suspend fun startToday(plan: IntakePlanEntity, today: Long = TimeUtils.todayEpochDay()) {
        dao.upsert(plan.copy(startDay = today, createdAt = System.currentTimeMillis()))
        notifier.notifyChanged()
    }
}
