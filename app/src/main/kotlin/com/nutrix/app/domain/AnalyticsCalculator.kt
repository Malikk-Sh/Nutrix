package com.nutrix.app.domain

import com.nutrix.app.core.model.AfterMealFeeling
import com.nutrix.app.core.model.Satiety
import com.nutrix.app.core.model.SymptomType
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.domain.ChartPoint
import com.nutrix.app.database.entity.DrinkEntity
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.database.entity.NoteEntity
import com.nutrix.app.database.entity.SleepEntity
import com.nutrix.app.database.entity.SymptomEntity
import com.nutrix.app.database.entity.WeightEntity
import java.time.LocalDate
import java.time.temporal.IsoFields
import kotlin.math.abs
import kotlin.math.sqrt

data class AnalyticsInput(
    val from: Long,
    val to: Long,
    val meals: List<MealEntity>,
    val drinks: List<DrinkEntity>,
    val weights: List<WeightEntity>,
    val symptoms: List<SymptomEntity>,
    val sleeps: List<SleepEntity>,
    val activity: List<DailyActivity>,
    val notes: List<NoteEntity> = emptyList(),
)

enum class Metric {
    WEIGHT, MEALS, FLUID, APPETITE, EARLY_SATIETY, NAUSEA, SLEEP_DURATION, BEDTIME, STEPS, HEART_RATE
}

data class MetricSeries(val metric: Metric, val points: List<ChartPoint>) {
    val average: Double? get() = points.takeIf { it.isNotEmpty() }?.map { it.value }?.average()
    val days: Int get() = points.size
}

enum class Comparison { APPETITE_VS_SLEEP, APPETITE_VS_WAKE, MEALS_VS_SYMPTOMS, FLUID_VS_DIZZINESS, WEIGHT_VS_MEALS }

enum class Strength { NONE, WEAK, MODERATE, STRONG }

data class CorrelationResult(
    val comparison: Comparison,
    val pairs: List<Pair<Double, Double>>,
    /** Null when there are not enough pairs or a variable does not vary. */
    val r: Double?,
    val minPairs: Int,
) {
    val sufficient: Boolean get() = r != null
    val strength: Strength
        get() = when (val a = r?.let(::abs)) {
            null -> Strength.NONE
            in 0.0..<0.1 -> Strength.NONE
            in 0.1..<0.3 -> Strength.WEAK
            in 0.3..<0.5 -> Strength.MODERATE
            else -> if (a >= 0.5) Strength.STRONG else Strength.NONE
        }
}

/**
 * Pure analytics over diary data. Days without any diary entry are treated as "not tracked"
 * (unknown), never as zero, so gaps in logging do not look like days without food or water.
 */
object AnalyticsCalculator {
    const val MIN_DAILY_PAIRS = 7
    const val MIN_WEEKLY_PAIRS = 5

    fun trackedDays(input: AnalyticsInput): Set<Long> =
        (input.meals.map { it.localDate } + input.drinks.map { it.localDate } + input.symptoms.map { it.localDate } +
            input.notes.map { it.localDate }).filter { it in input.from..input.to }.toSet()

    fun series(input: AnalyticsInput): Map<Metric, MetricSeries> {
        val tracked = trackedDays(input).sorted()
        val inRange = { d: Long -> d in input.from..input.to }
        val mealsByDay = input.meals.groupBy { it.localDate }
        val drinksByDay = input.drinks.groupBy { it.localDate }
        val symptomsByDay = input.symptoms.groupBy { it.localDate }

        val weight = input.weights.filter { inRange(it.localDate) }.groupBy { it.localDate }
            .map { (d, l) -> ChartPoint(d, l.map { it.weightKg }.average()) }.sortedBy { it.epochDay }
        val meals = tracked.map { ChartPoint(it, (mealsByDay[it]?.size ?: 0).toDouble()) }
        val fluid = tracked.map { d -> ChartPoint(d, drinksByDay[d].orEmpty().sumOf { it.volumeMl }.toDouble()) }
        val appetite = mealsByDay.filterKeys(inRange).mapNotNull { (d, l) ->
            l.mapNotNull { it.appetite }.takeIf { it.isNotEmpty() }?.let { ChartPoint(d, it.average()) }
        }.sortedBy { it.epochDay }
        val earlySatiety = tracked.map { d ->
            val fromMeals = mealsByDay[d].orEmpty().count { it.satiety == Satiety.QUICK }
            val fromSymptoms = symptomsByDay[d].orEmpty().count { it.type == SymptomType.EARLY_SATIETY }
            ChartPoint(d, (fromMeals + fromSymptoms).toDouble())
        }
        val nausea = tracked.map { d ->
            val fromMeals = mealsByDay[d].orEmpty().count { AfterMealFeeling.NAUSEA in it.feelings }
            val fromSymptoms = symptomsByDay[d].orEmpty().count { it.type == SymptomType.NAUSEA }
            ChartPoint(d, (fromMeals + fromSymptoms).toDouble())
        }
        val sleepByDay = input.sleeps.filter { inRange(it.localDate) }.groupBy { it.localDate }
        val sleep = sleepByDay.map { (d, l) -> ChartPoint(d, l.sumOf { it.durationMinutes } / 60.0) }.sortedBy { it.epochDay }
        val bedtime = sleepByDay.map { (d, l) ->
            val main = l.maxBy { it.durationMinutes }
            val start = TimeUtils.localDateTime(main.startMillis, main.zoneId).toLocalTime()
            ChartPoint(d, TimeUtils.bedtimeMinutesFromEvening(start).toDouble())
        }.sortedBy { it.epochDay }
        val steps = input.activity.filter { inRange(it.localDate) && (it.steps ?: 0) > 0 }.map { ChartPoint(it.localDate, it.steps!!.toDouble()) }
        val hr = input.activity.filter { inRange(it.localDate) }.mapNotNull { a ->
            (a.hrAvg ?: a.hrLatestBpm)?.let { ChartPoint(a.localDate, it.toDouble()) }
        }
        return listOf(
            MetricSeries(Metric.WEIGHT, weight),
            MetricSeries(Metric.MEALS, meals),
            MetricSeries(Metric.FLUID, fluid),
            MetricSeries(Metric.APPETITE, appetite),
            MetricSeries(Metric.EARLY_SATIETY, earlySatiety),
            MetricSeries(Metric.NAUSEA, nausea),
            MetricSeries(Metric.SLEEP_DURATION, sleep),
            MetricSeries(Metric.BEDTIME, bedtime),
            MetricSeries(Metric.STEPS, steps),
            MetricSeries(Metric.HEART_RATE, hr),
        ).associateBy { it.metric }
    }

    fun correlations(input: AnalyticsInput, series: Map<Metric, MetricSeries> = series(input)): List<CorrelationResult> {
        val appetite = series.getValue(Metric.APPETITE).points.associate { it.epochDay to it.value }
        val sleep = series.getValue(Metric.SLEEP_DURATION).points.associate { it.epochDay to it.value }
        val wake = input.sleeps.filter { it.localDate in input.from..input.to }.groupBy { it.localDate }.mapValues { (_, l) ->
            val main = l.maxBy { it.durationMinutes }
            TimeUtils.minutesOfDay(TimeUtils.localDateTime(main.endMillis, main.zoneId).toLocalTime()) / 60.0
        }
        val tracked = trackedDays(input)
        val mealsPerDay = series.getValue(Metric.MEALS).points.associate { it.epochDay to it.value }
        val symptomsPerDay = tracked.associateWith { d -> input.symptoms.count { it.localDate == d }.toDouble() }
        val fluidPerDay = series.getValue(Metric.FLUID).points.associate { it.epochDay to it.value }
        val dizzinessPerDay = tracked.associateWith { d ->
            input.symptoms.count { it.localDate == d && (it.type == SymptomType.DIZZINESS || it.type == SymptomType.DARKENING_VISION) }.toDouble()
        }

        return listOf(
            daily(Comparison.APPETITE_VS_SLEEP, sleep, appetite),
            daily(Comparison.APPETITE_VS_WAKE, wake, appetite),
            daily(Comparison.MEALS_VS_SYMPTOMS, mealsPerDay, symptomsPerDay),
            daily(Comparison.FLUID_VS_DIZZINESS, fluidPerDay, dizzinessPerDay),
            weightVsMeals(input, mealsPerDay),
        )
    }

    private fun daily(c: Comparison, x: Map<Long, Double>, y: Map<Long, Double>): CorrelationResult {
        val pairs = x.keys.intersect(y.keys).sorted().map { x.getValue(it) to y.getValue(it) }
        return CorrelationResult(c, pairs, pearson(pairs, MIN_DAILY_PAIRS), MIN_DAILY_PAIRS)
    }

    /** Weekly mean meals per tracked day vs weekly mean weight. */
    private fun weightVsMeals(input: AnalyticsInput, mealsPerDay: Map<Long, Double>): CorrelationResult {
        fun week(day: Long): Pair<Int, Int> = LocalDate.ofEpochDay(day).let { it.get(IsoFields.WEEK_BASED_YEAR) to it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) }
        val weightByWeek = input.weights.filter { it.localDate in input.from..input.to }.groupBy { week(it.localDate) }
            .mapValues { (_, l) -> l.map { it.weightKg }.average() }
        val mealsByWeek = mealsPerDay.entries.groupBy { week(it.key) }.mapValues { (_, l) -> l.map { it.value }.average() }
        val pairs = weightByWeek.keys.intersect(mealsByWeek.keys).sortedWith(compareBy({ it.first }, { it.second }))
            .map { mealsByWeek.getValue(it) to weightByWeek.getValue(it) }
        return CorrelationResult(Comparison.WEIGHT_VS_MEALS, pairs, pearson(pairs, MIN_WEEKLY_PAIRS), MIN_WEEKLY_PAIRS)
    }

    /** Pearson correlation coefficient, or null when there is too little or constant data. */
    fun pearson(pairs: List<Pair<Double, Double>>, minPairs: Int = MIN_DAILY_PAIRS): Double? {
        if (pairs.size < minPairs) return null
        val n = pairs.size
        val mx = pairs.sumOf { it.first } / n
        val my = pairs.sumOf { it.second } / n
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for ((x, y) in pairs) {
            sxy += (x - mx) * (y - my)
            sxx += (x - mx) * (x - mx)
            syy += (y - my) * (y - my)
        }
        if (sxx < 1e-12 || syy < 1e-12) return null
        return (sxy / sqrt(sxx * syy)).coerceIn(-1.0, 1.0)
    }
}
