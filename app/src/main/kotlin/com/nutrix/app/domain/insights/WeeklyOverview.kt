package com.nutrix.app.domain.insights

import com.nutrix.app.domain.AnalyticsInput
import com.nutrix.app.domain.kcalIfComplete

/** How much of the analysis window each diary actually covers. Missing days are unknown, never zeros. */
data class DataCoverage(
    val windowDays: Int,
    val mealDays: Int,
    val drinkDays: Int,
    val sleepNights: Int,
    val weightDays: Int,
    /** Days where most meals carry a calorie estimate. */
    val estimatedDays: Int,
    val meals: Int,
    /** Meals with an explicit after-meal feeling, including "normal". */
    val ratedMeals: Int,
) {
    companion object {
        fun of(records: AnalyticsInput): DataCoverage {
            val range = records.from..records.to
            val meals = records.meals.filter { it.localDate in range }
            val byDay = meals.groupBy { it.localDate }
            return DataCoverage(
                windowDays = (records.to - records.from + 1).toInt(),
                mealDays = byDay.size,
                drinkDays = records.drinks.filter { it.localDate in range }.map { it.localDate }.toSet().size,
                // A night belongs to the morning it ends on, so the window's nights end one day later.
                sleepNights = records.sleeps.filter { it.localDate in (records.from + 1)..(records.to + 1) }.map { it.localDate }.toSet().size,
                weightDays = records.weights.filter { it.localDate in range }.map { it.localDate }.toSet().size,
                estimatedDays = byDay.values.count { l -> l.count { it.kcalIfComplete != null }.let { k -> k >= 2 && k >= l.size * 0.7 } },
                meals = meals.size,
                ratedMeals = meals.count { it.feelings.isNotEmpty() },
            )
        }
    }
}

/** The one thing that limits the observations most right now. */
sealed interface DataLimitation {
    data class FewMealDays(val days: Int, val of: Int) : DataLimitation
    data object NoSleep : DataLimitation
    data class FewEstimates(val days: Int, val of: Int) : DataLimitation
    data class FewRatedMeals(val rated: Int, val of: Int) : DataLimitation
    /** Nothing stands out: still, only what was recorded is counted. */
    data object OnlyRecorded : DataLimitation
}

/** One useful step inside the diary, never a health instruction. */
enum class DiaryAction { KEEP_RECORDING, LOG_MEALS, ADD_SLEEP, RATE_MEALS, ESTIMATE_MEALS, SET_WATER_GUIDELINE }

/** Offline weekly overview: up to three notable observations, one data limitation and one diary action. */
data class WeeklyOverview(val highlights: List<Insight>, val limitation: DataLimitation, val action: DiaryAction) {
    companion object {
        fun build(visible: List<Insight>, coverage: DataCoverage, hasWaterGoal: Boolean): WeeklyOverview {
            val highlights = visible.filterNot { it is Coverage }.take(3)
            val limitation = when {
                coverage.mealDays < coverage.windowDays / 2 -> DataLimitation.FewMealDays(coverage.mealDays, coverage.windowDays)
                coverage.sleepNights == 0 -> DataLimitation.NoSleep
                coverage.estimatedDays < coverage.mealDays / 2 -> DataLimitation.FewEstimates(coverage.estimatedDays, coverage.mealDays)
                coverage.meals > 0 && coverage.ratedMeals < coverage.meals * 0.3 -> DataLimitation.FewRatedMeals(coverage.ratedMeals, coverage.meals)
                else -> DataLimitation.OnlyRecorded
            }
            val action = when {
                limitation is DataLimitation.FewMealDays -> DiaryAction.LOG_MEALS
                limitation is DataLimitation.NoSleep -> DiaryAction.ADD_SLEEP
                !hasWaterGoal && coverage.drinkDays >= InsightEngine.MIN_DAYS -> DiaryAction.SET_WATER_GUIDELINE
                limitation is DataLimitation.FewEstimates -> DiaryAction.ESTIMATE_MEALS
                limitation is DataLimitation.FewRatedMeals -> DiaryAction.RATE_MEALS
                else -> DiaryAction.KEEP_RECORDING
            }
            return WeeklyOverview(highlights, limitation, action)
        }
    }
}
