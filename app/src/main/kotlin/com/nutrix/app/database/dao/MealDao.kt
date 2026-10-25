package com.nutrix.app.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.nutrix.app.database.entity.DayCount
import com.nutrix.app.database.entity.DraftEntity
import com.nutrix.app.database.entity.FavoriteMealEntity
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.database.entity.MealPhotoEntity
import com.nutrix.app.database.entity.MealWithPhotos
import kotlinx.coroutines.flow.Flow

@Dao
interface MealDao {
    @Transaction
    @Query("SELECT * FROM meals WHERE localDate = :day ORDER BY epochMillis")
    fun observeDay(day: Long): Flow<List<MealWithPhotos>>

    @Transaction
    @Query("SELECT * FROM meals WHERE localDate BETWEEN :from AND :to ORDER BY epochMillis")
    fun observeRange(from: Long, to: Long): Flow<List<MealWithPhotos>>

    @Transaction
    @Query("SELECT * FROM meals WHERE localDate BETWEEN :from AND :to ORDER BY epochMillis")
    suspend fun getRange(from: Long, to: Long): List<MealWithPhotos>

    @Transaction
    @Query("SELECT * FROM meals ORDER BY epochMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MealWithPhotos>>

    @Transaction
    @Query("SELECT * FROM meals ORDER BY epochMillis DESC LIMIT :limit OFFSET :offset")
    suspend fun getPage(limit: Int, offset: Int): List<MealWithPhotos>

    @Transaction
    @Query("SELECT * FROM meals WHERE epochMillis BETWEEN :fromMillis AND :toMillis ORDER BY epochMillis DESC")
    suspend fun getBetweenMillis(fromMillis: Long, toMillis: Long): List<MealWithPhotos>

    @Transaction
    @Query("SELECT * FROM meals WHERE id = :id")
    suspend fun get(id: Long): MealWithPhotos?

    @Transaction
    @Query("SELECT * FROM meals WHERE id = :id")
    fun observe(id: Long): Flow<MealWithPhotos?>

    @Insert
    suspend fun insert(meal: MealEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWithId(meal: MealEntity): Long

    @Update
    suspend fun update(meal: MealEntity)

    @Delete
    suspend fun delete(meal: MealEntity)

    @Insert
    suspend fun insertPhotos(photos: List<MealPhotoEntity>)

    @Query("DELETE FROM meal_photos WHERE mealId = :mealId")
    suspend fun deletePhotos(mealId: Long)

    @Query("SELECT fileName FROM meal_photos")
    suspend fun allPhotoFiles(): List<String>

    @Query("SELECT DISTINCT localDate FROM meals WHERE localDate BETWEEN :from AND :to")
    fun observeDays(from: Long, to: Long): Flow<List<Long>>

    @Query("SELECT localDate, COUNT(*) AS count FROM meals WHERE localDate BETWEEN :from AND :to GROUP BY localDate")
    fun observeCountsPerDay(from: Long, to: Long): Flow<List<DayCount>>

    /** Full scan used for case-insensitive (Cyrillic-aware) search in Kotlin. */
    @Query("SELECT * FROM meals ORDER BY epochMillis DESC")
    suspend fun allMeals(): List<MealEntity>

    @Query(
        """
        SELECT title FROM meals WHERE title IS NOT NULL AND title != ''
        GROUP BY title ORDER BY COUNT(*) DESC, MAX(epochMillis) DESC LIMIT 200
        """,
    )
    suspend fun frequentTitles(): List<String>

    @Query("SELECT COUNT(*) FROM meals")
    suspend fun count(): Int

    @Query("SELECT * FROM meals ORDER BY epochMillis DESC LIMIT 1")
    fun observeLast(): Flow<MealEntity?>

    // Favorites
    @Query("SELECT * FROM favorite_meals ORDER BY useCount DESC, lastUsedAt DESC, title")
    fun observeFavorites(): Flow<List<FavoriteMealEntity>>

    @Query("SELECT * FROM favorite_meals WHERE id = :id")
    suspend fun getFavorite(id: Long): FavoriteMealEntity?

    @Query("SELECT * FROM favorite_meals WHERE title = :title LIMIT 1")
    suspend fun findFavoriteByTitle(title: String): FavoriteMealEntity?

    @Upsert
    suspend fun upsertFavorite(favorite: FavoriteMealEntity): Long

    @Delete
    suspend fun deleteFavorite(favorite: FavoriteMealEntity)

    @Query("UPDATE favorite_meals SET useCount = useCount + 1, lastUsedAt = :now WHERE id = :id")
    suspend fun markFavoriteUsed(id: Long, now: Long)

    @Query("SELECT photoFile FROM favorite_meals WHERE photoFile IS NOT NULL")
    suspend fun favoritePhotoFiles(): List<String>

    // Drafts
    @Query("SELECT * FROM drafts WHERE kind = :kind")
    suspend fun getDraft(kind: String): DraftEntity?

    @Upsert
    suspend fun upsertDraft(draft: DraftEntity)

    @Query("DELETE FROM drafts WHERE kind = :kind")
    suspend fun deleteDraft(kind: String)

    @Query("SELECT * FROM drafts")
    suspend fun allDrafts(): List<DraftEntity>
}
