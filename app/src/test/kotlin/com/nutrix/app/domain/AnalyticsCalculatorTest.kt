package com.nutrix.app.domain

import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.model.AfterMealFeeling
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.model.Satiety
import com.nutrix.app.core.model.SymptomType
import com.nutrix.app.database.entity.DrinkEntity
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.database.entity.SleepEntity
import com.nutrix.app.database.entity.SymptomEntity
import com.nutrix.app.database.entity.WeightEntity
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class AnalyticsCalculatorTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val base = LocalDate.of(2026, 10, 1)

    private fun millis(day: Long, time: LocalTime) = LocalDate.ofEpochDay(day).atTime(time).atZone(zone).toInstant().toEpochMilli()

    private fun meal(day: Long, appetite: Int? = null, satiety: Satiety? = null, feelings: List<AfterMealFeeling> = emptyList()) =
        MealEntity(epochMillis = millis(day, LocalTime.NOON), zoneId = zone.id, localDate = day, mealType = MealType.LUNCH, appetite = appetite, satiety = satiety, feelings = feelings)

    private fun drink(day: Long, ml: Int) = DrinkEntity(epochMillis = millis(day, LocalTime.of(10, 0)), zoneId = zone.id, localDate = day, drinkType = DrinkType.WATER, volumeMl = ml)

    private fun symptom(day: Long, type: SymptomType) = SymptomEntity(epochMillis = millis(day, LocalTime.of(15, 0)), zoneId = zone.id, localDate = day, type = type)

    private fun sleep(day: Long, start: LocalTime, hours: Int): SleepEntity {
        val startMillis = millis(day - 1, start)
        val end = startMillis + hours * 3_600_000L
        return SleepEntity(startMillis = startMillis, endMillis = end, zoneId = zone.id, localDate = day, durationMinutes = hours * 60)
    }

    private fun input(days: Int, meals: List<MealEntity> = emptyList(), drinks: List<DrinkEntity> = emptyList(), symptoms: List<SymptomEntity> = emptyList(), sleeps: List<SleepEntity> = emptyList(), weights: List<WeightEntity> = emptyList()) =
        AnalyticsInput(base.toEpochDay(), base.toEpochDay() + days - 1, meals, drinks, weights, symptoms, sleeps, emptyList())

    @Test
    fun `days without any entries are not counted as zero`() {
        val d0 = base.toEpochDay()
        val series = AnalyticsCalculator.series(input(10, meals = listOf(meal(d0), meal(d0), meal(d0 + 2))))
        val meals = series.getValue(Metric.MEALS).points
        assertThat(meals.map { it.epochDay }).containsExactly(d0, d0 + 2)
        assertThat(meals.map { it.value }).containsExactly(2.0, 1.0)
    }

    @Test
    fun `fluid includes recorded drinks without inventing zero on meal only days`() {
        val d0 = base.toEpochDay()
        val series = AnalyticsCalculator.series(input(5, meals = listOf(meal(d0 + 1)), drinks = listOf(drink(d0, 250), drink(d0, 300))))
        val fluid = series.getValue(Metric.FLUID).points.associate { it.epochDay to it.value }
        assertThat(fluid[d0]).isEqualTo(550.0)
        assertThat(fluid).doesNotContainKey(d0 + 1)
        assertThat(fluid).doesNotContainKey(d0 + 2)
    }

    @Test
    fun `unassessed feelings remain unknown and explicit normal assessment is zero`() {
        val d0 = base.toEpochDay()
        val series = AnalyticsCalculator.series(input(3, meals = listOf(meal(d0), meal(d0 + 1, feelings = listOf(AfterMealFeeling.NORMAL)))))
        val nausea = series.getValue(Metric.NAUSEA).points
        assertThat(nausea.map { it.epochDay }).containsExactly(d0 + 1)
        assertThat(nausea.single().value).isEqualTo(0.0)
        assertThat(series.getValue(Metric.EARLY_SATIETY).points).isEmpty()
    }

    @Test
    fun `zero step measurement is retained while missing measurement is skipped`() {
        val d0 = base.toEpochDay()
        val data = input(3).copy(activity = listOf(DailyActivity(d0, steps = 0), DailyActivity(d0 + 1)))
        val points = AnalyticsCalculator.series(data).getValue(Metric.STEPS).points
        assertThat(points.map { it.epochDay }).containsExactly(d0)
        assertThat(points.single().value).isEqualTo(0.0)
    }

    @Test
    fun `early satiety and nausea combine meals and symptoms`() {
        val d0 = base.toEpochDay()
        val series = AnalyticsCalculator.series(
            input(
                3,
                meals = listOf(meal(d0, satiety = Satiety.QUICK, feelings = listOf(AfterMealFeeling.NAUSEA))),
                symptoms = listOf(symptom(d0, SymptomType.EARLY_SATIETY), symptom(d0, SymptomType.NAUSEA), symptom(d0, SymptomType.NAUSEA)),
            ),
        )
        assertThat(series.getValue(Metric.EARLY_SATIETY).points.single().value).isEqualTo(2.0)
        assertThat(series.getValue(Metric.NAUSEA).points.single().value).isEqualTo(3.0)
    }

    @Test
    fun `bedtime around midnight is averaged on an evening scale`() {
        val d0 = base.toEpochDay()
        val series = AnalyticsCalculator.series(input(2, sleeps = listOf(sleep(d0, LocalTime.of(23, 30), 7), sleep(d0 + 1, LocalTime.of(0, 30), 7))))
        val bed = series.getValue(Metric.BEDTIME).points.map { it.value }
        // 23:30 -> 330 min after 18:00, 00:30 -> 390 min
        assertThat(bed).containsExactly(330.0, 390.0).inOrder()
    }

    @Test
    fun `correlation needs enough pairs`() {
        val d0 = base.toEpochDay()
        val few = (0 until 3).flatMap { listOf(meal(d0 + it, appetite = it)) }
        val sleeps = (0 until 3).map { sleep(d0 + it, LocalTime.of(23, 0), 6 + it) }
        val result = AnalyticsCalculator.correlations(input(10, meals = few, sleeps = sleeps)).first { it.comparison == Comparison.APPETITE_VS_SLEEP }
        assertThat(result.sufficient).isFalse()
        assertThat(result.pairs).hasSize(3)
    }

    @Test
    fun `perfectly related series give r of one`() {
        val d0 = base.toEpochDay()
        val meals = (0 until 8).map { meal(d0 + it, appetite = it % 5) }
        val sleeps = (0 until 8).map { sleep(d0 + it, LocalTime.of(23, 0), 5 + (it % 5)) }
        val result = AnalyticsCalculator.correlations(input(10, meals = meals, sleeps = sleeps)).first { it.comparison == Comparison.APPETITE_VS_SLEEP }
        assertThat(result.r).isWithin(1e-9).of(1.0)
        assertThat(result.strength).isEqualTo(Strength.STRONG)
    }

    @Test
    fun `pearson returns null for constant data`() {
        val pairs = List(10) { it.toDouble() to 3.0 }
        assertThat(AnalyticsCalculator.pearson(pairs)).isNull()
    }

    @Test
    fun `pearson detects negative relation`() {
        val pairs = List(10) { it.toDouble() to -2.0 * it + 1 }
        assertThat(AnalyticsCalculator.pearson(pairs)).isWithin(1e-9).of(-1.0)
    }
}
