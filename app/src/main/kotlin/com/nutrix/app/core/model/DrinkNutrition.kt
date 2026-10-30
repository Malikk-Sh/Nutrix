package com.nutrix.app.core.model

import kotlinx.serialization.Serializable

/** What the entered values refer to: a label's "per 100 ml", or the whole drink (a spoon of sugar does not grow with the mug). */
@Serializable
enum class NutrientBasis { PER_100_ML, PER_DRINK }

/**
 * Energy of a drink as the user entered or picked it. A drink without this is unknown, never zero;
 * water needs none. Values are approximate, like every estimate in the diary.
 */
@Serializable
data class DrinkNutrition(
    val kcal: Double,
    val proteinGrams: Double? = null,
    val fatGrams: Double? = null,
    val carbsGrams: Double? = null,
    val basis: NutrientBasis = NutrientBasis.PER_100_ML,
) {
    /** A drink marked as having no calories (tea or coffee without additions). */
    val isNone: Boolean get() = kcal == 0.0

    companion object {
        val NONE = DrinkNutrition(0.0, 0.0, 0.0, 0.0, NutrientBasis.PER_DRINK)

        /**
         * What a new drink of [type] starts with: tea and coffee without additions have no calories, water
         * never needs a value, and milk, juice or anything else stays unknown until the user says.
         */
        fun defaultFor(type: DrinkType): DrinkNutrition? = when (type) {
            DrinkType.TEA, DrinkType.COFFEE -> NONE
            else -> null
        }
    }
}
