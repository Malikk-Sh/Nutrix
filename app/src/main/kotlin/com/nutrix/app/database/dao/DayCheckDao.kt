package com.nutrix.app.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nutrix.app.database.entity.DayCheckEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DayCheckDao {
    @Query("SELECT localDate FROM day_checks WHERE localDate BETWEEN :from AND :to")
    fun observeRange(from: Long, to: Long): Flow<List<Long>>

    @Query("SELECT localDate FROM day_checks WHERE localDate BETWEEN :from AND :to")
    suspend fun getRange(from: Long, to: Long): List<Long>

    @Upsert
    suspend fun upsert(check: DayCheckEntity)

    @Query("DELETE FROM day_checks WHERE localDate = :day")
    suspend fun delete(day: Long)
}
