package com.nutrix.app.domain

import com.nutrix.app.core.model.DrinkNutrition
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.model.MealDish
import com.nutrix.app.core.model.NutrientBasis
import kotlin.math.roundToInt

/**
 * A dish that is really a drink (juice, compote, a latte on the lunch photo) moved to the drinks diary, so its
 * volume counts as fluid and its energy counts once, as a drink. Served amounts become values per 100 ml when
 * the served mass is known (1 g ≈ 1 ml), so a corrected volume scales them; otherwise the eaten amounts are
 * kept for the whole drink.
 */
object DishAsDrink {
    /** Volume drunk: the served amount times the share that was had. */
    fun volume(d: MealDish): Int? = d.servedGrams?.takeIf { it > 0 }?.let { (it * d.eatenShare.coerceIn(0.0, 1.0)).roundToInt().coerceIn(1, 5000) }

    fun nutrition(d: MealDish): DrinkNutrition? {
        val kcal = d.kcal ?: return null
        val served = d.servedGrams?.takeIf { it > 0 }
        return if (served != null) {
            // Two decimals per 100 ml, so the drink gives back the dish's own amounts for its volume.
            fun per100(v: Double?) = v?.let { round2(it * 100 / served) }
            DrinkNutrition(round2(kcal * 100 / served), per100(d.proteinGrams), per100(d.fatGrams), per100(d.carbsGrams),
                NutrientBasis.PER_100_ML, d.estimateModel, d.uncertainty)
        } else {
            val share = d.eatenShare.coerceIn(0.0, 1.0)
            fun eaten(v: Double?) = v?.let { round1(it * share) }
            DrinkNutrition(round1(kcal * share), eaten(d.proteinGrams), eaten(d.fatGrams), eaten(d.carbsGrams),
                NutrientBasis.PER_DRINK, d.estimateModel, d.uncertainty)
        }
    }

    /** A first guess of the drink's type from its name; the user confirms it before anything is saved. */
    fun guessType(name: String): DrinkType {
        val n = name.lowercase()
        fun has(vararg words: String) = words.any { it in n }
        return when {
            has("кофе", "латте", "капучино", "американо", "эспрессо", "раф", "мокко", "coffee", "latte") -> DrinkType.COFFEE
            has("чай", "tea") -> DrinkType.TEA
            has("молок", "кефир", "ряженк", "айран", "простокваш", "milk") -> DrinkType.MILK
            has("сок", "морс", "компот", "нектар", "juice") -> DrinkType.JUICE
            has("вода", "минерал", "water") -> DrinkType.WATER
            else -> DrinkType.OTHER
        }
    }

    private fun round1(v: Double) = kotlin.math.round(v * 10) / 10
    private fun round2(v: Double) = kotlin.math.round(v * 100) / 100
}
