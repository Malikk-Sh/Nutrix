package com.nutrix.app.reminders

import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.datastore.ReminderConfig
import com.nutrix.app.core.datastore.ReminderMode
import com.nutrix.app.core.datastore.ReminderType
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.model.MealType
import com.nutrix.app.database.entity.DrinkEntity
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.database.entity.WeightEntity
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class RoutineTest {
    private val zone = "UTC"
    // 2026-10-29 is a Thursday.
    private val today = LocalDate.of(2026, 10, 29).toEpochDay()
    private val window = RoutineLearner.window(today)
    private val days = window.toList()

    private fun at(day: Long, h: Int, m: Int = 0) = LocalDate.ofEpochDay(day).atTime(LocalTime.of(h, m)).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun meal(day: Long, h: Int, m: Int = 0) = MealEntity(epochMillis = at(day, h, m), zoneId = zone, localDate = day, mealType = MealType.OTHER)
    private fun drink(day: Long, h: Int, ml: Int) = DrinkEntity(epochMillis = at(day, h), zoneId = zone, localDate = day, drinkType = DrinkType.WATER, volumeMl = ml)
    private fun weekend(day: Long) = Routine.isWeekend(LocalDate.ofEpochDay(day))

    @Test
    fun `usual meal times are learned separately for weekdays and weekends`() {
        val meals = days.flatMap { d ->
            if (weekend(d)) listOf(meal(d, 10, 30), meal(d, 15), meal(d, 20))
            else listOf(meal(d, 8, if (d % 2 == 0L) 0 else 20), meal(d, 13, 30), meal(d, 19), meal(d, 1))
        }
        val routine = RoutineLearner.learn(meals, emptyList(), emptyList(), window)
        assertThat(routine.weekdayMeals.map { it.minute }).containsExactly(8 * 60 + 10, 13 * 60 + 30, 19 * 60).inOrder()
        assertThat(routine.weekendMeals.map { it.minute }).containsExactly(10 * 60 + 30, 15 * 60, 20 * 60).inOrder()
    }

    @Test
    fun `occasional snacks do not become a habit or glue meals together`() {
        val meals = days.flatMapIndexed { i, d ->
            listOf(meal(d, 9), meal(d, 13)) + if (i % 5 == 0) listOf(meal(d, 10, 30), meal(d, 11, 45)) else emptyList()
        }
        val routine = RoutineLearner.learn(meals, emptyList(), emptyList(), window)
        assertThat(routine.weekdayMeals.map { it.minute }).containsExactly(9 * 60, 13 * 60).inOrder()
    }

    @Test
    fun `less than a week of records learns nothing`() {
        val routine = RoutineLearner.learn(days.takeLast(5).map { meal(it, 9) }, days.takeLast(5).map { drink(it, 9, 300) }, emptyList(), window)
        assertThat(routine.weekdayMeals).isEmpty()
        assertThat(routine.fluidCurve).isEmpty()
        assertThat(AdaptivePlan.learned(ReminderType.MEAL, routine)).isFalse()
    }

    @Test
    fun `usual weighing time and drinking pace are learned`() {
        val weights = days.take(10).map { WeightEntity(epochMillis = at(it, 7, 40), zoneId = zone, localDate = it, weightKg = 60.0) }
        val drinks = days.flatMap { listOf(drink(it, 9, 400), drink(it, 13, 400), drink(it, 19, 800)) }
        val routine = RoutineLearner.learn(emptyList(), drinks, weights, window)
        assertThat(routine.weighMinute).isEqualTo(7 * 60 + 40)
        assertThat(routine.fluidMedianMl).isEqualTo(1600)
        assertThat(routine.fluidShareAt(14 * 60)).isWithin(1e-9).of(0.5)
        assertThat(routine.fluidShareAt(8 * 60)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `meal reminder comes after the usual time only when nothing was logged`() {
        val routine = Routine(weekdayMeals = listOf(MealSlot(13 * 60, 20)), weekendMeals = listOf(MealSlot(15 * 60, 8)))
        val thursday = LocalDate.of(2026, 10, 29)
        val config = ReminderConfig(ReminderType.MEAL, enabled = true, times = listOf(12 * 60), adaptive = true)
        assertThat(AdaptivePlan.timesFor(config, routine, 0, thursday)).containsExactly(13 * 60 + 45)
        assertThat(AdaptivePlan.timesFor(config, routine, 0, thursday.plusDays(2))).containsExactly(15 * 60 + 45)
        val slot = AdaptivePlan.slotFor(routine, thursday, 13 * 60 + 45)!!
        assertThat(AdaptivePlan.mealMissing(slot, listOf(8 * 60), 13 * 60 + 45)).isTrue()
        // An early lunch still counts for the usual one.
        assertThat(AdaptivePlan.mealMissing(slot, listOf(11 * 60 + 40), 13 * 60 + 45)).isFalse()
        // A late or snoozed reminder still checks the meal it belongs to.
        assertThat(AdaptivePlan.slotFor(routine, thursday, 14 * 60 + 30)).isEqualTo(slot)
        assertThat(AdaptivePlan.slotFor(routine, thursday, 12 * 60)).isNull()
        // Without the adaptive switch the user's own times stay.
        assertThat(AdaptivePlan.timesFor(config.copy(adaptive = false), routine, 0, thursday)).containsExactly(12 * 60)
    }

    @Test
    fun `without learned routine the configured times stay`() {
        val config = ReminderConfig(ReminderType.WEIGHT, enabled = true, times = listOf(8 * 60 + 30), adaptive = true)
        assertThat(AdaptivePlan.timesFor(config, Routine(), 0, LocalDate.of(2026, 10, 29))).containsExactly(8 * 60 + 30)
        assertThat(AdaptivePlan.timesFor(config, Routine(weighMinute = 7 * 60), 0, LocalDate.of(2026, 10, 29))).containsExactly(7 * 60 + 30)
    }

    @Test
    fun `water reminds only when clearly behind the usual pace`() {
        val routine = Routine(fluidCurve = (0..24).map { (it / 24.0) }, fluidMedianMl = 1600)
        val expected = AdaptivePlan.expectedFluid(routine, goalMl = 2400, minute = 12 * 60)!!
        assertThat(expected).isEqualTo(1200)
        assertThat(AdaptivePlan.fluidBehind(totalMl = 700, expectedMl = expected, dayTargetMl = 2400)).isTrue()
        assertThat(AdaptivePlan.fluidBehind(totalMl = 1000, expectedMl = expected, dayTargetMl = 2400)).isFalse()
        // Without a guideline the user's usual day is the reference.
        assertThat(AdaptivePlan.expectedFluid(routine, goalMl = null, minute = 12 * 60)).isEqualTo(800)
        assertThat(AdaptivePlan.expectedFluid(Routine(), goalMl = 2000, minute = 12 * 60)).isNull()
    }

    @Test
    fun `after three swiped reminders a type comes once a day until restored`() {
        val config = ReminderConfig(ReminderType.WATER, enabled = true, mode = ReminderMode.INTERVAL, intervalMinutes = 120,
            windowStartMinutes = 10 * 60, windowEndMinutes = 20 * 60, adaptive = true)
        val date = LocalDate.of(2026, 10, 29)
        assertThat(AdaptivePlan.timesFor(config, Routine(), 2, date)).hasSize(6)
        assertThat(AdaptivePlan.timesFor(config, Routine(), 3, date)).containsExactly(10 * 60)
        val now = ZonedDateTime.of(LocalDateTime.of(2026, 10, 29, 11, 0), ZoneId.of("UTC"))
        val next = ReminderPlanner.nextTrigger(config, QuietHours(false, 0, 0), now) { AdaptivePlan.timesFor(config, Routine(), 3, it) }!!
        assertThat(next.toLocalDate()).isEqualTo(date.plusDays(1))
        assertThat(next.hour).isEqualTo(10)
    }

    @Test
    fun `after-meal question is planned only for meals logged around eating time`() {
        val meal = 1_000_000_000L
        assertThat(AfterMealPlan.delayMillis(meal, meal + 10 * 60_000L, 90)).isEqualTo(80 * 60_000L)
        // Logged after the moment the question would come, or planned for later: no question.
        assertThat(AfterMealPlan.delayMillis(meal, meal + 95 * 60_000L, 90)).isNull()
        assertThat(AfterMealPlan.delayMillis(meal, meal - 60 * 60_000L, 90)).isNull()
        assertThat(AfterMealPlan.mayAsk(100, null, 0)).isTrue()
        assertThat(AfterMealPlan.mayAsk(100, 100, 0)).isFalse()
        assertThat(AfterMealPlan.mayAsk(100, 99, 0)).isTrue()
        // Ignored several times in a row: every third day at most.
        assertThat(AfterMealPlan.mayAsk(100, 99, 3)).isFalse()
        assertThat(AfterMealPlan.mayAsk(100, 97, 3)).isTrue()
    }
}
