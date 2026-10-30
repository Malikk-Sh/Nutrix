package com.nutrix.app.data.repository

import com.nutrix.app.core.model.DrinkNutrition
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.core.time.RecordTime
import com.nutrix.app.database.dao.DrinkDao
import com.nutrix.app.database.entity.DayValue
import com.nutrix.app.database.entity.DrinkEntity
import com.nutrix.app.database.entity.DrinkTemplateEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DrinkRepository @Inject constructor(
    private val dao: DrinkDao,
    private val photos: PhotoStorage,
    private val notifier: DataChangeNotifier,
) {
    fun observeDay(epochDay: Long): Flow<List<DrinkEntity>> = dao.observeDay(epochDay)
    fun observeRange(from: Long, to: Long): Flow<List<DrinkEntity>> = dao.observeRange(from, to)
    fun observeTotal(epochDay: Long): Flow<Int> = dao.observeTotalForDay(epochDay)
    fun observeTotalsPerDay(from: Long, to: Long): Flow<List<DayValue>> = dao.observeTotalsPerDay(from, to)
    fun observeDays(from: Long, to: Long): Flow<List<Long>> = dao.observeDays(from, to)
    fun observeTemplates(): Flow<List<DrinkTemplateEntity>> = dao.observeTemplates()
    fun observeForMeal(mealId: Long): Flow<List<DrinkEntity>> = dao.observeForMeal(mealId)
    suspend fun totalForDay(epochDay: Long): Int = dao.totalForDay(epochDay)
    suspend fun get(id: Long): DrinkEntity? = dao.get(id)
    suspend fun getRange(from: Long, to: Long): List<DrinkEntity> = dao.getRange(from, to)

    /** A quick entry gets the type's default energy: tea and coffee without additions, anything else unknown. */
    suspend fun quickAdd(
        type: DrinkType,
        volumeMl: Int,
        name: String? = null,
        templateId: Long? = null,
        time: RecordTime = RecordTime.now(),
        nutrition: DrinkNutrition? = DrinkNutrition.defaultFor(type),
    ): Long {
        val id = dao.insert(
            DrinkEntity(
                epochMillis = time.epochMillis,
                zoneId = time.zoneId,
                localDate = time.epochDay,
                drinkType = type,
                name = name,
                volumeMl = volumeMl,
                templateId = templateId,
                nutrition = nutrition,
            ),
        )
        notifier.notifyChanged()
        return id
    }

    /** A template's drinks get exactly its energy: an older template without one adds drinks with unknown energy. */
    suspend fun addFromTemplate(template: DrinkTemplateEntity): Long =
        quickAdd(template.drinkType, template.volumeMl, template.name, template.id, nutrition = template.nutrition)

    suspend fun save(drink: DrinkEntity): Long {
        val now = System.currentTimeMillis()
        val fixed = drink.copy(localDate = drink.time.epochDay, updatedAt = now)
        val id = if (drink.id == 0L) {
            dao.insert(fixed.copy(createdAt = now))
        } else {
            val previous = dao.get(drink.id)
            if (previous?.photoFile != null && previous.photoFile != drink.photoFile) photos.delete(previous.photoFile)
            dao.update(fixed)
            drink.id
        }
        notifier.notifyChanged()
        return id
    }

    suspend fun delete(id: Long): DrinkEntity? {
        val drink = dao.get(id) ?: return null
        dao.delete(drink)
        notifier.notifyChanged()
        return drink
    }

    suspend fun restore(drink: DrinkEntity) {
        dao.insertWithId(drink)
        notifier.notifyChanged()
    }

    fun purge(drink: DrinkEntity) = photos.delete(drink.photoFile)

    suspend fun saveTemplate(template: DrinkTemplateEntity): Long = dao.upsertTemplate(template)
    suspend fun deleteTemplate(template: DrinkTemplateEntity) = dao.deleteTemplate(template)

    suspend fun search(query: String): List<DrinkEntity> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return dao.allDrinks().filter { it.note.orEmpty().lowercase().contains(q) || it.name.orEmpty().lowercase().contains(q) }.take(200)
    }

    suspend fun referencedPhotoFiles(): Set<String> = dao.allPhotoFiles().toSet()
}
