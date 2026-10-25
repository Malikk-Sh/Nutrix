package com.nutrix.app.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.model.SymptomType
import com.nutrix.app.database.entity.DrinkEntity
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.database.entity.MealPhotoEntity
import com.nutrix.app.database.entity.SymptomEntity
import com.nutrix.app.database.entity.WeightEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: NutrixDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, NutrixDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun meal(day: Long, hour: Int = 12, title: String? = null) = MealEntity(
        epochMillis = day * 86_400_000L + hour * 3_600_000L, zoneId = "UTC", localDate = day, mealType = MealType.LUNCH, title = title,
    )

    @Test
    fun mealPhotosAreDeletedWithMealAndSymptomLinkIsCleared() = runTest {
        val dao = db.mealDao()
        val id = dao.insert(meal(100, title = "Суп"))
        dao.insertPhotos(listOf(MealPhotoEntity(mealId = id, fileName = "a.jpg", position = 0), MealPhotoEntity(mealId = id, fileName = "b.jpg", position = 1)))
        val symptomId = db.symptomDao().insert(SymptomEntity(epochMillis = 100 * 86_400_000L, zoneId = "UTC", localDate = 100, type = SymptomType.NAUSEA, mealId = id))

        val loaded = dao.get(id)!!
        assertThat(loaded.sortedPhotos.map { it.fileName }).containsExactly("a.jpg", "b.jpg").inOrder()

        dao.delete(loaded.meal)
        assertThat(dao.allPhotoFiles()).isEmpty()
        assertThat(db.symptomDao().get(symptomId)!!.mealId).isNull()
    }

    @Test
    fun listsAndConvertersRoundTrip() = runTest {
        val dao = db.mealDao()
        val id = dao.insert(meal(5).copy(items = listOf("Гречка", "Курица, запечённая"), feelings = listOf(com.nutrix.app.core.model.AfterMealFeeling.BLOATING)))
        val m = dao.get(id)!!.meal
        assertThat(m.items).containsExactly("Гречка", "Курица, запечённая").inOrder()
        assertThat(m.feelings).containsExactly(com.nutrix.app.core.model.AfterMealFeeling.BLOATING)
    }

    @Test
    fun drinkTotalsPerDay() = runTest {
        val dao = db.drinkDao()
        dao.insert(DrinkEntity(epochMillis = 0, zoneId = "UTC", localDate = 1, drinkType = DrinkType.WATER, volumeMl = 250))
        dao.insert(DrinkEntity(epochMillis = 0, zoneId = "UTC", localDate = 1, drinkType = DrinkType.TEA, volumeMl = 300))
        dao.insert(DrinkEntity(epochMillis = 0, zoneId = "UTC", localDate = 2, drinkType = DrinkType.COFFEE, volumeMl = 150))
        assertThat(dao.totalForDay(1)).isEqualTo(550)
        assertThat(dao.observeTotalsPerDay(0, 10).first().associate { it.localDate to it.value }).containsExactly(1L, 550.0, 2L, 150.0)
    }

    @Test
    fun externalWeightsCannotBeImportedTwice() = runTest {
        val dao = db.weightDao()
        val w = WeightEntity(epochMillis = 0, zoneId = "UTC", localDate = 0, weightKg = 60.0, source = DataSource.HEALTH_CONNECT, externalId = "hc-1")
        dao.insert(w)
        assertThrows(SQLiteConstraintException::class.java) { kotlinx.coroutines.runBlocking { dao.insert(w) } }
        // Manual entries have no external id and never collide.
        dao.insert(w.copy(source = DataSource.MANUAL, externalId = null))
        dao.insert(w.copy(source = DataSource.MANUAL, externalId = null))
        assertThat(dao.count()).isEqualTo(3)
    }

    @Test
    fun handlesManyRecords() = runTest {
        val dao = db.mealDao()
        val days = 3 * 365L
        db.withTransaction {
            (0 until days).forEach { d -> repeat(5) { h -> dao.insert(meal(d, 7 + h * 3, "Блюдо $d-$h")) } }
        }
        assertThat(dao.count()).isEqualTo((days * 5).toInt())
        val started = System.nanoTime()
        val month = dao.getRange(days - 30, days)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertThat(month).hasSize(30 * 5)
        assertThat(elapsedMs).isLessThan(2_000)
        assertThat(dao.observeCountsPerDay(0, days).first()).hasSize(days.toInt())
    }

    @Test
    fun dataSurvivesReopeningTheDatabaseFile() = runTest {
        val name = "persist-test.db"
        context.deleteDatabase(name)
        val first = Room.databaseBuilder(context, NutrixDatabase::class.java, name).allowMainThreadQueries().build()
        first.mealDao().insert(meal(42, title = "Омлет"))
        first.close()
        val second = Room.databaseBuilder(context, NutrixDatabase::class.java, name).allowMainThreadQueries().build()
        assertThat(second.mealDao().getRange(42, 42).single().meal.title).isEqualTo("Омлет")
        second.close()
        context.deleteDatabase(name)
    }
}
