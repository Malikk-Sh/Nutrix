package com.nutrix.app.reminders

import com.nutrix.app.core.datastore.ReminderConfig
import com.nutrix.app.core.datastore.ReminderType
import com.nutrix.app.core.datastore.supportsAdaptive
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.database.entity.DrinkEntity
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.database.entity.WeightEntity
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.roundToInt

/** A time of day the user usually eats at, and on how many days it was seen. */
data class MealSlot(val minute: Int, val days: Int)

/**
 * The user's own routine learned from the last weeks of records. Empty lists and nulls mean
 * "not enough data yet": adaptive reminders then keep the times the user set.
 */
data class Routine(
    val weekdayMeals: List<MealSlot> = emptyList(),
    val weekendMeals: List<MealSlot> = emptyList(),
    val weighMinute: Int? = null,
    /** Median share of the day's fluid drunk before each full hour 0..24. */
    val fluidCurve: List<Double> = emptyList(),
    val fluidMedianMl: Int? = null,
) {
    fun mealSlots(date: LocalDate): List<MealSlot> = if (isWeekend(date)) weekendMeals else weekdayMeals

    /** Share of a usual day's fluid drunk by [minute], interpolated between hours. */
    fun fluidShareAt(minute: Int): Double? {
        if (fluidCurve.size != 25) return null
        val h = (minute / 60).coerceIn(0, 23)
        val f = (minute % 60) / 60.0
        return fluidCurve[h] + (fluidCurve[h + 1] - fluidCurve[h]) * f
    }

    companion object {
        fun isWeekend(date: LocalDate) = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
    }
}

/** Learns [Routine] from records. Pure and deterministic so it can be unit-tested. */
object RoutineLearner {
    const val WINDOW_DAYS = 28
    const val MIN_DAYS = 7
    private const val MIN_WEEKEND_DAYS = 4
    private const val CLUSTER_GAP = 90
    private const val MAX_SLOT_WIDTH = 180
    /** A slot must appear on at least this share of tracked days to count as a habit. */
    private const val MIN_SUPPORT = 0.4
    private const val NIGHT_END = 4 * 60

    fun window(today: Long): LongRange = (today - WINDOW_DAYS)..(today - 1)

    fun learn(meals: List<MealEntity>, drinks: List<DrinkEntity>, weights: List<WeightEntity>, window: LongRange): Routine {
        val dayMeals = meals.filter { it.localDate in window }
            .map { it.localDate to TimeUtils.minutesOfDay(TimeUtils.localDateTime(it.epochMillis, it.zoneId).toLocalTime()) }
            .filter { it.second >= NIGHT_END }
        fun days(list: List<Pair<Long, Int>>) = list.map { it.first }.distinct().size
        val weekday = dayMeals.filterNot { Routine.isWeekend(LocalDate.ofEpochDay(it.first)) }
        val weekend = dayMeals.filter { Routine.isWeekend(LocalDate.ofEpochDay(it.first)) }
        // Weekdays and weekends are learned apart when each has enough days; otherwise all days together.
        val combined = if (days(dayMeals) >= MIN_DAYS) slots(dayMeals) else emptyList()
        val weekdaySlots = (if (days(weekday) >= MIN_DAYS) slots(weekday) else emptyList()).ifEmpty { combined }
        val weekendSlots = (if (days(weekend) >= MIN_WEEKEND_DAYS) slots(weekend) else emptyList()).ifEmpty { weekdaySlots }
        return Routine(
            weekdayMeals = weekdaySlots,
            weekendMeals = weekendSlots,
            weighMinute = weighMinute(weights, window),
            fluidCurve = fluidCurve(drinks, window),
            fluidMedianMl = fluidMedian(drinks, window),
        )
    }

    /** Groups meal times that lie within [CLUSTER_GAP] of each other; keeps groups seen on most days. */
    private fun slots(meals: List<Pair<Long, Int>>): List<MealSlot> {
        val days = meals.map { it.first }.distinct().size
        if (days == 0) return emptyList()
        val sorted = meals.sortedBy { it.second }
        val groups = mutableListOf<MutableList<Pair<Long, Int>>>()
        for (meal in sorted) {
            val last = groups.lastOrNull()
            if (last != null && meal.second - last.last().second <= CLUSTER_GAP) last += meal else groups += mutableListOf(meal)
        }
        return groups.flatMap(::split).mapNotNull { group ->
            val support = group.map { it.first }.distinct().size
            if (support < days * MIN_SUPPORT) null else MealSlot(median(group.map { it.second }), support)
        }
    }

    /** Snacks can chain breakfast and lunch together; a habit is narrower than [MAX_SLOT_WIDTH], so split at the widest gap. */
    private fun split(group: List<Pair<Long, Int>>): List<List<Pair<Long, Int>>> {
        if (group.size < 2 || group.last().second - group.first().second <= MAX_SLOT_WIDTH) return listOf(group)
        val cut = (1 until group.size).maxBy { group[it].second - group[it - 1].second }
        return split(group.subList(0, cut)) + split(group.subList(cut, group.size))
    }

    private fun weighMinute(weights: List<WeightEntity>, window: LongRange): Int? {
        val firstPerDay = weights.filter { it.localDate in window }.groupBy { it.localDate }
            .map { (_, l) -> l.minBy { it.epochMillis }.let { TimeUtils.minutesOfDay(TimeUtils.localDateTime(it.epochMillis, it.zoneId).toLocalTime()) } }
        return if (firstPerDay.size >= 5) median(firstPerDay) else null
    }

    private fun fluidCurve(drinks: List<DrinkEntity>, window: LongRange): List<Double> {
        val days = drinks.filter { it.localDate in window }.groupBy { it.localDate }.values.filter { l -> l.sumOf { it.volumeMl } > 0 }
        if (days.size < MIN_DAYS) return emptyList()
        return (0..24).map { hour ->
            val shares = days.map { list ->
                val total = list.sumOf { it.volumeMl }.toDouble()
                list.filter { TimeUtils.minutesOfDay(TimeUtils.localDateTime(it.epochMillis, it.zoneId).toLocalTime()) < hour * 60 }
                    .sumOf { it.volumeMl } / total
            }.sorted()
            if (hour == 24) 1.0 else shares[shares.size / 2]
        }
    }

    private fun fluidMedian(drinks: List<DrinkEntity>, window: LongRange): Int? {
        val totals = drinks.filter { it.localDate in window }.groupBy { it.localDate }.values.map { l -> l.sumOf { it.volumeMl } }.filter { it > 0 }
        return if (totals.size >= MIN_DAYS) median(totals) else null
    }

    private fun median(values: List<Int>): Int = values.sorted().let { s ->
        if (s.size % 2 == 1) s[s.size / 2] else ((s[s.size / 2 - 1] + s[s.size / 2]) / 2.0).roundToInt()
    }
}

/** Decisions for reminders that follow the user's routine instead of fixed times. */
object AdaptivePlan {
    /** Whether the routine has enough data for this reminder to adapt; otherwise configured times apply. */
    fun learned(type: ReminderType, routine: Routine): Boolean = when (type) {
        ReminderType.MEAL -> routine.weekdayMeals.isNotEmpty()
        ReminderType.WEIGHT -> routine.weighMinute != null
        ReminderType.WATER -> routine.fluidCurve.isNotEmpty()
        else -> false
    }

    /** Times planned for [date]: learned ones when adaptive and known, otherwise the configured ones; back-off applies. */
    fun timesFor(config: ReminderConfig, routine: Routine, ignoredInARow: Int, date: LocalDate): List<Int> {
        val configured = ReminderPlanner.configuredTimes(config)
        if (!config.adaptive || !config.type.supportsAdaptive) return configured
        val base = when (config.type) {
            ReminderType.MEAL -> mealTimes(routine, date).ifEmpty { configured }
            ReminderType.WEIGHT -> weighTimes(routine).ifEmpty { configured }
            // Water keeps its checkpoints; at each one the worker compares the day with the usual pace.
            else -> configured
        }
        return applyBackOff(base, ignoredInARow)
    }

    /** A meal reminder comes this long after the usual time, so an on-time meal never triggers it. */
    const val MEAL_GRACE = 45
    const val WEIGHT_GRACE = 30
    /** A meal logged this long before the usual time already counts for that slot. */
    const val MEAL_EARLY = 90
    /** After this many reminders in a row were swiped away, a reminder type comes at most once a day. */
    const val QUIET_AFTER_IGNORES = 3
    private const val SLOT_MATCH = 20

    fun mealTimes(routine: Routine, date: LocalDate): List<Int> = routine.mealSlots(date).map { it.minute + MEAL_GRACE }

    fun weighTimes(routine: Routine): List<Int> = listOfNotNull(routine.weighMinute?.plus(WEIGHT_GRACE))

    /**
     * The usual meal slot a reminder firing at [minute] belongs to: the latest one whose reminder
     * time has come, so a late or snoozed reminder still checks the right meal.
     */
    fun slotFor(routine: Routine, date: LocalDate, minute: Int): MealSlot? =
        routine.mealSlots(date).filter { it.minute + MEAL_GRACE - SLOT_MATCH <= minute }.maxByOrNull { it.minute }

    /** True when no meal was logged today from shortly before the usual time until now. */
    fun mealMissing(slot: MealSlot, mealMinutesToday: List<Int>, nowMinute: Int): Boolean =
        mealMinutesToday.none { it in (slot.minute - MEAL_EARLY)..nowMinute }

    /**
     * Fluid the user usually has by [minute]: their own guideline if set, otherwise their usual day,
     * spread over the day like their usual drinking. Null without enough history.
     */
    fun expectedFluid(routine: Routine, goalMl: Int?, minute: Int): Int? {
        val target = goalMl ?: routine.fluidMedianMl ?: return null
        val share = routine.fluidShareAt(minute) ?: return null
        return (target * share).roundToInt()
    }

    /** Behind by at least 15 % of the day (and 150 ml), so small differences never nag. */
    fun fluidBehind(totalMl: Int, expectedMl: Int, dayTargetMl: Int): Boolean =
        totalMl < dayTargetMl && expectedMl - totalMl >= maxOf(150, (dayTargetMl * 0.15).roundToInt())

    /** When quieted, only the first planned time of each day stays. */
    fun applyBackOff(times: List<Int>, ignoredInARow: Int): List<Int> =
        if (ignoredInARow >= QUIET_AFTER_IGNORES) times.sorted().take(1) else times
}

/** The optional question about how a meal went, asked once some time after it. */
object AfterMealPlan {
    private const val FUTURE_TOLERANCE = 5 * 60_000L

    /** Delay until the question; null for meals logged after that moment or planned ahead. */
    fun delayMillis(mealMillis: Long, nowMillis: Long, afterMinutes: Int): Long? {
        val at = mealMillis + afterMinutes * 60_000L
        if (mealMillis > nowMillis + FUTURE_TOLERANCE || at <= nowMillis) return null
        return at - nowMillis
    }

    /** At most once a day; after a few ignored questions in a row, at most every third day. */
    fun mayAsk(today: Long, lastAskedDay: Long?, ignoredInARow: Int): Boolean {
        if (lastAskedDay == null) return true
        val gap = if (ignoredInARow >= AdaptivePlan.QUIET_AFTER_IGNORES) 3 else 1
        return today - lastAskedDay >= gap
    }
}
