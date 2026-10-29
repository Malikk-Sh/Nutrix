package com.nutrix.app.domain.insights

import com.nutrix.app.core.model.AfterMealFeeling
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.model.SymptomType
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.database.entity.SleepEntity
import com.nutrix.app.domain.AnalyticsInput
import com.nutrix.app.domain.ChartPoint
import com.nutrix.app.domain.SleepTotals
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

data class InsightInput(
    /** Records of the analysis window; [AnalyticsInput.from]..[AnalyticsInput.to] are completed days. */
    val records: AnalyticsInput,
    val waterGoalMl: Int?,
    val targetWeightKg: Double?,
)

/**
 * Finds observations in the user's own records. Every rule compares the user with their own days,
 * requires a minimum number of observations and states counts, so small samples do not look like facts.
 * Coincidences in a diary are not causes; the UI says so on every link.
 */
object InsightEngine {
    const val WINDOW_DAYS = 28
    const val MIN_DAYS = 7
    private const val MIN_GROUP = 4
    private const val EVENING_START = 18 * 60
    private const val LATE_START = 12 * 60
    private const val LATE_MEAL = 22 * 60
    private const val LATE_COFFEE = 16 * 60
    private const val LONG_GAP_MINUTES = 6 * 60
    private const val SYMPTOM_GAP_MINUTES = 5 * 60
    private const val BEFORE_SLEEP_MINUTES = 120
    private const val NIGHT_END = 4 * 60

    /** The last [WINDOW_DAYS] completed days ending yesterday, so today's half-filled day does not skew rates. */
    fun window(today: Long): LongRange = (today - WINDOW_DAYS)..(today - 1)

    fun analyze(input: InsightInput): List<Insight> = evaluate(input).insights

    /**
     * Runs every rule and keeps, besides what was found, whether each rule could be evaluated at all:
     * "evaluated, nothing found" and "too little data" mean different things when windows are compared.
     */
    fun evaluate(input: InsightInput): Analysis {
        val r = input.records
        val range = r.from..r.to
        val meals = r.meals.filter { it.localDate in range }.sortedBy { it.epochMillis }
        val mealsByDay = meals.groupBy { it.localDate }
        val nights = r.sleeps.filter { it.localDate in (r.from + 1)..(r.to + 1) }.groupBy { it.localDate }
        val outcomes = linkedMapOf(
            "meal_rhythm" to mealRhythm(mealsByDay),
            "late_meals" to lateMeals(mealsByDay, nights),
            "long_gaps" to longGaps(mealsByDay),
            "nutrition" to nutrition(mealsByDay),
            "sleep_eating" to sleepAndEating(mealsByDay, nights),
            "fluid" to fluid(input, range),
            "late_caffeine" to lateCaffeine(input, range, nights),
            "sleep_rhythm" to sleepRhythm(nights),
            "weight_trend" to weightTrend(input, range),
            "steps" to steps(input, range),
            "meal_discomfort" to mealDiscomfort(meals),
            "symptoms_after_gaps" to symptomsAfterGaps(input, range, meals),
        )
        val found = outcomes.values.mapNotNull { (it as? Outcome.Found)?.insight }
        val checks = outcomes.mapValues { (_, o) ->
            when (o) {
                is Outcome.Found -> RuleCheck(RuleState.FOUND, o.insight.basis)
                is Outcome.Absent -> RuleCheck(RuleState.NOT_FOUND, o.basis)
                Outcome.NoData -> RuleCheck(RuleState.NOT_ENOUGH_DATA, null)
            }
        }
        val tracked = (r.meals.map { it.localDate } + r.drinks.map { it.localDate } + r.symptoms.map { it.localDate } +
            r.notes.map { it.localDate } + r.weights.map { it.localDate }).filter { it in range }.toSet().size
        val windowDays = (r.to - r.from + 1).toInt()
        val coverage = Coverage(tracked, windowDays).takeIf { found.isEmpty() || tracked < windowDays / 2 }
        return Analysis(rank(found) + listOfNotNull(coverage), checks)
    }

    /** What one rule said about a window. */
    private sealed interface Outcome {
        data class Found(val insight: Insight) : Outcome
        /** Enough data to evaluate, and no pattern: [basis] tells which variant of the rule was checked. */
        data class Absent(val basis: String = Insight.DEFAULT_BASIS) : Outcome
        data object NoData : Outcome
    }

    /** Attention first, personal links before summaries; the order is stable for the same data. */
    fun rank(insights: List<Insight>): List<Insight> = insights.sortedWith(
        compareBy<Insight> { when (it.tone) { InsightTone.ATTENTION -> 0; InsightTone.POSITIVE -> 1; InsightTone.NEUTRAL -> 2 } }
            .thenBy { if (it.isLink) 0 else 1 }
            .thenBy { it.topic.ordinal },
    )

    private fun minuteOf(millis: Long, zone: String) = TimeUtils.minutesOfDay(TimeUtils.localDateTime(millis, zone).toLocalTime())
    private fun MealEntity.minute() = minuteOf(epochMillis, zoneId)

    /** A snack after midnight belongs to the previous evening, not to the start of the eating day. */
    private fun firstMeal(list: List<MealEntity>): MealEntity = list.firstOrNull { it.minute() >= NIGHT_END } ?: list.first()

    private fun mealRhythm(byDay: Map<Long, List<MealEntity>>): Outcome {
        if (byDay.size < MIN_DAYS) return Outcome.NoData
        val first = byDay.values.map { firstMeal(it).minute() }
        val last = byDay.values.map { it.last().minute() }
        val window = byDay.values.map { ((it.last().epochMillis - firstMeal(it).epochMillis) / 60_000).toInt().coerceAtLeast(0) }
        val chart = byDay.toSortedMap().map { (d, l) -> ChartPoint(d, firstMeal(l).minute() / 60.0) }
        return Outcome.Found(MealRhythm(
            days = byDay.size,
            firstMedian = median(first).roundToInt(),
            lastMedian = median(last).roundToInt(),
            windowMedianMinutes = median(window).roundToInt(),
            firstSpreadMinutes = iqr(first).roundToInt(),
            lateStartDays = first.count { it >= LATE_START },
            mealsPerDay = byDay.values.sumOf { it.size } / byDay.size.toDouble(),
            chart = chart,
        ))
    }

    /** The main sleep that started after this day's last meal (it ends on the next morning). */
    private fun sleepStart(day: Long, nights: Map<Long, List<SleepEntity>>): Long? =
        nights[day + 1]?.maxByOrNull { it.durationMinutes }?.startMillis

    private fun lateMeals(byDay: Map<Long, List<MealEntity>>, nights: Map<Long, List<SleepEntity>>): Outcome {
        if (byDay.size < MIN_DAYS) return Outcome.NoData
        data class Evening(val day: Long, val gap: Int, val sleep: Int, val last: Int, val meal: Long) { val late get() = gap < BEFORE_SLEEP_MINUTES }
        val withSleep = byDay.mapNotNull { (day, list) ->
            val start = sleepStart(day, nights) ?: return@mapNotNull null
            val gap = ((start - list.last().epochMillis) / 60_000).toInt()
            if (gap <= 0) null else Evening(day, gap, SleepTotals.minutes(nights.getValue(day + 1)), list.last().minute(), list.last().id)
        }
        if (withSleep.size >= MIN_DAYS) {
            val late = withSleep.filter { it.late }
            if (late.size < 3 || late.size < withSleep.size * 0.3) return Outcome.Absent(LateMeals.BEFORE_SLEEP)
            val other = withSleep.filterNot { it.late }
            val diff = if (late.size >= MIN_GROUP && other.size >= MIN_GROUP)
                (late.map { it.sleep }.average() - other.map { it.sleep }.average()).roundToInt().takeIf { abs(it) >= 20 } else null
            return Outcome.Found(LateMeals(withSleep.size, late.size, beforeSleep = true, sleepDifferenceMinutes = diff,
                // Both groups, so "shorter sleep after late evenings" can be checked against the other evenings.
                evidence = withSleep.map {
                    EvidenceDay(it.day, EvidenceKind.LATE_BEFORE_SLEEP, clock = it.last, minutes = it.gap, value = it.sleep,
                        group = if (it.late) EvidenceGroup.MATCH else EvidenceGroup.OTHER, record = RecordRef(RecordType.MEAL, it.meal))
                }.newestFirst()))
        }
        val late = byDay.filterValues { it.last().minute() >= LATE_MEAL }
        if (late.size < 3 || late.size < byDay.size * 0.3) return Outcome.Absent(LateMeals.AFTER_22)
        return Outcome.Found(LateMeals(byDay.size, late.size, beforeSleep = false, sleepDifferenceMinutes = null,
            evidence = byDay.map { (day, l) ->
                EvidenceDay(day, EvidenceKind.LATE_CLOCK, clock = l.last().minute(),
                    group = if (day in late) EvidenceGroup.MATCH else EvidenceGroup.OTHER, record = RecordRef(RecordType.MEAL, l.last().id))
            }.newestFirst()))
    }

    private fun longGaps(byDay: Map<Long, List<MealEntity>>): Outcome {
        val multi = byDay.filterValues { it.size >= 2 }
        if (multi.size < MIN_DAYS) return Outcome.NoData
        // The longest gap of each day and the meal it starts after.
        data class Gap(val day: Long, val from: MealEntity, val minutes: Int)
        val longest = multi.map { (day, list) ->
            val (from, to) = list.zipWithNext().maxBy { (a, b) -> b.epochMillis - a.epochMillis }
            Gap(day, from, ((to.epochMillis - from.epochMillis) / 60_000).toInt())
        }
        val gaps = longest.filter { it.minutes > LONG_GAP_MINUTES }
        if (gaps.size < 3 || gaps.size < multi.size * 0.25) return Outcome.Absent()
        return Outcome.Found(LongGaps(multi.size, gaps.size, median(longest.map { it.minutes }).roundToInt(),
            evidence = longest.map {
                EvidenceDay(it.day, EvidenceKind.LONG_GAP, clock = it.from.minute(), minutes = it.minutes,
                    group = if (it.minutes > LONG_GAP_MINUTES) EvidenceGroup.MATCH else EvidenceGroup.OTHER, record = RecordRef(RecordType.MEAL, it.from.id))
            }.newestFirst()))
    }

    /** Days where at least two meals and most of the day's meals carry a calorie estimate. */
    private fun estimatedDays(byDay: Map<Long, List<MealEntity>>): Map<Long, List<MealEntity>> = byDay.filterValues { list ->
        val known = list.count { it.caloriesEstimate != null }
        known >= 2 && known >= list.size * 0.7
    }

    private fun nutrition(byDay: Map<Long, List<MealEntity>>): Outcome {
        val days = estimatedDays(byDay)
        if (days.size < 5) return Outcome.NoData
        fun macro(pick: (MealEntity) -> Double?): Int? {
            val values = days.values.filter { l -> l.count { pick(it) != null } >= l.size * 0.7 }
                .map { l -> l.sumOf { pick(it) ?: 0.0 } }
            return if (values.size >= 5) values.average().roundToInt() else null
        }
        val kcal = days.values.map { l -> l.sumOf { it.caloriesEstimate ?: 0 } }
        val evening = days.values.mapNotNull { l ->
            val total = l.sumOf { it.caloriesEstimate ?: 0 }
            if (total <= 0) null else l.filter { it.minute() >= EVENING_START }.sumOf { it.caloriesEstimate ?: 0 } / total.toDouble()
        }
        return Outcome.Found(NutritionSummary(
            days = days.size,
            kcal = kcal.average().roundToInt(),
            protein = macro { it.nutrition?.proteinGrams },
            fat = macro { it.nutrition?.fatGrams },
            carbs = macro { it.nutrition?.carbsGrams },
            eveningShare = if (evening.isEmpty()) 0.0 else median(evening),
        ))
    }

    private fun sleepAndEating(byDay: Map<Long, List<MealEntity>>, nights: Map<Long, List<SleepEntity>>): Outcome {
        val days = estimatedDays(byDay)
        // A night belongs to the day it ends on, which is the day whose eating it may influence.
        val pairs = days.mapNotNull { (day, list) ->
            val night = nights[day] ?: return@mapNotNull null
            Triple(day, SleepTotals.minutes(night), list.sumOf { it.caloriesEstimate ?: 0 })
        }
        if (pairs.size < MIN_DAYS) return Outcome.NoData
        val threshold = (median(pairs.map { it.second }) - 60).roundToInt()
        val short = pairs.filter { it.second < threshold }
        val other = pairs.filter { it.second >= threshold }
        if (short.size < MIN_GROUP || other.size < MIN_GROUP) return Outcome.NoData
        val diff = (short.map { it.third }.average() - other.map { it.third }.average()).roundToInt()
        if (abs(diff) < 200) return Outcome.Absent()
        return Outcome.Found(SleepAndEating(pairs.size, short.size, threshold, diff,
            evidence = pairs.map { (day, sleep, kcal) ->
                EvidenceDay(day, EvidenceKind.SHORT_NIGHT, minutes = sleep, value = kcal,
                    group = if (sleep < threshold) EvidenceGroup.MATCH else EvidenceGroup.OTHER)
            }.newestFirst()))
    }

    private fun fluid(input: InsightInput, range: LongRange): Outcome {
        val byDay = input.records.drinks.filter { it.localDate in range }.groupBy { it.localDate }
        if (byDay.size < MIN_DAYS) return Outcome.NoData
        val totals = byDay.mapValues { (_, l) -> l.sumOf { it.volumeMl } }
        val morning = byDay.mapNotNull { (_, l) ->
            val total = l.sumOf { it.volumeMl }
            if (total <= 0) null else l.filter { minuteOf(it.epochMillis, it.zoneId) < 14 * 60 }.sumOf { it.volumeMl } / total.toDouble()
        }
        val goal = input.waterGoalMl?.takeIf { it > 0 }
        return Outcome.Found(FluidSummary(
            days = byDay.size,
            medianMl = median(totals.values.toList()).roundToInt(),
            goalMl = goal,
            goalDays = if (goal == null) 0 else totals.values.count { it >= goal },
            morningShare = if (morning.isEmpty()) 0.0 else median(morning),
            chart = totals.toSortedMap().map { (d, v) -> ChartPoint(d, v.toDouble()) },
        ))
    }

    private fun lateCaffeine(input: InsightInput, range: LongRange, nights: Map<Long, List<SleepEntity>>): Outcome {
        val drinkDays = input.records.drinks.filter { it.localDate in range }.groupBy { it.localDate }
        data class Night(val day: Long, val coffee: Int?, val drink: Long?, val bedtime: Int, val sleep: Int)
        val rows = drinkDays.mapNotNull { (day, list) ->
            val night = nights[day + 1] ?: return@mapNotNull null
            val main = night.maxBy { it.durationMinutes }
            val coffee = list.filter { it.drinkType == DrinkType.COFFEE && minuteOf(it.epochMillis, it.zoneId) >= LATE_COFFEE }.maxByOrNull { it.epochMillis }
            val bedtime = TimeUtils.bedtimeMinutesFromEvening(TimeUtils.localDateTime(main.startMillis, main.zoneId).toLocalTime())
            Night(day, coffee?.let { minuteOf(it.epochMillis, it.zoneId) }, coffee?.id, bedtime, SleepTotals.minutes(night))
        }
        val late = rows.filter { it.coffee != null }
        val other = rows.filter { it.coffee == null }
        if (late.size < MIN_GROUP || other.size < MIN_GROUP) return Outcome.NoData
        val shift = (late.map { it.bedtime }.average() - other.map { it.bedtime }.average()).roundToInt()
        val sleep = (late.map { it.sleep }.average() - other.map { it.sleep }.average()).roundToInt()
        if (shift < 20 && sleep > -20) return Outcome.Absent()
        return Outcome.Found(LateCaffeine(rows.size, late.size, shift, sleep,
            evidence = rows.map {
                EvidenceDay(it.day, EvidenceKind.LATE_COFFEE, clock = it.coffee, minutes = it.sleep,
                    group = if (it.coffee != null) EvidenceGroup.MATCH else EvidenceGroup.OTHER, record = it.drink?.let { id -> RecordRef(RecordType.DRINK, id) })
            }.newestFirst()))
    }

    private fun sleepRhythm(nights: Map<Long, List<SleepEntity>>): Outcome {
        if (nights.size < MIN_DAYS) return Outcome.NoData
        val rows = nights.toSortedMap().map { (day, list) ->
            val main = list.maxBy { it.durationMinutes }
            val bedtime = TimeUtils.bedtimeMinutesFromEvening(TimeUtils.localDateTime(main.startMillis, main.zoneId).toLocalTime())
            val wake = minuteOf(main.endMillis, main.zoneId)
            val weekend = LocalDate.ofEpochDay(day).dayOfWeek.let { it == DayOfWeek.SATURDAY || it == DayOfWeek.SUNDAY }
            Row(day, SleepTotals.minutes(list), bedtime, wake, weekend)
        }
        val weekend = rows.filter { it.weekend }
        val weekday = rows.filterNot { it.weekend }
        val shift = if (weekend.size >= 2 && weekday.size >= MIN_GROUP)
            (median(weekend.map { it.wake }) - median(weekday.map { it.wake })).roundToInt() else null
        return Outcome.Found(SleepRhythm(
            days = rows.size,
            durationMedianMinutes = median(rows.map { it.minutes }).roundToInt(),
            bedtimeMedian = TimeUtils.minutesOfDay(TimeUtils.eveningMinutesToTime(median(rows.map { it.bedtime }).roundToInt())),
            bedtimeSpreadMinutes = iqr(rows.map { it.bedtime }).roundToInt(),
            weekendShiftMinutes = shift,
            chart = rows.map { ChartPoint(it.day, it.minutes / 60.0) },
        ))
    }

    private data class Row(val day: Long, val minutes: Int, val bedtime: Int, val wake: Int, val weekend: Boolean)

    private fun weightTrend(input: InsightInput, range: LongRange): Outcome {
        val daily = input.records.weights.filter { it.localDate in range }.groupBy { it.localDate }
            .map { (d, l) -> ChartPoint(d, l.map { it.weightKg }.average()) }.sortedBy { it.epochDay }
        if (daily.size < 4) return Outcome.NoData
        val span = (daily.last().epochDay - daily.first().epochDay).toInt()
        if (span < 10) return Outcome.NoData
        val slope = slope(daily.map { it.epochDay.toDouble() to it.value }) * 7
        val current = daily.takeLast(3).map { it.value }.average()
        return Outcome.Found(WeightTrend(daily.size, span, slope, input.targetWeightKg?.let { it - current }, daily))
    }

    private fun steps(input: InsightInput, range: LongRange): Outcome {
        val days = input.records.activity.filter { it.localDate in range && it.steps != null }.sortedBy { it.localDate }
        if (days.size < 10) return Outcome.NoData
        val recentFrom = range.last - 6
        val recent = days.filter { it.localDate >= recentFrom }.map { it.steps!!.toDouble() }
        val earlier = days.filter { it.localDate < recentFrom }.map { it.steps!!.toDouble() }
        if (recent.size < 4 || earlier.size < 4) return Outcome.NoData
        return Outcome.Found(StepsTrend(
            days = days.size,
            medianSteps = median(days.map { it.steps!!.toDouble() }).roundToInt(),
            recentAverage = recent.average().roundToInt(),
            earlierAverage = earlier.average().roundToInt(),
            chart = days.map { ChartPoint(it.localDate, it.steps!!.toDouble()) },
        ))
    }

    private fun mealDiscomfort(meals: List<MealEntity>): Outcome {
        val rated = meals.filter { it.feelings.isNotEmpty() }
        fun size(m: MealEntity): Double? = m.portionGrams?.toDouble()
        val byGrams = rated.count { it.portionGrams != null } >= rated.size * 0.7
        val measure: (MealEntity) -> Double? = if (byGrams) ::size else { m -> m.caloriesEstimate?.toDouble() }
        val sized = rated.mapNotNull { m -> measure(m)?.let { m to it } }
        if (sized.size < 8) return Outcome.NoData
        val threshold = quantile(sized.map { it.second }, 2.0 / 3)
        val large = sized.filter { it.second >= threshold }.map { it.first }
        val other = sized.filter { it.second < threshold }.map { it.first }
        if (large.size < MIN_GROUP || other.size < MIN_GROUP) return Outcome.NoData
        fun bad(m: MealEntity) = m.feelings.any { it != AfterMealFeeling.NORMAL }
        val largeWith = large.count(::bad)
        val otherWith = other.count(::bad)
        if (largeWith < 3 || largeWith / large.size.toDouble() - otherWith / other.size.toDouble() < 0.25) return Outcome.Absent(if (byGrams) MealDiscomfort.BY_GRAMS else MealDiscomfort.BY_KCAL)
        // Shown to the user as "from N g": the smallest portion that counted as large.
        val largeFrom = sized.filter { it.second >= threshold }.minOf { it.second }.roundToInt()
        return Outcome.Found(MealDiscomfort(
            days = sized.map { it.first.localDate }.distinct().size,
            largeWith = largeWith, largeTotal = large.size, otherWith = otherWith, otherTotal = other.size,
            largeFromGrams = if (byGrams) largeFrom else null,
            largeFromKcal = if (byGrams) null else largeFrom,
            // Every rated, sized meal: large and other portions, with and without an unpleasant feeling.
            evidence = sized.map { (m, size) ->
                EvidenceDay(m.localDate, EvidenceKind.LARGE_DISCOMFORT, clock = m.minute(), value = size.roundToInt(),
                    group = if (size >= threshold) EvidenceGroup.MATCH else EvidenceGroup.OTHER, flag = bad(m), record = RecordRef(RecordType.MEAL, m.id))
            }.newestFirst(),
        ))
    }

    private val gapSymptoms = setOf(SymptomType.WEAKNESS, SymptomType.DIZZINESS, SymptomType.DARKENING_VISION, SymptomType.TREMOR, SymptomType.SWEATING)

    private fun symptomsAfterGaps(input: InsightInput, range: LongRange, meals: List<MealEntity>): Outcome {
        val episodes = input.records.symptoms.filter { it.localDate in range && it.type in gapSymptoms }
        if (episodes.size < 3 || meals.size < 14) return Outcome.NoData
        val times = meals.map { it.epochMillis }
        fun sinceMeal(at: Long): Long? = times.lastOrNull { it <= at }?.let { (at - it) / 60_000 }
        val afterGapEpisodes = episodes.filter { s -> sinceMeal(s.epochMillis)?.let { it > SYMPTOM_GAP_MINUTES } == true }
        val afterGap = afterGapEpisodes.size
        // How much of the waking day (08:00-22:00) is spent that long after a meal, sampled every 30 minutes.
        val mealDays = meals.map { it.localDate }.toSet()
        val samples = mealDays.flatMap { day ->
            val zone = TimeUtils.zoneOrDefault(meals.first { it.localDate == day }.zoneId)
            val start = TimeUtils.dayBounds(day, zone).first
            (16 until 44).map { start + it * 30 * 60_000L }
        }
        val gapSamples = samples.count { at -> sinceMeal(at)?.let { it > SYMPTOM_GAP_MINUTES } == true }
        val baseline = if (samples.isEmpty()) 0.0 else gapSamples / samples.size.toDouble()
        if (afterGap < 3 || afterGap / episodes.size.toDouble() < baseline + 0.3) return Outcome.Absent()
        return Outcome.Found(SymptomsAfterGaps(episodes.map { it.localDate }.distinct().size, episodes.size, afterGap, baseline,
            evidence = episodes.map { s ->
                EvidenceDay(s.localDate, EvidenceKind.SYMPTOM_AFTER_GAP, clock = minuteOf(s.epochMillis, s.zoneId), minutes = sinceMeal(s.epochMillis)?.toInt(),
                    group = if (s in afterGapEpisodes) EvidenceGroup.MATCH else EvidenceGroup.OTHER, record = RecordRef(RecordType.SYMPTOM, s.id))
            }.newestFirst()))
    }

    private fun List<EvidenceDay>.newestFirst() = sortedWith(compareByDescending<EvidenceDay> { it.day }.thenByDescending { it.clock ?: 0 })

    internal fun median(values: List<Number>): Double = quantile(values.map { it.toDouble() }, 0.5)

    internal fun iqr(values: List<Number>): Double = values.map { it.toDouble() }.let { quantile(it, 0.75) - quantile(it, 0.25) }

    /** Linear interpolation between order statistics. */
    internal fun quantile(values: List<Double>, q: Double): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val pos = (sorted.size - 1) * q
        val lo = sorted[pos.toInt()]
        val hi = sorted[minOf(pos.toInt() + 1, sorted.lastIndex)]
        return lo + (hi - lo) * (pos - pos.toInt())
    }

    /** Least-squares slope of y over x. */
    internal fun slope(points: List<Pair<Double, Double>>): Double {
        val mx = points.map { it.first }.average()
        val my = points.map { it.second }.average()
        val den = points.sumOf { (x, _) -> (x - mx) * (x - mx) }
        return if (den == 0.0) 0.0 else points.sumOf { (x, y) -> (x - mx) * (y - my) } / den
    }
}
