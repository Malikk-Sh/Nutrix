package com.nutrix.app.domain

import com.nutrix.app.core.model.MealDish
import com.nutrix.app.core.model.MealNutrition
import com.nutrix.app.database.entity.MealEntity
import kotlin.math.roundToInt

/** A sum that knows how many of its parts it covers: "known 650 kcal, 2 of 3 dishes" or "of 4 records of the day". */
data class PartialTotal(val value: Double?, val known: Int, val of: Int) {
    val complete: Boolean get() = known == of
}

data class DishTotals(
    val dishes: Int,
    val grams: PartialTotal,
    val kcal: PartialTotal,
    val protein: PartialTotal,
    val fat: PartialTotal,
    val carbs: PartialTotal,
)

/**
 * The one place where what was eaten is computed: each dish's served portion times the eaten share,
 * summed per nutrient. Unknown values stay unknown; they are never counted as zero. Photos have no
 * effect on the numbers, so another photo of the same dish cannot count it twice.
 */
object MealTotals {
    fun eaten(dish: MealDish, value: Double?): Double? = value?.let { it * dish.eatenShare.coerceIn(0.0, 1.0) }

    fun of(dishes: List<MealDish>): DishTotals {
        fun total(pick: (MealDish) -> Double?): PartialTotal {
            val known = dishes.mapNotNull { d -> eaten(d, pick(d)) }
            return PartialTotal(if (known.isEmpty()) null else known.sum(), known.size, dishes.size)
        }
        return DishTotals(
            dishes = dishes.size,
            grams = total { it.servedGrams },
            kcal = total { it.kcal },
            protein = total { it.proteinGrams },
            fat = total { it.fatGrams },
            carbs = total { it.carbsGrams },
        )
    }

    /**
     * Writes the dishes' eaten totals into the meal's summary fields, which the diary, reports and
     * observations read. Partial totals are kept with their counts, so readers can tell them apart.
     * Without dishes the meal's own whole-meal estimate is left as it is.
     */
    fun apply(meal: MealEntity): MealEntity {
        val dishes = meal.dishes.orEmpty()
        if (dishes.isEmpty()) return meal.copy(dishes = null)
        val t = of(dishes)
        val estimated = dishes.filter { it.estimateModel != null }
        return meal.copy(
            portionGrams = t.grams.value?.roundToInt(),
            caloriesEstimate = t.kcal.value?.roundToInt(),
            nutrition = MealNutrition(
                proteinGrams = t.protein.value?.let(::round1),
                fatGrams = t.fat.value?.let(::round1),
                carbsGrams = t.carbs.value?.let(::round1),
                estimateModel = estimated.map { it.estimateModel }.distinct().singleOrNull(),
                dishCount = t.dishes,
                gramsKnown = t.grams.known,
                kcalKnown = t.kcal.known,
                proteinKnown = t.protein.known,
                fatKnown = t.fat.known,
                carbsKnown = t.carbs.known,
            ),
            // Names and components stay searchable and visible where only a list of names fits.
            items = dishes.flatMap { listOf(it.name) + it.components }.map(String::trim).filter(String::isNotEmpty).distinct(),
        )
    }

    private fun round1(v: Double) = kotlin.math.round(v * 10) / 10
}

/** Calories of the meal only when they cover all of it; a partial sum is not the meal's intake. */
val MealEntity.kcalIfComplete: Int?
    get() = caloriesEstimate?.takeIf { nutrition?.let { n -> n.dishCount == null || n.kcalKnown == n.dishCount } ?: true }

/** Eaten grams only when every dish has a mass. */
val MealEntity.gramsIfComplete: Int?
    get() = portionGrams?.takeIf { nutrition?.let { n -> n.dishCount == null || n.gramsKnown == n.dishCount } ?: true }

/** A macronutrient total only when every dish has it. */
fun MealEntity.macroIfComplete(pick: (MealNutrition) -> Double?, known: (MealNutrition) -> Int?): Double? {
    val n = nutrition ?: return null
    return pick(n)?.takeIf { n.dishCount == null || known(n) == n.dishCount }
}
