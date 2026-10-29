package com.nutrix.app.domain

import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.model.AfterMealFeeling
import com.nutrix.app.core.model.MealType
import com.nutrix.app.data.repository.InsightReport
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.database.entity.SleepEntity
import com.nutrix.app.domain.insights.DataCoverage
import com.nutrix.app.domain.insights.FluidSummary
import com.nutrix.app.domain.insights.Insight
import com.nutrix.app.domain.insights.InsightComparison
import com.nutrix.app.domain.insights.InsightEngine
import com.nutrix.app.domain.insights.InsightInput
import com.nutrix.app.domain.insights.InsightTopic
import com.nutrix.app.domain.insights.LateMeals
import com.nutrix.app.domain.insights.LongGaps
import com.nutrix.app.domain.insights.MealDiscomfort
import com.nutrix.app.domain.insights.MealRhythm
import com.nutrix.app.domain.insights.Recurrence
import com.nutrix.app.domain.insights.RuleState
import com.nutrix.app.domain.insights.evidenceDays
import com.nutrix.app.feature.insights.InsightReviewPayload
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class InsightHistoryTest {
    private val today = LocalDate.of(2026, 10, 29).toEpochDay()
    private val window = InsightEngine.window(today)
    private val earlier = InsightComparison.previousWindow(window)

    private fun at(day: Long, h: Int, m: Int = 0) = LocalDate.ofEpochDay(day).atTime(LocalTime.of(h, m)).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun meal(day: Long, h: Int, m: Int = 0, grams: Int? = null, feelings: List<AfterMealFeeling> = emptyList()) =
        MealEntity(epochMillis = at(day, h, m), zoneId = "UTC", localDate = day, mealType = MealType.OTHER, portionGrams = grams, feelings = feelings)

    /** A night that starts on [day] and belongs to the next morning. */
    private fun night(day: Long, h: Int, minutes: Int) =
        SleepEntity(startMillis = at(day, h), endMillis = at(day, h) + minutes * 60_000L, zoneId = "UTC", localDate = day + 1, durationMinutes = minutes)

    /** Analyses both windows from one list of records, as the repository does. */
    private fun report(meals: List<MealEntity>, sleeps: List<SleepEntity> = emptyList(), interests: Set<InsightTopic> = emptySet()): InsightReport {
        val records = AnalyticsInput(window.first, window.last, meals, emptyList(), emptyList(), emptyList(), sleeps, emptyList())
        val now = InsightEngine.evaluate(InsightInput(records, null, null))
        val before = InsightEngine.evaluate(InsightInput(records.copy(from = earlier.first, to = earlier.last), null, null))
        return InsightReport(window.first, window.last, now.insights, emptySet(), DataCoverage.of(records), false, before, now.checks, interests)
    }

    private fun days(range: LongRange) = range.toList()

    @Test fun `the previous window ends where the analysis window starts`() {
        assertThat(earlier.last).isEqualTo(window.first - 1)
        assertThat(earlier.count()).isEqualTo(InsightEngine.WINDOW_DAYS)
    }

    @Test fun `a pattern seen in both windows repeats, one the earlier weeks checked and did not see is new`() {
        // Late dinners on every other day in both windows; long gaps only in the latest weeks.
        val meals = days(earlier.first..window.last).flatMap { d ->
            val last = meal(d, if (d % 2 == 0L) 22 else 19)
            val gap = d >= window.first && d % 3 == 0L
            // Gaps of at most 6 hours, except a 7-hour one on the latest weeks' gap days.
            if (gap) listOf(meal(d, 8), meal(d, 15), last) else listOf(meal(d, 8), meal(d, 12), meal(d, 16), last)
        }
        val r = report(meals)
        val late = r.all.filterIsInstance<LateMeals>().single()
        val gaps = r.all.filterIsInstance<LongGaps>().single()
        assertThat(r.history(late).recurrence).isEqualTo(Recurrence.REPEATS)
        assertThat(r.history(late).previous).isInstanceOf(LateMeals::class.java)
        assertThat(r.previous.check("long_gaps").state).isEqualTo(RuleState.NOT_FOUND)
        assertThat(r.history(gaps).recurrence).isEqualTo(Recurrence.NEW)
        assertThat(r.faded).isEmpty()
        val sent = InsightReviewPayload.build(r).getValue("observations").jsonArray.map { it.jsonObject }
            .associate { it.getValue("id").jsonPrimitive.content to it["compared_with_previous_4_weeks"]?.jsonPrimitive?.content }
        assertThat(sent["late_meals"]).isEqualTo("repeats")
        assertThat(sent["long_gaps"]).isEqualTo("new")
    }

    @Test fun `without earlier data nothing is called new`() {
        val meals = days(window).flatMap { d -> listOf(meal(d, 9), meal(d, if (d % 2 == 0L) 22 else 19)) }
        val r = report(meals)
        val late = r.all.filterIsInstance<LateMeals>().single()
        assertThat(r.previous.check("late_meals").state).isEqualTo(RuleState.NOT_ENOUGH_DATA)
        assertThat(r.history(late).recurrence).isEqualTo(Recurrence.NO_BASELINE)
        // Summaries without an earlier value are not compared in the cloud request either.
        val sent = InsightReviewPayload.build(r).getValue("observations").jsonArray.map { it.jsonObject }.associateBy { it.getValue("id").jsonPrimitive.content }
        assertThat(sent.getValue("late_meals").getValue("compared_with_previous_4_weeks").jsonPrimitive.content).isEqualTo("no_baseline")
        assertThat(sent.getValue("meal_rhythm")["compared_with_previous_4_weeks"]).isNull()
    }

    @Test fun `a pattern is listed as not repeated only when the latest weeks could check it`() {
        val before = days(earlier).flatMap { d -> listOf(meal(d, 9), meal(d, if (d % 2 == 0L) 22 else 19, 30)) }
        val after = days(window).flatMap { d -> listOf(meal(d, 9), meal(d, 19, 30)) }
        assertThat(report(before + after).faded.map { it.id }).containsExactly("late_meals")
        assertThat(report(before).faded).isEmpty()
        // Two meals with an 8-hour gap before; one meal a day now: gaps cannot be measured, so nothing is claimed.
        val gapsBefore = days(earlier).flatMap { d -> listOf(meal(d, 8), meal(d, 16)) }
        val single = days(window).map { d -> meal(d, 13) }
        val r = report(gapsBefore + single)
        assertThat(r.previous.check("long_gaps").state).isEqualTo(RuleState.FOUND)
        assertThat(r.checks.getValue("long_gaps").state).isEqualTo(RuleState.NOT_ENOUGH_DATA)
        assertThat(r.faded).isEmpty()
    }

    @Test fun `a rule checked another way earlier is not compared`() {
        // Earlier: dinner at 22:00 with no sleep recorded (clock rule). Now: dinner at 20:00, sleep at 21:00 (sleep rule).
        val before = days(earlier).flatMap { d -> listOf(meal(d, 9), meal(d, if (d % 2 == 0L) 22 else 18)) }
        val after = days(window).flatMap { d -> listOf(meal(d, 9), meal(d, if (d % 2 == 0L) 20 else 17)) }
        val sleeps = days(window).map { d -> night(d, 21, 8 * 60) }
        val r = report(before + after, sleeps)
        val late = r.all.filterIsInstance<LateMeals>().single()
        assertThat(late.beforeSleep).isTrue()
        assertThat(r.previous.insights.filterIsInstance<LateMeals>().single().beforeSleep).isFalse()
        val history = r.history(late)
        assertThat(history.recurrence).isEqualTo(Recurrence.OTHER_BASIS)
        assertThat(history.previousBasis).isEqualTo(LateMeals.AFTER_22)
        assertThat(history.previous).isNull()
    }

    @Test fun `a summary whose tone changed is marked as changed`() {
        // Breakfast time wanders in the previous weeks and settles in the latest ones.
        val before = days(earlier).flatMap { d -> listOf(meal(d, if (d % 2 == 0L) 7 else 12), meal(d, 19)) }
        val after = days(window).flatMap { d -> listOf(meal(d, 9), meal(d, 19)) }
        val r = report(before + after)
        val rhythm = r.all.filterIsInstance<MealRhythm>().single()
        assertThat(r.history(rhythm).recurrence).isEqualTo(Recurrence.CHANGED)
        assertThat((r.history(rhythm).previous as MealRhythm).firstSpreadMinutes).isGreaterThan(120)
    }

    @Test fun `several episodes of one day count as one day`() {
        // Large uncomfortable portions four times on the same day, other meals small and fine.
        val same = window.last
        val meals = List(4) { i -> meal(same, 8 + i * 3, grams = 600, feelings = listOf(AfterMealFeeling.HEAVINESS)) } +
            days(window.first..window.first + 5).flatMap { d -> listOf(meal(d, 9, grams = 200, feelings = listOf(AfterMealFeeling.NORMAL)),
                meal(d, 19, grams = 250, feelings = listOf(AfterMealFeeling.NORMAL))) }
        val discomfort = report(meals).all.filterIsInstance<MealDiscomfort>().single()
        assertThat(discomfort.evidence).hasSize(4)
        assertThat(discomfort.evidenceDays).containsExactly(same)
    }

    @Test fun `chosen topics come first and keep the engine order inside`() {
        val fluid = FluidSummary(10, 1500, null, 0, 0.5, emptyList())
        val late = LateMeals(10, 5, beforeSleep = false, sleepDifferenceMinutes = null)
        val rhythm = MealRhythm(10, 540, 1170, 630, 30, 0, 3.0, emptyList())
        val all: List<Insight> = listOf(late, rhythm, fluid)
        fun order(interests: Set<InsightTopic>) =
            InsightReport(window.first, window.last, all, emptySet(), interests = interests).visible.map { it.id }
        assertThat(order(emptySet())).containsExactly("late_meals", "meal_rhythm", "fluid").inOrder()
        assertThat(order(setOf(InsightTopic.FLUID))).containsExactly("fluid", "late_meals", "meal_rhythm").inOrder()
        assertThat(InsightReport(window.first, window.last, all, setOf("fluid"), interests = setOf(InsightTopic.FLUID)).visible.map { it.id })
            .containsExactly("late_meals", "meal_rhythm").inOrder()
    }
}
