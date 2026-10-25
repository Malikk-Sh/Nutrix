package com.nutrix.app.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nutrix.app.database.entity.SymptomEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SymptomDao {
    @Query("SELECT * FROM symptoms WHERE localDate = :day ORDER BY epochMillis")
    fun observeDay(day: Long): Flow<List<SymptomEntity>>

    @Query("SELECT * FROM symptoms WHERE localDate BETWEEN :from AND :to ORDER BY epochMillis")
    fun observeRange(from: Long, to: Long): Flow<List<SymptomEntity>>

    @Query("SELECT * FROM symptoms WHERE localDate BETWEEN :from AND :to ORDER BY epochMillis")
    suspend fun getRange(from: Long, to: Long): List<SymptomEntity>

    @Query("SELECT * FROM symptoms ORDER BY epochMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SymptomEntity>>

    @Query("SELECT * FROM symptoms WHERE id = :id")
    suspend fun get(id: Long): SymptomEntity?

    @Query("SELECT DISTINCT localDate FROM symptoms WHERE localDate BETWEEN :from AND :to")
    fun observeDays(from: Long, to: Long): Flow<List<Long>>

    @Insert
    suspend fun insert(symptom: SymptomEntity): Long

    @Insert
    suspend fun insertAll(symptoms: List<SymptomEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWithId(symptom: SymptomEntity): Long

    @Update
    suspend fun update(symptom: SymptomEntity)

    @Delete
    suspend fun delete(symptom: SymptomEntity)

    @Query("SELECT * FROM symptoms ORDER BY epochMillis DESC")
    suspend fun allSymptoms(): List<SymptomEntity>

    @Query("SELECT COUNT(*) FROM symptoms")
    suspend fun count(): Int
}
