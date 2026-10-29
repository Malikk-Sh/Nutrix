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
