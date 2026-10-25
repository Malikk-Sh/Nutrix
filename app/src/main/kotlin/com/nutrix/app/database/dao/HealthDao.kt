package com.nutrix.app.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.database.entity.ActivityDailyEntity
import com.nutrix.app.database.entity.ExerciseEntity
import com.nutrix.app.database.entity.SleepEntity
import kotlinx.coroutines.flow.Flow

/** Sleep, activity and exercise data (mostly imported from health platforms). */
@Dao
interface HealthDao {
    // Sleep
    @Query("SELECT * FROM sleep_sessions WHERE localDate BETWEEN :from AND :to ORDER BY endMillis")
    fun observeSleepRange(from: Long, to: Long): Flow<List<SleepEntity>>

    @Query("SELECT * FROM sleep_sessions WHERE localDate BETWEEN :from AND :to ORDER BY endMillis")
    suspend fun getSleepRange(from: Long, to: Long): List<SleepEntity>

    @Query("SELECT * FROM sleep_sessions ORDER BY endMillis DESC LIMIT :limit")
    fun observeRecentSleep(limit: Int): Flow<List<SleepEntity>>

    @Query("SELECT * FROM sleep_sessions WHERE id = :id")
    suspend fun getSleep(id: Long): SleepEntity?

    @Query("SELECT * FROM sleep_sessions WHERE source = :source AND externalId = :externalId LIMIT 1")
    suspend fun findSleep(source: DataSource, externalId: String): SleepEntity?

    @Query("SELECT * FROM sleep_sessions WHERE endMillis > :fromMillis AND startMillis < :toMillis")
    suspend fun overlappingSleep(fromMillis: Long, toMillis: Long): List<SleepEntity>

    @Query("DELETE FROM sleep_sessions WHERE source = :source AND externalId = :externalId")
    suspend fun deleteSleepExternal(source: DataSource, externalId: String): Int

    @Query("SELECT DISTINCT localDate FROM sleep_sessions WHERE localDate BETWEEN :from AND :to")
    fun observeSleepDays(from: Long, to: Long): Flow<List<Long>>

    @Insert
    suspend fun insertSleep(sleep: SleepEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSleepWithId(sleep: SleepEntity): Long

    @Update
    suspend fun updateSleep(sleep: SleepEntity)

    @Delete
    suspend fun deleteSleep(sleep: SleepEntity)

    @Query("SELECT * FROM sleep_sessions ORDER BY endMillis DESC")
    suspend fun allSleep(): List<SleepEntity>

    // Daily activity
    @Upsert
    suspend fun upsertActivity(activity: ActivityDailyEntity)

    @Query("SELECT * FROM activity_daily WHERE localDate = :day AND source = :source")
    suspend fun getActivity(day: Long, source: DataSource): ActivityDailyEntity?

    @Query("SELECT * FROM activity_daily WHERE localDate BETWEEN :from AND :to ORDER BY localDate")
    fun observeActivityRange(from: Long, to: Long): Flow<List<ActivityDailyEntity>>

    @Query("SELECT * FROM activity_daily WHERE localDate BETWEEN :from AND :to ORDER BY localDate")
    suspend fun getActivityRange(from: Long, to: Long): List<ActivityDailyEntity>

    @Query("SELECT * FROM activity_daily WHERE localDate = :day")
    fun observeActivityDay(day: Long): Flow<List<ActivityDailyEntity>>

    // Exercises
    @Upsert
    suspend fun upsertExercise(exercise: ExerciseEntity): Long

    @Query("SELECT * FROM exercises WHERE source = :source AND externalId = :externalId LIMIT 1")
    suspend fun findExercise(source: DataSource, externalId: String): ExerciseEntity?

    @Query("DELETE FROM exercises WHERE source = :source AND externalId = :externalId")
    suspend fun deleteExerciseExternal(source: DataSource, externalId: String): Int

    @Query("SELECT * FROM exercises WHERE localDate BETWEEN :from AND :to ORDER BY startMillis")
    fun observeExerciseRange(from: Long, to: Long): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises WHERE localDate BETWEEN :from AND :to ORDER BY startMillis")
    suspend fun getExerciseRange(from: Long, to: Long): List<ExerciseEntity>

    @Query("SELECT DISTINCT localDate FROM activity_daily WHERE localDate BETWEEN :from AND :to AND (steps > 0 OR exerciseMinutes > 0)")
    fun observeActivityDays(from: Long, to: Long): Flow<List<Long>>

    @Query("SELECT externalId FROM sleep_sessions WHERE source = :source AND externalId IS NOT NULL AND endMillis BETWEEN :fromMillis AND :toMillis")
    suspend fun sleepExternalIdsBetween(source: DataSource, fromMillis: Long, toMillis: Long): List<String>

    @Query("SELECT externalId FROM exercises WHERE source = :source AND externalId IS NOT NULL AND startMillis BETWEEN :fromMillis AND :toMillis")
    suspend fun exerciseExternalIdsBetween(source: DataSource, fromMillis: Long, toMillis: Long): List<String>

    // Integration clean-up
    @Query("DELETE FROM sleep_sessions WHERE source = :source")
    suspend fun deleteSleepFromSource(source: DataSource): Int

    @Query("DELETE FROM activity_daily WHERE source = :source")
    suspend fun deleteActivityFromSource(source: DataSource): Int

    @Query("DELETE FROM exercises WHERE source = :source")
    suspend fun deleteExercisesFromSource(source: DataSource): Int
}
