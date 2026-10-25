package com.nutrix.app.core

import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.core.model.PortionEaten
import com.nutrix.app.core.model.WeightUnit
import com.nutrix.app.core.security.PinHasher
import com.nutrix.app.core.time.RecordTime
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.core.util.Units
import com.nutrix.app.database.entity.ActivityDailyEntity
import com.nutrix.app.database.entity.SleepEntity
import com.nutrix.app.domain.ActivityMerger
import com.nutrix.app.domain.SleepDeduplicator
import com.nutrix.app.feature.ai.FoodReference
import com.nutrix.app.feature.reports.CsvWriter
import com.nutrix.app.feature.search.DateQueryParser
import org.junit.Test
import java.io.StringWriter
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class CoreLogicTest {

    @Test
    fun `record keeps its local day after a time zone change`() {
        // 23:30 in Moscow on Oct 25 is 20:30 UTC: the meal must stay on Oct 25 everywhere.
        val rt = RecordTime.of(LocalDate.of(2026, 10, 25), LocalTime.of(23, 30), ZoneId.of("Europe/Moscow"))
        assertThat(rt.localDate).isEqualTo(LocalDate.of(2026, 10, 25))
        assertThat(rt.localTime).isEqualTo(LocalTime.of(23, 30))
        assertThat(rt.isForeignZone(ZoneId.of("Asia/Tokyo"))).isTrue()
        assertThat(rt.isForeignZone(ZoneId.of("Europe/Moscow"))).isFalse()
        assertThat(TimeUtils.epochDay(rt.epochMillis, "Asia/Tokyo")).isEqualTo(LocalDate.of(2026, 10, 26).toEpochDay())
    }

    @Test
    fun `invalid zone id falls back to device zone`() {
        assertThat(TimeUtils.zoneOrDefault("Not/AZone")).isEqualTo(ZoneId.systemDefault())
    }

    @Test
    fun `bedtime minutes round trip`() {
        listOf(LocalTime.of(21, 15), LocalTime.of(0, 0), LocalTime.of(2, 45)).forEach {
            assertThat(TimeUtils.eveningMinutesToTime(TimeUtils.bedtimeMinutesFromEvening(it))).isEqualTo(it)
        }
    }

    @Test
    fun `weight units convert both ways`() {
        assertThat(Units.toDisplay(Units.toKg(120.0, WeightUnit.LB), WeightUnit.LB)).isWithin(1e-9).of(120.0)
        assertThat(Units.parseDecimal("54,8")).isEqualTo(54.8)
        assertThat(Units.parseDecimal(" 54.8 ")).isEqualTo(54.8)
        assertThat(Units.parseDecimal("abc")).isNull()
        assertThat(Units.isPlausibleWeightKg(1.0)).isFalse()
    }

    @Test
    fun `csv escapes separators quotes and new lines`() {
        val out = StringWriter()
        CsvWriter(out).row("a", "b,c", "say \"hi\"", "line\nbreak", null)
        assertThat(out.toString()).isEqualTo("a,\"b,c\",\"say \"\"hi\"\"\",\"line\nbreak\",\r\n")
    }

    @Test
    fun `date queries are parsed`() {
        val today = LocalDate.of(2026, 10, 25)
        assertThat(DateQueryParser.parse("25.10.2026", today)).isEqualTo(LocalDate.of(2026, 10, 25))
        assertThat(DateQueryParser.parse("2026-10-01", today)).isEqualTo(LocalDate.of(2026, 10, 1))
        assertThat(DateQueryParser.parse("31.12", today)).isEqualTo(LocalDate.of(2025, 12, 31))
        assertThat(DateQueryParser.parse("3.10", today)).isEqualTo(LocalDate.of(2026, 10, 3))
        assertThat(DateQueryParser.parse("борщ", today)).isNull()
    }

    @Test
    fun `food reference finds dishes and estimates approximate energy`() {
        val borsch = FoodReference.find("Борщ со сметаной")
        assertThat(borsch?.name).isEqualTo("Борщ")
        // 49 kcal/100 g × 300 g × 50 % eaten
        assertThat(FoodReference.estimateKcal(borsch!!, 300, PortionEaten.P50)).isEqualTo(74)
        assertThat(FoodReference.find("xyz")).isNull()
    }

    @Test
    fun `pin hash verifies only the right pin`() {
        val verifier = PinHasher.create("2580", iterations = 1000)
        val decoded = PinHasher.Verifier.decode(verifier.encode())
        assertThat(PinHasher.verify("2580", decoded)).isTrue()
        assertThat(PinHasher.verify("0000", decoded)).isFalse()
    }

    @Test
    fun `activity merge picks highest priority source per metric`() {
        val rows = listOf(
            ActivityDailyEntity(localDate = 10, source = DataSource.HEALTH_CONNECT, steps = 5000, hrAvg = null),
            ActivityDailyEntity(localDate = 10, source = DataSource.SAMSUNG_HEALTH, steps = 5200, hrAvg = 72),
        )
        val merged = ActivityMerger.mergeDay(10, rows, listOf(DataSource.HEALTH_CONNECT, DataSource.SAMSUNG_HEALTH))
        assertThat(merged.steps).isEqualTo(5000)
        assertThat(merged.stepsSource).isEqualTo(DataSource.HEALTH_CONNECT)
        assertThat(merged.hrAvg).isEqualTo(72)
        assertThat(merged.hrSource).isEqualTo(DataSource.SAMSUNG_HEALTH)
    }

    @Test
    fun `same night from two sources is shown once`() {
        val h = 3_600_000L
        val a = SleepEntity(id = 1, startMillis = 0, endMillis = 8 * h, zoneId = "UTC", localDate = 0, durationMinutes = 480, source = DataSource.SAMSUNG_HEALTH, externalId = "a")
        val b = SleepEntity(id = 2, startMillis = 10 * 60_000, endMillis = 8 * h, zoneId = "UTC", localDate = 0, durationMinutes = 470, source = DataSource.HEALTH_CONNECT, externalId = "b")
        val nap = SleepEntity(id = 3, startMillis = 14 * h, endMillis = 15 * h, zoneId = "UTC", localDate = 0, durationMinutes = 60, source = DataSource.HEALTH_CONNECT, externalId = "c")
        val result = SleepDeduplicator.dedupe(listOf(a, b, nap), listOf(DataSource.HEALTH_CONNECT, DataSource.SAMSUNG_HEALTH))
        assertThat(result.map { it.id }).containsExactly(2L, 3L)
    }
}
