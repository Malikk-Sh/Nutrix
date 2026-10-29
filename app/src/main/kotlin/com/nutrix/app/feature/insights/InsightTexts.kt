package com.nutrix.app.feature.insights

import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.nutrix.app.R
import com.nutrix.app.core.model.WeightUnit
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.core.ui.icons.Bedtime
import com.nutrix.app.core.ui.icons.DirectionsRun
import com.nutrix.app.core.ui.icons.Healing
import com.nutrix.app.core.ui.icons.Insights
import com.nutrix.app.core.ui.icons.MonitorWeight
import com.nutrix.app.core.ui.icons.Restaurant
import com.nutrix.app.core.ui.icons.Schedule
import com.nutrix.app.core.ui.icons.WaterDrop
import com.nutrix.app.core.ui.theme.LocalCategoryColors
import com.nutrix.app.core.util.Units
import com.nutrix.app.domain.insights.Coverage
import com.nutrix.app.domain.insights.Change
import com.nutrix.app.domain.insights.EvidenceDay
import com.nutrix.app.domain.insights.EvidenceGroup
import com.nutrix.app.domain.insights.EvidenceKind
import com.nutrix.app.domain.insights.MeasureKind
import com.nutrix.app.domain.insights.FluidSummary
import com.nutrix.app.domain.insights.Insight
import com.nutrix.app.domain.insights.InsightTopic
import com.nutrix.app.domain.insights.LateCaffeine
import com.nutrix.app.domain.insights.LateMeals
import com.nutrix.app.domain.insights.LongGaps
import com.nutrix.app.domain.insights.MealDiscomfort
import com.nutrix.app.domain.insights.MealRhythm
import com.nutrix.app.domain.insights.NutritionSummary
import com.nutrix.app.domain.insights.SleepAndEating
import com.nutrix.app.domain.insights.SleepRhythm
import com.nutrix.app.domain.insights.StepsTrend
import com.nutrix.app.domain.insights.SymptomsAfterGaps
import com.nutrix.app.domain.insights.WeightTrend
import kotlin.math.abs
import kotlin.math.roundToInt

/** Localised wording of an observation: a title, short sentences and what it is based on. */
data class InsightText(val title: String, val lines: List<String>, val evidence: String?)

@Composable
fun topicIcon(topic: InsightTopic): ImageVector = when (topic) {
    InsightTopic.MEALS -> Icons.Rounded.Schedule
    InsightTopic.NUTRITION -> Icons.Rounded.Restaurant
    InsightTopic.FLUID -> Icons.Rounded.WaterDrop
    InsightTopic.SLEEP -> Icons.Rounded.Bedtime
    InsightTopic.WEIGHT -> Icons.Rounded.MonitorWeight
    InsightTopic.ACTIVITY -> Icons.Rounded.DirectionsRun
    InsightTopic.WELLBEING -> Icons.Rounded.Healing
    InsightTopic.DATA -> Icons.Rounded.Insights
}

@Composable
fun topicColor(topic: InsightTopic): Color = LocalCategoryColors.current.let { c ->
    when (topic) {
        InsightTopic.MEALS, InsightTopic.NUTRITION -> c.meal
        InsightTopic.FLUID -> c.water
        InsightTopic.SLEEP -> c.sleep
        InsightTopic.WEIGHT -> c.weight
        InsightTopic.ACTIVITY -> c.activity
        InsightTopic.WELLBEING -> c.symptom
        InsightTopic.DATA -> c.note
    }
}

@Composable
fun insightText(insight: Insight, unit: WeightUnit): InsightText {
    val h = stringResource(R.string.unit_hour_short)
    val m = stringResource(R.string.unit_minute_short)
    fun duration(minutes: Int) = Formatters.duration(abs(minutes).toLong(), h, m)
    fun clock(minutes: Int) = Formatters.time(TimeUtils.timeOfMinutes(minutes))
    @Composable fun ofDays(count: Int) = pluralStringResource(R.plurals.insights_of_days, count, count)
    @Composable fun byDays(count: Int) = pluralStringResource(R.plurals.insights_by_days, count, count)
    @Composable fun byNights(count: Int) = pluralStringResource(R.plurals.insights_by_nights, count, count)
    return when (insight) {
        is MealRhythm -> InsightText(
            stringResource(R.string.insight_rhythm_title),
            listOfNotNull(
                stringResource(R.string.insight_rhythm_body, clock(insight.firstMedian), clock(insight.lastMedian),
                    duration(insight.windowMedianMinutes), Formatters.decimal(insight.mealsPerDay)),
                when {
                    insight.firstSpreadMinutes > 120 -> stringResource(R.string.insight_rhythm_spread, duration(insight.firstSpreadMinutes))
                    insight.firstSpreadMinutes <= 60 -> stringResource(R.string.insight_rhythm_steady, duration(insight.firstSpreadMinutes))
                    else -> null
                },
                if (insight.lateStartDays >= 3) stringResource(R.string.insight_rhythm_late_start, insight.lateStartDays, ofDays(insight.days)) else null,
            ),
            byDays(insight.days),
        )
        is LateMeals -> InsightText(
            stringResource(R.string.insight_late_title),
            listOfNotNull(
                stringResource(if (insight.beforeSleep) R.string.insight_late_sleep else R.string.insight_late_clock, insight.lateDays, ofDays(insight.days)),
                insight.sleepDifferenceMinutes?.let {
                    stringResource(if (it < 0) R.string.insight_late_shorter else R.string.insight_late_longer, duration(it))
                },
            ),
            if (insight.beforeSleep) byNights(insight.days) else byDays(insight.days),
        )
        is LongGaps -> InsightText(
            stringResource(R.string.insight_gaps_title),
            listOf(stringResource(R.string.insight_gaps_body, insight.gapDays, ofDays(insight.days), duration(insight.longestGapMedianMinutes))),
            byDays(insight.days),
        )
        is NutritionSummary -> InsightText(
            stringResource(R.string.insight_nutrition_title),
            listOfNotNull(
                stringResource(R.string.insight_nutrition_body, Formatters.integer(insight.kcal.toLong())),
                if (insight.protein != null && insight.fat != null && insight.carbs != null)
                    stringResource(R.string.insight_nutrition_macros, insight.protein.toString(), insight.fat.toString(), insight.carbs.toString()) else null,
                stringResource(R.string.insight_nutrition_evening, (insight.eveningShare * 100).roundToInt()),
            ),
            stringResource(R.string.insight_nutrition_evidence, insight.days),
        )
        is SleepAndEating -> InsightText(
            stringResource(R.string.insight_sleep_eating_title),
            listOf(stringResource(if (insight.kcalDifference > 0) R.string.insight_sleep_eating_more else R.string.insight_sleep_eating_less,
                duration(insight.shortThresholdMinutes), Formatters.integer(abs(insight.kcalDifference).toLong()))),
            stringResource(R.string.insight_sleep_eating_evidence, insight.shortNights, ofDays(insight.days)),
        )
        is FluidSummary -> InsightText(
            stringResource(R.string.insight_fluid_title),
            listOfNotNull(
                stringResource(R.string.insight_fluid_body, Formatters.integer(insight.medianMl.toLong()), (insight.morningShare * 100).roundToInt()),
                insight.goalMl?.let { stringResource(R.string.insight_fluid_goal, Formatters.integer(it.toLong()), insight.goalDays, ofDays(insight.days)) },
            ),
            byDays(insight.days),
        )
        is LateCaffeine -> InsightText(
            stringResource(R.string.insight_caffeine_title),
            listOfNotNull(
                stringResource(R.string.insight_caffeine_body, duration(insight.bedtimeShiftMinutes)).takeIf { insight.bedtimeShiftMinutes >= 20 },
                stringResource(R.string.insight_caffeine_shorter, duration(insight.sleepDifferenceMinutes)).takeIf { insight.sleepDifferenceMinutes <= -20 },
            ),
            stringResource(R.string.insight_caffeine_evidence, insight.lateDays, ofDays(insight.days)),
        )
        is SleepRhythm -> InsightText(
            stringResource(R.string.insight_sleep_title),
            listOfNotNull(
                stringResource(R.string.insight_sleep_body, duration(insight.durationMedianMinutes), clock(insight.bedtimeMedian), duration(insight.bedtimeSpreadMinutes)),
                insight.weekendShiftMinutes?.takeIf { it >= 30 }?.let { stringResource(R.string.insight_sleep_weekend, duration(it)) },
            ),
            byNights(insight.days),
        )
        is WeightTrend -> {
            val kg = stringResource(if (unit == WeightUnit.KG) R.string.unit_kg else R.string.unit_lb)
            InsightText(
                stringResource(R.string.insight_weight_title),
                listOfNotNull(
                    if (abs(insight.kgPerWeek) < 0.05) stringResource(R.string.insight_weight_stable, insight.days, insight.spanDays)
                    else stringResource(R.string.insight_weight_body, insight.days, insight.spanDays,
                        Formatters.signedDecimal(Units.toDisplay(insight.kgPerWeek, unit), 2) + " " + kg),
                    insight.toTargetKg?.takeIf { abs(it) >= 0.1 }?.let {
                        stringResource(R.string.insight_weight_target, Formatters.signedDecimal(Units.toDisplay(it, unit)) + " " + kg)
                    },
                    stringResource(R.string.insight_weight_note),
                ),
                null,
            )
        }
        is StepsTrend -> InsightText(
            stringResource(R.string.insight_steps_title),
            listOf(stringResource(R.string.insight_steps_body, Formatters.integer(insight.medianSteps.toLong()),
                Formatters.integer(insight.recentAverage.toLong()), Formatters.signedDecimal(insight.change * 100, 0) + "%")),
            byDays(insight.days),
        )
        is MealDiscomfort -> InsightText(
            stringResource(R.string.insight_discomfort_title),
            listOf(stringResource(R.string.insight_discomfort_body, insight.largeWith, insight.largeTotal,
                insight.largeFromGrams?.let { "$it ${stringResource(R.string.unit_g)}" }
                    ?: "${insight.largeFromKcal} ${stringResource(R.string.unit_kcal)}",
                insight.otherWith, insight.otherTotal)),
            byDays(insight.days),
        )
        is SymptomsAfterGaps -> InsightText(
            stringResource(R.string.insight_gap_symptoms_title),
            listOf(stringResource(R.string.insight_gap_symptoms_body, insight.afterGap, insight.episodes, (insight.baselineShare * 100).roundToInt())),
            byDays(insight.days),
        )
        is Coverage -> InsightText(
            stringResource(R.string.insight_coverage_title),
            listOf(stringResource(R.string.insight_coverage_body, insight.days, ofDays(insight.windowDays))),
            null,
        )
    }
}

/** One evidence day in words: what happened that day, in the user's own records. */
@Composable
fun evidenceText(insight: Insight, e: EvidenceDay): String {
    val h = stringResource(R.string.unit_hour_short)
    val m = stringResource(R.string.unit_minute_short)
    fun duration(minutes: Int?) = Formatters.duration(abs(minutes ?: 0).toLong(), h, m)
    fun clock(minutes: Int?) = Formatters.time(TimeUtils.timeOfMinutes(minutes ?: 0))
    return when (e.kind) {
        EvidenceKind.LATE_BEFORE_SLEEP -> stringResource(R.string.evidence_late_sleep, clock(e.clock), duration(e.minutes), duration(e.value))
        EvidenceKind.LATE_CLOCK -> stringResource(R.string.evidence_late_clock, clock(e.clock))
        EvidenceKind.LONG_GAP -> stringResource(R.string.evidence_gap, clock(e.clock), duration(e.minutes))
        EvidenceKind.SHORT_NIGHT -> stringResource(R.string.evidence_short_night, duration(e.minutes), Formatters.integer((e.value ?: 0).toLong()))
        EvidenceKind.LATE_COFFEE ->
            if (e.clock != null) stringResource(R.string.evidence_coffee, clock(e.clock), duration(e.minutes))
            else stringResource(R.string.evidence_no_coffee, duration(e.minutes))
        EvidenceKind.LARGE_DISCOMFORT -> {
            val unit = stringResource(if ((insight as? MealDiscomfort)?.largeFromGrams != null) R.string.unit_g else R.string.unit_kcal)
            stringResource(if (e.flag == false) R.string.evidence_comfort else R.string.evidence_discomfort, clock(e.clock), "${e.value ?: 0} $unit")
        }
        EvidenceKind.SYMPTOM_AFTER_GAP ->
            if (e.minutes != null) stringResource(R.string.evidence_symptom, clock(e.clock), duration(e.minutes))
            else stringResource(R.string.evidence_symptom_no_meal, clock(e.clock))
    }
}

/** Heading of one evidence group, with its count (or its outcome count where the outcome is yes/no). */
@Composable
fun evidenceGroupTitle(insight: Insight, group: EvidenceGroup, items: List<EvidenceDay>): String {
    val match = group == EvidenceGroup.MATCH
    val days = items.map { it.day }.distinct().size
    @Composable fun count(@androidx.annotation.StringRes res: Int, vararg args: Any) = stringResource(R.string.evidence_group_count, stringResource(res, *args), days)
    return when (insight) {
        is LateMeals -> if (insight.beforeSleep) count(if (match) R.string.evidence_late_sleep_match else R.string.evidence_late_sleep_other)
            else count(if (match) R.string.evidence_late_clock_match else R.string.evidence_late_clock_other)
        is LongGaps -> count(if (match) R.string.evidence_gap_match else R.string.evidence_gap_other)
        is SleepAndEating -> if (match) count(R.string.evidence_short_match,
            Formatters.duration(insight.shortThresholdMinutes.toLong(), stringResource(R.string.unit_hour_short), stringResource(R.string.unit_minute_short)))
            else count(R.string.evidence_short_other)
        is LateCaffeine -> count(if (match) R.string.evidence_coffee_match else R.string.evidence_coffee_other)
        is MealDiscomfort -> {
            val from = insight.largeFromGrams?.let { "$it ${stringResource(R.string.unit_g)}" } ?: "${insight.largeFromKcal} ${stringResource(R.string.unit_kcal)}"
            val label = if (match) stringResource(R.string.evidence_portion_match, stringResource(R.string.value_from, from)) else stringResource(R.string.evidence_portion_other)
            stringResource(R.string.evidence_group_outcome, label, items.count { it.flag == true }, items.size)
        }
        is SymptomsAfterGaps -> stringResource(R.string.evidence_group_count,
            stringResource(if (match) R.string.evidence_symptom_match else R.string.evidence_symptom_other), items.size)
        else -> stringResource(R.string.evidence_group_count, stringResource(R.string.evidence_title), days)
    }
}

@Composable
fun measureLabel(key: String): String = stringResource(when (key) {
    "first_meal" -> R.string.measure_first_meal
    "last_meal" -> R.string.measure_last_meal
    "eating_window" -> R.string.measure_eating_window
    "first_meal_spread" -> R.string.measure_first_meal_spread
    "meals_per_day" -> R.string.measure_meals_per_day
    "late_share" -> R.string.measure_late_share
    "sleep_difference" -> R.string.measure_sleep_difference
    "gap_share" -> R.string.measure_gap_share
    "longest_gap" -> R.string.measure_longest_gap
    "kcal" -> R.string.measure_kcal
    "evening_share" -> R.string.measure_evening_share
    "protein" -> R.string.measure_protein
    "fat" -> R.string.measure_fat
    "carbs" -> R.string.measure_carbs
    "kcal_difference" -> R.string.measure_kcal_difference
    "fluid" -> R.string.measure_fluid
    "morning_share" -> R.string.measure_morning_share
    "goal_share" -> R.string.measure_goal_share
    "bedtime_shift" -> R.string.measure_bedtime_shift
    "late_coffee_share" -> R.string.measure_late_coffee_share
    "sleep" -> R.string.measure_sleep
    "bedtime" -> R.string.measure_bedtime
    "bedtime_spread" -> R.string.measure_bedtime_spread
    "weekend_shift" -> R.string.measure_weekend_shift
    "trend" -> R.string.measure_trend
    "steps" -> R.string.measure_steps
    "large_share" -> R.string.measure_large_share
    "other_share" -> R.string.measure_other_share
    "after_gap_share" -> R.string.measure_after_gap_share
    "baseline" -> R.string.measure_baseline
    "short_night_below" -> R.string.measure_short_night_below
    "large_from" -> R.string.measure_large_from
    else -> R.string.measure_other
})

/** A measured value in the user's units; negative differences keep their sign. */
@Composable
fun measureValue(kind: MeasureKind, value: Double, unit: WeightUnit): String {
    val h = stringResource(R.string.unit_hour_short)
    val m = stringResource(R.string.unit_minute_short)
    val minus = if (value < 0) "−" else ""
    return when (kind) {
        MeasureKind.CLOCK -> Formatters.time(TimeUtils.timeOfMinutes(value.roundToInt()))
        MeasureKind.MINUTES -> minus + Formatters.duration(abs(value).roundToInt().toLong(), h, m)
        MeasureKind.SHARE -> "${(value * 100).roundToInt()}%"
        MeasureKind.ML -> Formatters.integer(value.roundToInt().toLong()) + " " + stringResource(R.string.unit_ml)
        MeasureKind.KCAL -> minus + Formatters.integer(abs(value).roundToInt().toLong()) + " " + stringResource(R.string.unit_kcal)
        MeasureKind.GRAMS -> "${value.roundToInt()} " + stringResource(R.string.unit_g)
        MeasureKind.STEPS -> Formatters.integer(value.roundToInt().toLong())
        MeasureKind.KG_PER_WEEK -> stringResource(R.string.unit_per_week,
            Formatters.signedDecimal(Units.toDisplay(value, unit), 2) + " " + stringResource(if (unit == WeightUnit.KG) R.string.unit_kg else R.string.unit_lb))
        MeasureKind.COUNT -> Formatters.decimal(value)
    }
}

/** "Первый приём пищи: было 09:10, стало 08:30." */
@Composable
fun changeText(change: Change, unit: WeightUnit): String =
    stringResource(R.string.insights_change, measureLabel(change.key), measureValue(change.kind, change.before, unit), measureValue(change.kind, change.after, unit))

/** "Граница большой порции изменилась: было от 500 г, стало от 1 000 г." */
@Composable
fun paramChangeText(change: Change, unit: WeightUnit): String {
    @Composable fun bound(v: Double) = stringResource(if (change.key == "short_night_below") R.string.value_below else R.string.value_from, measureValue(change.kind, v, unit))
    return stringResource(R.string.insights_param_change, measureLabel(change.key), bound(change.before), bound(change.after))
}

@Composable
fun topicLabel(topic: InsightTopic): String = stringResource(when (topic) {
    InsightTopic.MEALS -> R.string.topic_meals
    InsightTopic.NUTRITION -> R.string.topic_nutrition
    InsightTopic.FLUID -> R.string.topic_fluid
    InsightTopic.SLEEP -> R.string.topic_sleep
    InsightTopic.WEIGHT -> R.string.topic_weight
    InsightTopic.ACTIVITY -> R.string.topic_activity
    InsightTopic.WELLBEING -> R.string.topic_wellbeing
    InsightTopic.DATA -> R.string.insight_coverage_title
})

/** The variant of a rule in words, for when two windows were checked differently. */
@Composable
fun basisLabel(basis: String?): String = stringResource(when (basis) {
    LateMeals.BEFORE_SLEEP -> R.string.basis_before_sleep
    LateMeals.AFTER_22 -> R.string.basis_after_22
    MealDiscomfort.BY_GRAMS -> R.string.basis_grams
    MealDiscomfort.BY_KCAL -> R.string.basis_kcal
    else -> R.string.basis_other
})
