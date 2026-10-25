package com.nutrix.app.data.repository

import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.core.time.RecordTime
import com.nutrix.app.database.NutrixDatabase
import com.nutrix.app.database.dao.MealDao
import com.nutrix.app.database.entity.DraftEntity
import com.nutrix.app.database.entity.FavoriteMealEntity
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.database.entity.MealPhotoEntity
import com.nutrix.app.database.entity.MealWithPhotos
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MealRepository @Inject constructor(
    private val db: NutrixDatabase,
    private val dao: MealDao,
    private val photos: PhotoStorage,
    private val notifier: DataChangeNotifier,
) {
    fun observeDay(epochDay: Long): Flow<List<MealWithPhotos>> = dao.observeDay(epochDay)
    fun observeRange(from: Long, to: Long): Flow<List<MealWithPhotos>> = dao.observeRange(from, to)
    fun observeRecent(limit: Int): Flow<List<MealWithPhotos>> = dao.observeRecent(limit)
    fun observe(id: Long): Flow<MealWithPhotos?> = dao.observe(id)
    fun observeDays(from: Long, to: Long): Flow<List<Long>> = dao.observeDays(from, to)
    fun observeLast(): Flow<MealEntity?> = dao.observeLast()
    suspend fun get(id: Long): MealWithPhotos? = dao.get(id)
    suspend fun getRange(from: Long, to: Long): List<MealWithPhotos> = dao.getRange(from, to)
    suspend fun page(limit: Int, offset: Int): List<MealWithPhotos> = dao.getPage(limit, offset)

    /** Meals in the hours before [time], used to link a symptom to a possible trigger meal. */
    suspend fun mealsBefore(time: RecordTime, hours: Int = 8): List<MealWithPhotos> =
        dao.getBetweenMillis(time.epochMillis - hours * 3_600_000L, time.epochMillis + 30 * 60_000L)

    /**
     * Inserts or updates a meal together with its ordered photo list. Photo files that were
     * attached before but are no longer part of the meal are deleted.
     */
    suspend fun save(meal: MealEntity, photoFiles: List<String>): Long {
        val now = System.currentTimeMillis()
        var removedFiles: List<String> = emptyList()
        val id = db.withTransaction {
            val id = if (meal.id == 0L) {
                dao.insert(meal.copy(createdAt = now, updatedAt = now))
            } else {
                val previous = dao.get(meal.id)
                removedFiles = previous?.photos?.map { it.fileName }.orEmpty().filter { it !in photoFiles }
                dao.update(meal.copy(updatedAt = now))
                dao.deletePhotos(meal.id)
                meal.id
            }
            dao.insertPhotos(photoFiles.mapIndexed { index, file -> MealPhotoEntity(mealId = id, fileName = file, position = index) })
            id
        }
        removedFiles.forEach(photos::delete)
        notifier.notifyChanged()
        return id
    }

    /** Deletes the row but keeps photo files so the deletion can be undone. */
    suspend fun delete(id: Long): MealWithPhotos? {
        val snapshot = dao.get(id) ?: return null
        dao.delete(snapshot.meal)
        notifier.notifyChanged()
        return snapshot
    }

    suspend fun restore(snapshot: MealWithPhotos) {
        db.withTransaction {
            dao.insertWithId(snapshot.meal)
            dao.deletePhotos(snapshot.meal.id)
            dao.insertPhotos(snapshot.photos)
        }
        notifier.notifyChanged()
    }

    /** Final clean-up once undo is no longer possible. */
    fun purge(snapshot: MealWithPhotos) {
        snapshot.photos.forEach { photos.delete(it.fileName) }
    }

    suspend fun frequentTitles(): List<String> = dao.frequentTitles()

    suspend fun search(query: String): List<MealEntity> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return dao.allMeals().filter { m ->
            m.title.orEmpty().lowercase().contains(q) ||
                m.items.any { it.lowercase().contains(q) } ||
                m.note.orEmpty().lowercase().contains(q) ||
                m.feelingOther.orEmpty().lowercase().contains(q)
        }.take(200)
    }

    // Favorites
    fun observeFavorites(): Flow<List<FavoriteMealEntity>> = dao.observeFavorites()
    suspend fun getFavorite(id: Long): FavoriteMealEntity? = dao.getFavorite(id)

    suspend fun saveFavorite(favorite: FavoriteMealEntity): Long {
        val existing = if (favorite.id == 0L) dao.findFavoriteByTitle(favorite.title) else null
        val toSave = if (existing != null) {
            if (existing.photoFile != null && existing.photoFile != favorite.photoFile) photos.delete(existing.photoFile)
            favorite.copy(id = existing.id, useCount = existing.useCount, createdAt = existing.createdAt)
        } else favorite
        val id = dao.upsertFavorite(toSave)
        return if (toSave.id != 0L) toSave.id else id
    }

    /** Creates a favorite from a meal; the first photo is copied so both can be edited independently. */
    suspend fun favoriteFromMeal(meal: MealEntity, firstPhoto: String?): Long {
        val title = meal.title?.takeIf { it.isNotBlank() } ?: meal.items.firstOrNull() ?: return 0
        val photoCopy = firstPhoto?.let { photos.duplicate(it) }
        return saveFavorite(
            FavoriteMealEntity(
                title = title,
                items = meal.items,
                mealType = meal.mealType,
                portionGrams = meal.portionGrams,
                caloriesEstimate = meal.caloriesEstimate,
                note = null,
                photoFile = photoCopy,
            ),
        )
    }

    suspend fun deleteFavorite(favorite: FavoriteMealEntity) {
        dao.deleteFavorite(favorite)
        photos.delete(favorite.photoFile)
    }

    suspend fun markFavoriteUsed(id: Long) = dao.markFavoriteUsed(id, System.currentTimeMillis())

    // Drafts
    suspend fun getDraft(kind: String): DraftEntity? = dao.getDraft(kind)
    suspend fun saveDraft(kind: String, payload: String) = dao.upsertDraft(DraftEntity(kind, payload))
    suspend fun deleteDraft(kind: String) = dao.deleteDraft(kind)

    suspend fun referencedPhotoFiles(): Set<String> =
        (dao.allPhotoFiles() + dao.favoritePhotoFiles()).toSet()

    suspend fun draftPayloads(): List<String> = dao.allDrafts().map { it.payload }
}
