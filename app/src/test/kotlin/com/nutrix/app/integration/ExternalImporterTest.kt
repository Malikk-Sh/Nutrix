package com.nutrix.app.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.data.repository.DataChangeNotifier
import com.nutrix.app.data.repository.HealthRepository
import com.nutrix.app.data.repository.WeightRepository
import com.nutrix.app.database.NutrixDatabase
import com.nutrix.app.integration.sync.ExternalImporter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class ExternalImporterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: NutrixDatabase
    private lateinit var importer: ExternalImporter

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, NutrixDatabase::class.java).allowMainThreadQueries().build()
        val settings = SettingsRepository(context)
        val notifier = DataChangeNotifier()
        importer = ExternalImporter(WeightRepository(db.weightDao(), settings, notifier), HealthRepository(db.healthDao(), settings, notifier))
    }

    @After
    fun tearDown() = db.close()

    private val t0 = Instant.parse("2026-10-20T05:00:00Z")
    private val plus3 = ZoneOffset.ofHours(3)

    @Test
    fun importIsIdempotentUpdatesAndMirrorsDeletions() = runBlocking {
        val source = DataSource.SAMSUNG_HEALTH
        val weights = listOf(
            ExternalWeight("w1", t0, plus3, 55.0, "com.sec.android.app.shealth"),
            ExternalWeight("w2", t0.plusSeconds(86_400), plus3, 54.9, "com.sec.android.app.shealth"),
        )
        assertThat(importer.importWeights(source, weights)).isEqualTo(2)
        // Re-import of identical data changes nothing.
        assertThat(importer.importWeights(source, weights)).isEqualTo(0)
        assertThat(db.weightDao().count()).isEqualTo(2)

        // Edited remotely -> updated locally, not duplicated.
        assertThat(importer.importWeights(source, listOf(weights[1].copy(kg = 54.7)))).isEqualTo(1)
        assertThat(db.weightDao().findExternal(source, "w2")!!.weightKg).isEqualTo(54.7)

        // Deleted remotely -> reconciliation removes it.
        val from = t0.minusSeconds(3600).toEpochMilli()
        val to = t0.plusSeconds(3 * 86_400).toEpochMilli()
        assertThat(importer.reconcileWeights(source, from, to, setOf("w1"))).isEqualTo(1)
        assertThat(db.weightDao().findExternal(source, "w2")).isNull()

        // The stored zone/day follow the offset the measurement was recorded with.
        val stored = db.weightDao().findExternal(source, "w1")!!
        assertThat(stored.localDate).isEqualTo(LocalDate.of(2026, 10, 20).toEpochDay())
        assertThat(stored.sourceApp).isEqualTo("com.sec.android.app.shealth")
    }

    @Test
    fun sleepAndDailyActivityImportAndDeletion() = runBlocking {
        val source = DataSource.HEALTH_CONNECT
        val sleep = ExternalSleep("s1", t0.minusSeconds(8 * 3600), t0, plus3, 450, "com.sec.android.app.shealth")
        assertThat(importer.importSleep(source, listOf(sleep))).isEqualTo(1)
        assertThat(importer.importSleep(source, listOf(sleep))).isEqualTo(0)
        assertThat(db.healthDao().findSleep(source, "s1")!!.durationMinutes).isEqualTo(450)

        assertThat(importer.deleteById(source, "s1")).isTrue()
        assertThat(db.healthDao().findSleep(source, "s1")).isNull()

        val day = LocalDate.of(2026, 10, 20)
        importer.importDaily(source, listOf(ExternalDaily(day, steps = 8000, hrLatestBpm = 64, hrLatestMillis = t0.toEpochMilli())))
        importer.importDaily(source, listOf(ExternalDaily(day, steps = 9000)))
        val row = db.healthDao().getActivity(day.toEpochDay(), source)!!
        assertThat(row.steps).isEqualTo(9000)
        // Latest heart rate is kept when a later sync has no new reading.
        assertThat(row.hrLatestBpm).isEqualTo(64)
    }
}
