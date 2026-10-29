package com.nutrix.app.domain

import com.google.common.truth.Truth.assertThat
import com.nutrix.app.domain.insights.Coverage
import com.nutrix.app.domain.insights.DataCoverage
import com.nutrix.app.domain.insights.DataLimitation
import com.nutrix.app.domain.insights.DiaryAction
import com.nutrix.app.domain.insights.FluidSummary
import com.nutrix.app.domain.insights.LongGaps
import com.nutrix.app.domain.insights.MealRhythm
import com.nutrix.app.domain.insights.WeeklyOverview
import org.junit.Test

class WeeklyOverviewTest {
    private val full = DataCoverage(windowDays = 28, mealDays = 26, drinkDays = 25, sleepNights = 20, weightDays = 10,
        estimatedDays = 20, meals = 70, ratedMeals = 40)
    private val rhythm = MealRhythm(26, 540, 1230, 690, 150, 6, 2.7, emptyList())
    private val fluid = FluidSummary(25, 1300, null, 0, 0.3, emptyList())

    @Test fun `few meal days come first and suggest logging meals`() {
        val o = WeeklyOverview.build(listOf(rhythm), full.copy(mealDays = 9), hasWaterGoal = true)
        assertThat(o.limitation).isEqualTo(DataLimitation.FewMealDays(9, 28))
        assertThat(o.action).isEqualTo(DiaryAction.LOG_MEALS)
    }

    @Test fun `missing sleep is named before estimates and ratings`() {
        val o = WeeklyOverview.build(listOf(rhythm), full.copy(sleepNights = 0, estimatedDays = 2, ratedMeals = 0), hasWaterGoal = true)
        assertThat(o.limitation).isEqualTo(DataLimitation.NoSleep)
        assertThat(o.action).isEqualTo(DiaryAction.ADD_SLEEP)
    }

    @Test fun `few rated meals ask for after-meal ratings including normal`() {
        val o = WeeklyOverview.build(listOf(rhythm), full.copy(ratedMeals = 5), hasWaterGoal = true)
        assertThat(o.limitation).isEqualTo(DataLimitation.FewRatedMeals(5, 70))
        assertThat(o.action).isEqualTo(DiaryAction.RATE_MEALS)
    }

    @Test fun `drinks without the user's guideline suggest setting one`() {
        val o = WeeklyOverview.build(listOf(fluid), full, hasWaterGoal = false)
        assertThat(o.limitation).isEqualTo(DataLimitation.OnlyRecorded)
        assertThat(o.action).isEqualTo(DiaryAction.SET_WATER_GUIDELINE)
    }

    @Test fun `at most three highlights and never the coverage hint`() {
        val o = WeeklyOverview.build(listOf(Coverage(5, 28), rhythm, fluid, LongGaps(20, 6, 400), rhythm.copy(days = 1)), full, true)
        assertThat(o.highlights).hasSize(3)
        assertThat(o.highlights.none { it is Coverage }).isTrue()
        assertThat(o.action).isEqualTo(DiaryAction.KEEP_RECORDING)
    }
}
