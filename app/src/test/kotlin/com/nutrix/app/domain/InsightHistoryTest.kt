package com.nutrix.app.domain

import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.model.MealType
import com.nutrix.app.data.repository.InsightReport
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.domain.insights.DataCoverage
import com.nutrix.app.domain.insights.FluidSummary
import com.nutrix.app.domain.insights.Insight
import com.nutrix.app.domain.insights.InsightComparison
import com.nutrix.app.domain.insights.InsightEngine
import com.nutrix.app.domain.insights.InsightInput
import com.nutrix.app.domain.insights.InsightTopic
import com.nutrix.app.domain.insights.LateMeals
import com.nutrix.app.domain.insights.LongGaps
import com.nutrix.app.domain.insights.MealRhythm
import com.nutrix.app.domain.insights.Recurrence
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

    private fun meal(day: Long, h: Int, m: Int = 0) = MealEntity(
        epochMillis = LocalDate.ofEpochDay(day).atTime(LocalTime.of(h, m)).toInstant(ZoneOffset.UTC).toEpochMilli(),
        zoneId = "UTC", localDate = day, mealType = MealType.OTHER,
    )

    /** Analyses both windows from one list of records, as the repository does. */
    private fun report(meals: List<MealEntity>, interests: Set<InsightTopic> = emptySet()): InsightReport {
        val records = AnalyticsInput(window.first, window.last, meals, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val now = InsightEngine.analyze(InsightInput(records, null, null))
        val before = InsightEngine.analyze(InsightInput(records.copy(from = earlier.first, to = earlier.last), null, null))
        return InsightReport(window.first, window.last, now, emptySet(), DataCoverage.of(records), false, before, interests)
    }

    @Test fun `the previous window ends where the analysis window starts`() {
        assertThat(earlier.last).isEqualTo(window.first - 1)
        assertThat(earlier.count()).isEqualTo(InsightEngine.WINDOW_DAYS)
    }

    @Test fun `a pattern seen in both windows repeats, a pattern seen only now is new`() {
        // Late dinners on every other day in both windows; long gaps only in the latest weeks.
        val meals = (earlier.first..window.last).flatMap { d ->
            val late = d % 2 == 0L
            val gap = d >= window.first && d % 3 == 0L
            val last = meal(d, if (late) 22 else 19)
            // Gaps of at most 6 hours, except a 7-hour one on the latest weeks' gap days.
            if (gap) listOf(meal(d, 8), meal(d, 15), last) else listOf(meal(d, 8), meal(d, 12), meal(d, 16), last)
        }
        val r = report(meals)
        val late = r.all.filterIsInstance<LateMeals>().single()
        val gaps = r.all.filterIsInstance<LongGaps>().single()
        assertThat(r.history(late).recurrence).isEqualTo(Recurrence.REPEATS)
        assertThat(r.history(late).previous).isInstanceOf(LateMeals::class.java)
        assertThat(r.history(gaps).recurrence).isEqualTo(Recurrence.NEW)
        assertThat(r.history(gaps).previous).isNull()
        assertThat(r.faded).isEmpty()
        // The cloud review learns which observations are only preliminary.
        val sent = InsightReviewPayload.build(r).getValue("observations").jsonArray.map { it.jsonObject }
            .associate { it.getValue("id").jsonPrimitive.content to it.getValue("compared_with_previous_4_weeks").jsonPrimitive.content }
        assertThat(sent["late_meals"]).isEqualTo("repeats")
        assertThat(sent["long_gaps"]).isEqualTo("new")
    }

    @Test fun `a pattern that stopped is listed only when the latest weeks had the data to show it`() {
        val before = (earlier.first..earlier.last).flatMap { d -> listOf(meal(d, 9), meal(d, if (d % 2 == 0L) 22 else 19, 30)) }
        val after = (window.first..window.last).flatMap { d -> listOf(meal(d, 9), meal(d, 19, 30)) }
        val faded = report(before + after).faded
        assertThat(faded.map { it.id }).containsExactly("late_meals")
        // Without meals in the latest weeks nothing can be said about the pattern.
        assertThat(report(before).faded).isEmpty()
    }

    @Test fun `a summary whose tone changed is marked as changed`() {
        // Breakfast time wanders in the previous weeks and settles in the latest ones.
        val before = (earlier.first..earlier.last).flatMap { d -> listOf(meal(d, if (d % 2 == 0L) 7 else 12), meal(d, 19)) }
        val after = (window.first..window.last).flatMap { d -> listOf(meal(d, 9), meal(d, 19)) }
        val r = report(before + after)
        val rhythm = r.all.filterIsInstance<MealRhythm>().single()
        assertThat(r.history(rhythm).recurrence).isEqualTo(Recurrence.CHANGED)
        assertThat((r.history(rhythm).previous as MealRhythm).firstSpreadMinutes).isGreaterThan(120)
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
