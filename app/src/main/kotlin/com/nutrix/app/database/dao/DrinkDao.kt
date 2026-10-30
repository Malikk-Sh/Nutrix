package com.nutrix.app.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.nutrix.app.database.entity.DayValue
import com.nutrix.app.database.entity.DrinkEntity
import com.nutrix.app.database.entity.DrinkTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DrinkDao {
    @Query("SELECT * FROM drinks WHERE localDate = :day ORDER BY epochMillis")
    fun observeDay(day: Long): Flow<List<DrinkEntity>>

    @Query("SELECT * FROM drinks WHERE localDate BETWEEN :from AND :to ORDER BY epochMillis")
    fun observeRange(from: Long, to: Long): Flow<List<DrinkEntity>>

    @Query("SELECT * FROM drinks WHERE localDate BETWEEN :from AND :to ORDER BY epochMillis")
    suspend fun getRange(from: Long, to: Long): List<DrinkEntity>

    @Query("SELECT COALESCE(SUM(volumeMl), 0) FROM drinks WHERE localDate = :day")
    fun observeTotalForDay(day: Long): Flow<Int>

    @Query("SELECT COALESCE(SUM(volumeMl), 0) FROM drinks WHERE localDate = :day")
    suspend fun totalForDay(day: Long): Int

    @Query("SELECT localDate, SUM(volumeMl) AS value FROM drinks WHERE localDate BETWEEN :from AND :to GROUP BY localDate")
    fun observeTotalsPerDay(from: Long, to: Long): Flow<List<DayValue>>

    @Query("SELECT DISTINCT localDate FROM drinks WHERE localDate BETWEEN :from AND :to")
    fun observeDays(from: Long, to: Long): Flow<List<Long>>

    @Query("SELECT * FROM drinks WHERE id = :id")
    suspend fun get(id: Long): DrinkEntity?

    @Query("SELECT * FROM drinks WHERE mealId = :mealId ORDER BY epochMillis")
    fun observeForMeal(mealId: Long): Flow<List<DrinkEntity>>

    @Insert
    suspend fun insert(drink: DrinkEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWithId(drink: DrinkEntity): Long

    @Update
    suspend fun update(drink: DrinkEntity)

    @Delete
    suspend fun delete(drink: DrinkEntity)

    @Query("SELECT * FROM drinks ORDER BY epochMillis DESC")
    suspend fun allDrinks(): List<DrinkEntity>

    @Query("SELECT photoFile FROM drinks WHERE photoFile IS NOT NULL")
    suspend fun allPhotoFiles(): List<String>

    @Query("SELECT COUNT(*) FROM drinks")
    suspend fun count(): Int

    // Templates
    @Query("SELECT * FROM drink_templates ORDER BY sortOrder, id")
    fun observeTemplates(): Flow<List<DrinkTemplateEntity>>

    @Query("SELECT * FROM drink_templates WHERE id = :id")
    suspend fun getTemplate(id: Long): DrinkTemplateEntity?

    @Upsert
    suspend fun upsertTemplate(template: DrinkTemplateEntity): Long

    @Delete
    suspend fun deleteTemplate(template: DrinkTemplateEntity)
}
