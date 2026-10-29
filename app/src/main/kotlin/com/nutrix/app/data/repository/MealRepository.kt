package com.nutrix.app.data.repository

import com.nutrix.app.core.DataOperationGate
import com.nutrix.app.core.model.AfterMealFeeling
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.database.entity.WeightEntity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
    private val gate: DataOperationGate = DataOperationGate(),
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
    suspend fun save(
        meal: MealEntity,
        photoFiles: List<String>,
        alsoSaveWeight: Boolean = false,
        saveAsFavorite: Boolean = false,
        draftKind: String? = null,
        saveToken: String? = null,
    ): Long = gate.exclusive {
        withContext(NonCancellable) {
            val now = System.currentTimeMillis()
            val previousSave = saveToken?.let { dao.getDraft(SAVE_RECEIPT_PREFIX + it)?.payload?.toLongOrNull() }
            if (previousSave != null) return@withContext previousSave
            val removedFiles = mutableListOf<String>()
            val favoriteTitle = if (saveAsFavorite) meal.title?.takeIf { it.isNotBlank() } ?: meal.items.firstOrNull() else null
            val favoritePhoto = if (favoriteTitle != null) photoFiles.firstOrNull()?.let { photos.duplicate(it) } else null
            val id = try {
                db.withTransaction {
                    val receiptKind = saveToken?.let { SAVE_RECEIPT_PREFIX + it }
                    val committed = receiptKind?.let { dao.getDraft(it)?.payload?.toLongOrNull() }
                    if (committed != null) return@withTransaction committed
                    val id = if (meal.id == 0L) {
                        dao.insert(meal.copy(createdAt = now, updatedAt = now))
                    } else {
                        val previous = requireNotNull(dao.get(meal.id)) { "Meal no longer exists" }
                        removedFiles += previous.photos.map { it.fileName }.filter { it !in photoFiles }
                        dao.update(meal.copy(updatedAt = now))
                        dao.deletePhotos(meal.id)
                        meal.id
                    }
                    dao.insertPhotos(photoFiles.mapIndexed { index, file -> MealPhotoEntity(mealId = id, fileName = file, position = index) })
                    if (alsoSaveWeight && meal.weightKg != null) {
                        db.weightDao().insert(WeightEntity(epochMillis = meal.epochMillis, zoneId = meal.zoneId,
                            localDate = meal.localDate, weightKg = meal.weightKg))
                    }
                    if (favoriteTitle != null) {
                        val previous = dao.findFavoriteByTitle(favoriteTitle)
                        previous?.photoFile?.let { removedFiles += it }
                        dao.upsertFavorite(FavoriteMealEntity(
                            id = previous?.id ?: 0, title = favoriteTitle, items = meal.items, mealType = meal.mealType,
                            portionGrams = meal.portionGrams, caloriesEstimate = meal.caloriesEstimate, nutrition = meal.nutrition,
                            photoFile = favoritePhoto, useCount = previous?.useCount ?: 0,
                            createdAt = previous?.createdAt ?: now,
                        ))
                    }
                    draftKind?.let { dao.deleteDraft(it) }
                    if (receiptKind != null) {
                        dao.upsertDraft(DraftEntity(receiptKind, id.toString()))
                        dao.deleteOldSaveReceipts(SAVE_RECEIPT_PREFIX, now - 90L * 24 * 60 * 60 * 1000)
                    }
                    id
                }
            } catch (error: Throwable) {
                photos.delete(favoritePhoto)
                throw error
            }
            // Cleanup cannot turn a committed save into a reported failure.
            removedFiles.forEach(photos::delete)
            notifier.notifyChanged()
            id
        }
    }

    /** Deletes the row but keeps photo files so the deletion can be undone. */
    suspend fun delete(id: Long, draftKind: String? = null): MealWithPhotos? = gate.exclusive {
        withContext(NonCancellable) {
            val snapshot = db.withTransaction {
                val snapshot = dao.get(id) ?: return@withTransaction null
                dao.delete(snapshot.meal)
                draftKind?.let { dao.deleteDraft(it) }
                snapshot
            }
            if (snapshot != null) notifier.notifyChanged()
            snapshot
        }
    }

    suspend fun restore(snapshot: MealWithPhotos) {
        db.withTransaction {
            dao.insertWithId(snapshot.meal)
            dao.deletePhotos(snapshot.meal.id)
            dao.insertPhotos(snapshot.photos)
        }
        notifier.notifyChanged()
    }

    /** Quick answer from the after-meal question: fills only a meal that has no feeling yet. */
    suspend fun markFeelingNormal(id: Long): Boolean = gate.exclusive {
        withContext(NonCancellable) {
            val meal = dao.get(id)?.meal ?: return@withContext false
            if (meal.feelings.isNotEmpty()) return@withContext false
            dao.update(meal.copy(feelings = listOf(AfterMealFeeling.NORMAL), updatedAt = System.currentTimeMillis()))
            notifier.notifyChanged()
            true
        }
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

    suspend fun saveFavorite(favorite: FavoriteMealEntity): Long = withContext(NonCancellable) {
        var removedPhoto: String? = null
        val id = db.withTransaction {
            val existing = if (favorite.id == 0L) dao.findFavoriteByTitle(favorite.title) else dao.getFavorite(favorite.id)
            removedPhoto = existing?.photoFile?.takeIf { it != favorite.photoFile }
            val toSave = if (existing != null) favorite.copy(id = existing.id, useCount = existing.useCount, createdAt = existing.createdAt) else favorite
            val id = dao.upsertFavorite(toSave)
            if (toSave.id != 0L) toSave.id else id
        }
        photos.delete(removedPhoto)
        id
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
                nutrition = meal.nutrition,
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
    suspend fun isSaveCommitted(token: String): Boolean = dao.getDraft(SAVE_RECEIPT_PREFIX + token) != null
    suspend fun saveDraft(kind: String, payload: String) = gate.exclusive { dao.upsertDraft(DraftEntity(kind, payload)) }
    suspend fun deleteDraft(kind: String) = gate.exclusive { dao.deleteDraft(kind) }

    /** A cancelled editor may share files with a meal, favorite, or another recoverable draft. */
    suspend fun deleteUnusedPhotos(candidates: List<String>) = gate.exclusive {
        val referenced = referencedPhotoFiles() + db.drinkDao().allPhotoFiles()
        val drafts = draftPayloads()
        candidates.distinct().filter { it !in referenced && drafts.none { draft -> draft.contains(it) } }.forEach(photos::delete)
    }

    suspend fun referencedPhotoFiles(): Set<String> =
        (dao.allPhotoFiles() + dao.favoritePhotoFiles()).toSet()

    suspend fun draftPayloads(): List<String> = dao.allDrafts().map { it.payload }

    private companion object {
        // Bounded recovery receipts share the existing key/value journal table; no schema change.
        const val SAVE_RECEIPT_PREFIX = "__meal_commit__:"
    }
}
