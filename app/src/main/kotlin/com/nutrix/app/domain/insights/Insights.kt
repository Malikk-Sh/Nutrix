package com.nutrix.app.domain.insights

import com.nutrix.app.domain.ChartPoint

enum class InsightTopic { MEALS, NUTRITION, FLUID, SLEEP, WEIGHT, ACTIVITY, WELLBEING, DATA }

/** How an observation reads for the user's own routine. It never grades health. */
enum class InsightTone { POSITIVE, NEUTRAL, ATTENTION }

enum class EvidenceKind { LATE_BEFORE_SLEEP, LATE_CLOCK, LONG_GAP, SHORT_NIGHT, LATE_COFFEE, LARGE_DISCOMFORT, SYMPTOM_AFTER_GAP }

/**
 * One diary day behind a pattern, so the user can open it and check the claim. Stays on the phone:
 * it is never part of [Insight.facts].
 */
data class EvidenceDay(
    val day: Long,
    val kind: EvidenceKind,
    /** Minute of the day of the event (the last meal, the coffee, the symptom). */
    val clock: Int? = null,
    /** A duration in minutes: before sleep, the gap, the night. */
    val minutes: Int? = null,
    /** A size: kcal of the day, grams or kcal of the portion. */
    val value: Int? = null,
)

/**
 * A local observation about the user's own records over the analysis window.
 * Values are compared with the user's own days, never with population norms.
 */
sealed interface Insight {
    /** Stable identity: used to hide an observation and to cite it in the AI review. */
    val id: String
    val topic: InsightTopic
    val tone: InsightTone
    /** Observed days (or nights, measurements) behind the observation. */
    val days: Int
    /** Optional daily series for a small chart on the card. */
    val chart: List<ChartPoint> get() = emptyList()
    /** Personal links between two diaries rank above plain summaries. */
    val isLink: Boolean get() = false
    /** A pattern appears only when something recurs; summaries are there whenever data is. */
    val isPattern: Boolean get() = false
    /** The days behind a pattern, newest first. */
    val evidence: List<EvidenceDay> get() = emptyList()
    /** Which variant of the rule produced it; two windows are comparable only on the same basis. */
    val basis: String get() = DEFAULT_BASIS

    /** Compact, language-neutral facts for the optional AI review. Never includes free text from the diary. */
    fun facts(): Map<String, Any>

    companion object {
        const val DEFAULT_BASIS = "default"
    }
}

/** Distinct days behind a pattern: several episodes of one day count once. */
val Insight.evidenceDays: List<Long> get() = evidence.map { it.day }.distinct()

enum class RuleState { FOUND, NOT_FOUND, NOT_ENOUGH_DATA }

/** Whether a rule could be evaluated in a window and on which [basis] (null without enough data). */
data class RuleCheck(val state: RuleState, val basis: String?)

/** Everything one window says: what was found and how every rule fared. */
data class Analysis(val insights: List<Insight>, val checks: Map<String, RuleCheck>) {
    fun check(id: String): RuleCheck = checks[id] ?: RuleCheck(RuleState.NOT_ENOUGH_DATA, null)

    companion object {
        val EMPTY = Analysis(emptyList(), emptyMap())
    }
}

data class MealRhythm(
    override val days: Int,
    val firstMedian: Int,
    val lastMedian: Int,
    val windowMedianMinutes: Int,
    val firstSpreadMinutes: Int,
    val lateStartDays: Int,
    val mealsPerDay: Double,
    override val chart: List<ChartPoint>,
) : Insight {
    override val id = "meal_rhythm"
    override val topic = InsightTopic.MEALS
    override val tone = when {
        firstSpreadMinutes > 120 -> InsightTone.ATTENTION
        firstSpreadMinutes <= 60 -> InsightTone.POSITIVE
        else -> InsightTone.NEUTRAL
    }
    override fun facts() = mapOf(
        "days" to days, "first_meal_median" to clock(firstMedian), "last_meal_median" to clock(lastMedian),
        "eating_window_median_min" to windowMedianMinutes, "first_meal_spread_min" to firstSpreadMinutes,
        "days_first_meal_after_12" to lateStartDays, "meals_per_day" to round1(mealsPerDay),
    )
}

/** The last meal of the day comes shortly before sleep (or late in the evening without sleep data). */
data class LateMeals(
    override val days: Int,
    val lateDays: Int,
    val beforeSleep: Boolean,
    /** Sleep on late nights minus other nights, minutes; null without enough nights of each. */
    val sleepDifferenceMinutes: Int?,
    override val evidence: List<EvidenceDay> = emptyList(),
) : Insight {
    override val id = "late_meals"
    override val topic = InsightTopic.MEALS
    override val tone = InsightTone.ATTENTION
    override val isPattern = true
    override val basis get() = if (beforeSleep) BEFORE_SLEEP else AFTER_22
    override val isLink get() = sleepDifferenceMinutes != null
    companion object {
        /** With sleep data: less than two hours between the last meal and sleep. */
        const val BEFORE_SLEEP = "before_sleep_2h"
        /** Without sleep data: the last meal after 22:00. */
        const val AFTER_22 = "after_22_00"
    }

    override fun facts() = buildMap<String, Any> {
        put("days", days); put("late_days", lateDays)
        put("rule", if (beforeSleep) "last_meal_less_than_2h_before_sleep" else "last_meal_after_22_00")
        sleepDifferenceMinutes?.let { put("sleep_on_late_nights_vs_other_min", it) }
    }
}

data class LongGaps(
    override val days: Int,
    val gapDays: Int,
    val longestGapMedianMinutes: Int,
    override val evidence: List<EvidenceDay> = emptyList(),
) : Insight {
    override val isPattern = true
    override val id = "long_gaps"
    override val topic = InsightTopic.MEALS
    override val tone = InsightTone.NEUTRAL
    override fun facts() = mapOf("days" to days, "days_with_gap_over_6h" to gapDays, "longest_gap_median_min" to longestGapMedianMinutes)
}

/** Averages over days where most meals carry a calorie estimate. */
data class NutritionSummary(
    override val days: Int,
    val kcal: Int,
    val protein: Int?,
    val fat: Int?,
    val carbs: Int?,
    /** Share of the day's estimated calories eaten after 18:00. */
    val eveningShare: Double,
) : Insight {
    override val id = "nutrition"
    override val topic = InsightTopic.NUTRITION
    override val tone = if (eveningShare >= 0.5) InsightTone.ATTENTION else InsightTone.NEUTRAL
    override fun facts() = buildMap<String, Any> {
        put("days_with_estimates", days); put("kcal_per_day_avg", kcal)
        protein?.let { put("protein_g_per_day_avg", it) }
        fat?.let { put("fat_g_per_day_avg", it) }
        carbs?.let { put("carbs_g_per_day_avg", it) }
        put("share_of_kcal_after_18_00", round2(eveningShare))
    }
}

/** Estimated calories on the day after a short night compared with other days. */
data class SleepAndEating(
    override val days: Int,
    val shortNights: Int,
    val shortThresholdMinutes: Int,
    val kcalDifference: Int,
    override val evidence: List<EvidenceDay> = emptyList(),
) : Insight {
    override val isPattern = true
    override val id = "sleep_eating"
    override val topic = InsightTopic.NUTRITION
    override val tone = if (kcalDifference > 0) InsightTone.ATTENTION else InsightTone.NEUTRAL
    override val isLink = true
    override fun facts() = mapOf(
        "days" to days, "short_nights" to shortNights, "short_night_below_min" to shortThresholdMinutes,
        "kcal_next_day_after_short_vs_other" to kcalDifference,
    )
}

data class FluidSummary(
    override val days: Int,
    val medianMl: Int,
    val goalMl: Int?,
    val goalDays: Int,
    /** Median share of the day's fluid drunk before 14:00. */
    val morningShare: Double,
    override val chart: List<ChartPoint>,
) : Insight {
    override val id = "fluid"
    override val topic = InsightTopic.FLUID
    override val tone = when {
        goalMl == null -> InsightTone.NEUTRAL
        goalDays >= days * 0.7 -> InsightTone.POSITIVE
        goalDays < days * 0.4 -> InsightTone.ATTENTION
        else -> InsightTone.NEUTRAL
    }
    override fun facts() = buildMap<String, Any> {
        put("days", days); put("fluid_ml_median", medianMl); put("share_before_14_00_median", round2(morningShare))
        goalMl?.let { put("user_guideline_ml", it); put("days_guideline_reached", goalDays) }
    }
}

/** Nights after coffee late in the day compared with other nights. */
data class LateCaffeine(
    override val days: Int,
    val lateDays: Int,
    val bedtimeShiftMinutes: Int,
    val sleepDifferenceMinutes: Int,
    override val evidence: List<EvidenceDay> = emptyList(),
) : Insight {
    override val isPattern = true
    override val id = "late_caffeine"
    override val topic = InsightTopic.SLEEP
    override val tone = InsightTone.ATTENTION
    override val isLink = true
    override fun facts() = mapOf(
        "nights" to days, "nights_after_coffee_after_16_00" to lateDays,
        "bedtime_later_by_min" to bedtimeShiftMinutes, "sleep_vs_other_nights_min" to sleepDifferenceMinutes,
    )
}

data class SleepRhythm(
    override val days: Int,
    val durationMedianMinutes: Int,
    val bedtimeMedian: Int,
    val bedtimeSpreadMinutes: Int,
    /** Weekend wake-up minus weekday wake-up, minutes; null without enough of both. */
    val weekendShiftMinutes: Int?,
    override val chart: List<ChartPoint>,
) : Insight {
    override val id = "sleep_rhythm"
    override val topic = InsightTopic.SLEEP
    override val tone = when {
        bedtimeSpreadMinutes > 90 || (weekendShiftMinutes ?: 0) >= 90 -> InsightTone.ATTENTION
        bedtimeSpreadMinutes <= 45 && (weekendShiftMinutes ?: 0) < 60 -> InsightTone.POSITIVE
        else -> InsightTone.NEUTRAL
    }
    override fun facts() = buildMap<String, Any> {
        put("nights", days); put("sleep_median_min", durationMedianMinutes); put("bedtime_median", clock(bedtimeMedian))
        put("bedtime_spread_min", bedtimeSpreadMinutes)
        weekendShiftMinutes?.let { put("weekend_wake_later_by_min", it) }
    }
}

data class WeightTrend(
    override val days: Int,
    val spanDays: Int,
    val kgPerWeek: Double,
    /** Signed distance to the user's own target: negative means the target is lower. */
    val toTargetKg: Double?,
    override val chart: List<ChartPoint>,
) : Insight {
    override val id = "weight_trend"
    override val topic = InsightTopic.WEIGHT
    override val tone = when {
        toTargetKg == null || kotlin.math.abs(toTargetKg) < 0.3 || kotlin.math.abs(kgPerWeek) < 0.1 -> InsightTone.NEUTRAL
        (toTargetKg < 0) == (kgPerWeek < 0) -> InsightTone.POSITIVE
        else -> InsightTone.ATTENTION
    }
    override fun facts() = buildMap<String, Any> {
        put("measurement_days", days); put("span_days", spanDays); put("trend_kg_per_week", round2(kgPerWeek))
        toTargetKg?.let { put("user_target_minus_current_kg", round1(it)) }
    }
}

data class StepsTrend(
    override val days: Int,
    val medianSteps: Int,
    val recentAverage: Int,
    val earlierAverage: Int,
    override val chart: List<ChartPoint>,
) : Insight {
    override val id = "steps"
    override val topic = InsightTopic.ACTIVITY
    val change: Double get() = if (earlierAverage > 0) recentAverage / earlierAverage.toDouble() - 1 else 0.0
    override val tone = when {
        change <= -0.2 -> InsightTone.ATTENTION
        change >= 0.2 -> InsightTone.POSITIVE
        else -> InsightTone.NEUTRAL
    }
    override fun facts() = mapOf(
        "days" to days, "steps_median" to medianSteps, "last_7_days_avg" to recentAverage, "earlier_days_avg" to earlierAverage,
    )
}

/** Unpleasant feelings after the user's larger portions compared with their other meals. */
data class MealDiscomfort(
    override val days: Int,
    val largeWith: Int,
    val largeTotal: Int,
    val otherWith: Int,
    val otherTotal: Int,
    val largeFromGrams: Int?,
    val largeFromKcal: Int?,
    override val evidence: List<EvidenceDay> = emptyList(),
) : Insight {
    override val isPattern = true
    override val id = "meal_discomfort"
    override val basis get() = if (largeFromGrams != null) BY_GRAMS else BY_KCAL
    override val topic = InsightTopic.WELLBEING
    override val tone = InsightTone.ATTENTION
    override val isLink = true
    companion object {
        const val BY_GRAMS = "portion_grams"
        const val BY_KCAL = "portion_kcal"
    }

    override fun facts() = buildMap<String, Any> {
        put("large_portions_with_discomfort", largeWith); put("large_portions", largeTotal)
        put("other_meals_with_discomfort", otherWith); put("other_meals", otherTotal)
        largeFromGrams?.let { put("large_portion_from_g", it) }
        largeFromKcal?.let { put("large_portion_from_kcal", it) }
    }
}

/** Weakness-type episodes recorded long after the last meal, against how often such gaps occur. */
data class SymptomsAfterGaps(
    override val days: Int,
    val episodes: Int,
    val afterGap: Int,
    val baselineShare: Double,
    override val evidence: List<EvidenceDay> = emptyList(),
) : Insight {
    override val isPattern = true
    override val id = "symptoms_after_gaps"
    override val topic = InsightTopic.WELLBEING
    override val tone = InsightTone.ATTENTION
    override val isLink = true
    override fun facts() = mapOf(
        "episodes_weakness_dizziness_tremor_sweating" to episodes, "episodes_over_5h_after_meal" to afterGap,
        "share_of_daytime_over_5h_after_meal" to round2(baselineShare),
    )
}

data class Coverage(override val days: Int, val windowDays: Int) : Insight {
    override val id = "coverage"
    override val topic = InsightTopic.DATA
    override val tone = InsightTone.NEUTRAL
    override fun facts() = mapOf("days_with_entries" to days, "window_days" to windowDays)
}

internal fun clock(minutes: Int): String {
    val m = ((minutes % 1440) + 1440) % 1440
    return String.format(java.util.Locale.ROOT, "%02d:%02d", m / 60, m % 60)
}

private fun round1(v: Double) = kotlin.math.round(v * 10) / 10
private fun round2(v: Double) = kotlin.math.round(v * 100) / 100
