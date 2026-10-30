package com.nutrix.app.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nutrix.app.database.entity.IntakePlanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IntakePlanDao {
    @Query("SELECT * FROM intake_plans ORDER BY startDay")
    fun observeAll(): Flow<List<IntakePlanEntity>>

    @Query("SELECT * FROM intake_plans ORDER BY startDay")
    suspend fun all(): List<IntakePlanEntity>

    @Upsert
    suspend fun upsert(plan: IntakePlanEntity)
}
