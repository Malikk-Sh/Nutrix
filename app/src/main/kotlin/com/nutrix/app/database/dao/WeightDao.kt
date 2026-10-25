package com.nutrix.app.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.database.entity.WeightEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WeightDao {
    @Query("SELECT * FROM weights ORDER BY epochMillis DESC")
    fun observeAll(): Flow<List<WeightEntity>>

    @Query("SELECT * FROM weights WHERE localDate BETWEEN :from AND :to ORDER BY epochMillis")
    fun observeRange(from: Long, to: Long): Flow<List<WeightEntity>>

    @Query("SELECT * FROM weights WHERE localDate BETWEEN :from AND :to ORDER BY epochMillis")
    suspend fun getRange(from: Long, to: Long): List<WeightEntity>

    @Query("SELECT * FROM weights WHERE localDate = :day ORDER BY epochMillis")
    fun observeDay(day: Long): Flow<List<WeightEntity>>

    @Query("SELECT * FROM weights ORDER BY epochMillis DESC LIMIT 1")
    fun observeLatest(): Flow<WeightEntity?>

    @Query("SELECT * FROM weights ORDER BY epochMillis DESC LIMIT 1")
    suspend fun latest(): WeightEntity?

    @Query("SELECT * FROM weights WHERE id = :id")
    suspend fun get(id: Long): WeightEntity?

    @Query("SELECT * FROM weights WHERE epochMillis BETWEEN :fromMillis AND :toMillis")
    suspend fun getBetweenMillis(fromMillis: Long, toMillis: Long): List<WeightEntity>

    @Query("SELECT * FROM weights WHERE source = :source AND externalId = :externalId LIMIT 1")
    suspend fun findExternal(source: DataSource, externalId: String): WeightEntity?

    @Query("DELETE FROM weights WHERE source = :source AND externalId = :externalId")
    suspend fun deleteExternal(source: DataSource, externalId: String): Int

    @Query("SELECT externalId FROM weights WHERE source = :source AND externalId IS NOT NULL AND epochMillis BETWEEN :fromMillis AND :toMillis")
    suspend fun externalIdsBetween(source: DataSource, fromMillis: Long, toMillis: Long): List<String>

    @Query("DELETE FROM weights WHERE source = :source")
    suspend fun deleteAllFromSource(source: DataSource): Int

    @Query("SELECT DISTINCT localDate FROM weights WHERE localDate BETWEEN :from AND :to")
    fun observeDays(from: Long, to: Long): Flow<List<Long>>

    @Insert
    suspend fun insert(weight: WeightEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWithId(weight: WeightEntity): Long

    @Update
    suspend fun update(weight: WeightEntity)

    @Delete
    suspend fun delete(weight: WeightEntity)

    @Query("SELECT * FROM weights ORDER BY epochMillis DESC")
    suspend fun allWeights(): List<WeightEntity>

    @Query("SELECT COUNT(*) FROM weights")
    suspend fun count(): Int
}
