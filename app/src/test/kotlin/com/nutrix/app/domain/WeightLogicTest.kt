package com.nutrix.app.domain

import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.database.entity.WeightEntity
import org.junit.Test

class WeightLogicTest {
    private val day = 24 * 3_600_000L
    private val t0 = 1_760_000_000_000L

    private fun w(id: Long, at: Long, kg: Double, source: DataSource = DataSource.MANUAL) =
        WeightEntity(id = id, epochMillis = at, zoneId = "UTC", localDate = at / day, weightKg = kg, source = source, externalId = if (source == DataSource.MANUAL) null else "x$id")

    @Test
    fun `same weighing from several sources is shown once, manual wins`() {
        val list = listOf(
            w(1, t0, 54.8, DataSource.HEALTH_CONNECT),
            w(2, t0 + 60_000, 54.8, DataSource.SAMSUNG_HEALTH),
            w(3, t0 + 120_000, 54.85, DataSource.MANUAL),
        )
        val result = WeightDeduplicator.dedupe(list, listOf(DataSource.HEALTH_CONNECT, DataSource.SAMSUNG_HEALTH))
        assertThat(result.map { it.id }).containsExactly(3L)
    }

    @Test
    fun `source priority decides between external duplicates`() {
        val list = listOf(w(1, t0, 60.0, DataSource.HEALTH_CONNECT), w(2, t0 + 30_000, 60.0, DataSource.SAMSUNG_HEALTH))
        assertThat(WeightDeduplicator.dedupe(list, listOf(DataSource.SAMSUNG_HEALTH, DataSource.HEALTH_CONNECT)).single().id).isEqualTo(2L)
        assertThat(WeightDeduplicator.dedupe(list, listOf(DataSource.HEALTH_CONNECT, DataSource.SAMSUNG_HEALTH)).single().id).isEqualTo(1L)
    }

    @Test
    fun `different weighings are all kept`() {
        val list = listOf(w(1, t0, 60.0), w(2, t0 + 3 * 3_600_000L, 60.0), w(3, t0 + 60_000, 61.0, DataSource.HEALTH_CONNECT))
        assertThat(WeightDeduplicator.dedupe(list, listOf(DataSource.HEALTH_CONNECT))).hasSize(3)
    }

    @Test
    fun `likely duplicate of a manual entry is detected`() {
        val existing = listOf(w(1, t0, 54.8))
        assertThat(WeightDeduplicator.findLikelyDuplicate(existing, t0 + 10 * 60_000, 54.8)).isNotNull()
        assertThat(WeightDeduplicator.findLikelyDuplicate(existing, t0 + 10 * 60_000, 55.3)).isNull()
        assertThat(WeightDeduplicator.findLikelyDuplicate(existing, t0 + 10 * 60_000, 54.8, ignoreId = 1)).isNull()
    }

    @Test
    fun `stats compute week and month change, averages and extremes`() {
        val now = t0 + 40 * day
        val list = listOf(
            w(1, t0 + 10 * day, 60.0),
            w(2, t0 + 33 * day, 58.0),
            w(3, t0 + 38 * day, 57.0),
            w(4, t0 + 40 * day, 56.0),
        )
        val stats = WeightStatsCalculator.compute(list, targetKg = 55.0, nowMillis = now)
        assertThat(stats.latest?.id).isEqualTo(4L)
        assertThat(stats.weekChangeKg).isWithin(1e-9).of(-2.0)
        assertThat(stats.monthChangeKg).isWithin(1e-9).of(-4.0)
        assertThat(stats.minKg).isEqualTo(56.0)
        assertThat(stats.maxKg).isEqualTo(60.0)
        assertThat(stats.toGoalKg).isWithin(1e-9).of(1.0)
        assertThat(stats.avg7Kg).isWithin(1e-9).of((58.0 + 57.0 + 56.0) / 3)
    }

    @Test
    fun `no reference measurement means no change value`() {
        val list = listOf(w(1, t0, 60.0), w(2, t0 + day, 59.5))
        val stats = WeightStatsCalculator.compute(list, null, nowMillis = t0 + day)
        assertThat(stats.weekChangeKg).isNull()
        assertThat(stats.monthChangeKg).isNull()
    }
}
