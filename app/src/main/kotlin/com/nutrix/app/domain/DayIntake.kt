package com.nutrix.app.domain

import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.model.NutrientBasis
import com.nutrix.app.database.entity.DrinkEntity
import com.nutrix.app.database.entity.MealEntity
import kotlin.math.roundToLong

enum class Nutrient { KCAL, PROTEIN, FAT, CARBS }

/** What one drink adds to the day; null is unknown. Water and drinks marked as without calories add a known zero. */
data class DrinkIntake(val kcal: Double?, val protein: Double?, val fat: Double?, val carbs: Double?) {
    fun of(n: Nutrient): Double? = when (n) {
        Nutrient.KCAL -> kcal
        Nutrient.PROTEIN -> protein
        Nutrient.FAT -> fat
        Nutrient.CARBS -> carbs
    }

    companion object {
        val ZERO = DrinkIntake(0.0, 0.0, 0.0, 0.0)
        val UNKNOWN = DrinkIntake(null, null, null, null)
    }
}

/** A drink that can never change the day's energy, so it is not one of the records the day's total depends on. */
val DrinkEntity.withoutEnergy: Boolean get() = drinkType == DrinkType.WATER || nutrition?.isNone == true

val DrinkEntity.intake: DrinkIntake
    get() {
        if (withoutEnergy) return DrinkIntake.ZERO
        val n = nutrition ?: return DrinkIntake.UNKNOWN
        val factor = if (n.basis == NutrientBasis.PER_100_ML) volumeMl / 100.0 else 1.0
        return DrinkIntake(n.kcal * factor, n.proteinGrams?.times(factor), n.fatGrams?.times(factor), n.carbsGrams?.times(factor))
    }

/**
 * A meal's part of the day: [value] can be the known part of a meal whose dishes are only partly estimated
 * (so the day's known sum matches what the diary shows), but only a value covering the whole meal is [complete].
 */
data class MealPart(val value: Double?, val complete: Boolean)

fun MealEntity.part(n: Nutrient): MealPart = when (n) {
    Nutrient.KCAL -> MealPart(caloriesEstimate?.toDouble(), kcalIfComplete != null)
    Nutrient.PROTEIN -> MealPart(nutrition?.proteinGrams, macroIfComplete({ it.proteinGrams }, { it.proteinKnown }) != null)
    Nutrient.FAT -> MealPart(nutrition?.fatGrams, macroIfComplete({ it.fatGrams }, { it.fatKnown }) != null)
    Nutrient.CARBS -> MealPart(nutrition?.carbsGrams, macroIfComplete({ it.carbsGrams }, { it.carbsKnown }) != null)
}

enum class DayState {
    /** Nothing eaten and no drink with calories recorded: unknown, never zero. */
    NO_RECORDS,
    /** The current day, still being recorded. */
    TODAY,
    /** Some record of the day has no value for this nutrient: only "known X" can be said. */
    PARTIAL,
    /** Every record is estimated, but the user has not said that every meal and drink is recorded. */
    UNCONFIRMED,
    /** A finished day the user confirmed, with every record estimated: it can stand for the day's intake. */
    COMPLETE,
}

/** Food and caloric drinks of one day, summed once by the one rule the diary, charts and observations share. */
data class DayIntake(
    val day: Long,
    val meals: List<MealEntity>,
    /** Drinks that can carry energy; water and drinks without calories are left out. */
    val drinks: List<DrinkEntity>,
    val confirmed: Boolean,
    val isToday: Boolean,
) {
    val records: Int get() = meals.size + drinks.size

    fun total(n: Nutrient): PartialTotal {
        val parts = meals.map { it.part(n) } + drinks.map { d -> d.intake.of(n).let { MealPart(it, it != null) } }
        val values = parts.mapNotNull { it.value }
        return PartialTotal(values.takeIf { it.isNotEmpty() }?.sum(), parts.count { it.complete }, parts.size)
    }

    fun fromMeals(n: Nutrient): Double? = meals.mapNotNull { it.part(n).value }.takeIf { it.isNotEmpty() }?.sum()
    fun fromDrinks(n: Nutrient): Double? = drinks.mapNotNull { it.intake.of(n) }.takeIf { it.isNotEmpty() }?.sum()

    fun state(n: Nutrient): DayState = when {
        records == 0 -> DayState.NO_RECORDS
        isToday -> DayState.TODAY
        !total(n).complete -> DayState.PARTIAL
        !confirmed -> DayState.UNCONFIRMED
        else -> DayState.COMPLETE
    }

    /** Counts as the day's intake of [n]; any other day is at most "known X". */
    fun usable(n: Nutrient): Boolean = state(n) == DayState.COMPLETE

    /** Energy from protein, fat and carbohydrates by 4/9/4 kcal per gram: a rough cross-check of the estimate only. */
    val kcalFromMacros: Double?
        get() {
            val (p, f, c) = listOf(Nutrient.PROTEIN, Nutrient.FAT, Nutrient.CARBS).map { total(it) }
            if (!p.complete || !f.complete || !c.complete || records == 0) return null
            return (p.value ?: 0.0) * 4 + (f.value ?: 0.0) * 9 + (c.value ?: 0.0) * 4
        }
}

object DayIntakes {
    fun of(day: Long, meals: List<MealEntity>, drinks: List<DrinkEntity>, confirmed: Boolean, today: Long?): DayIntake = DayIntake(
        day = day,
        meals = meals.filter { it.localDate == day }.sortedBy { it.epochMillis },
        drinks = drinks.filter { it.localDate == day && !it.withoutEnergy }.sortedBy { it.epochMillis },
        confirmed = confirmed,
        isToday = day == today,
    )

    /** Every day of the range, empty ones included, so a missing day stays missing instead of becoming a zero. */
    fun range(records: AnalyticsInput, today: Long?): List<DayIntake> {
        val meals = records.meals.groupBy { it.localDate }
        val drinks = records.drinks.groupBy { it.localDate }
        return (records.from..records.to).map { day ->
            of(day, meals[day].orEmpty(), drinks[day].orEmpty(), day in records.confirmedDays, today)
        }
    }

    data class Average(val value: Double?, val days: Int)

    /** The mean over usable days only; days that are partial, unconfirmed or still running are left out, not filled in. */
    fun average(days: List<DayIntake>, n: Nutrient): Average {
        val values = days.filter { it.usable(n) }.mapNotNull { it.total(n).value }
        return Average(values.takeIf { it.isNotEmpty() }?.average(), values.size)
    }

    /** Rounds for display the way the diary does: whole kcal, grams with one decimal under ten. */
    fun rounded(n: Nutrient, value: Double): Double =
        if (n == Nutrient.KCAL || value >= 10) value.roundToLong().toDouble() else kotlin.math.round(value * 10) / 10
}
