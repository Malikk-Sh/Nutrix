package com.nutrix.app.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** Where a dish's numbers come from; shown next to them so an estimate never passes for a measurement. */
@Serializable
enum class DishSource { MANUAL, PHOTO, REFERENCE, FAVORITE, MEAL_ESTIMATE }

/**
 * One dish, product or caloric drink of a meal. Mass and nutrients describe the portion served; what was
 * eaten is that portion times [eatenShare], computed in one place (see MealTotals). Unknown stays null.
 */
@Serializable
data class MealDish(
    /** Stable across edits, so a recognised photo can update "this" dish instead of adding a copy. */
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** What the dish is made of, as recognised or typed; no amounts are invented for them. */
    val components: List<String> = emptyList(),
    val servedGrams: Double? = null,
    /** 0..1 of the served portion that was eaten. */
    val eatenShare: Double = 1.0,
    val kcal: Double? = null,
    val proteinGrams: Double? = null,
    val fatGrams: Double? = null,
    val carbsGrams: Double? = null,
    val source: DishSource = DishSource.MANUAL,
    /** Meal photos that show this dish; one photo may show several dishes and several photos one dish. */
    val photos: List<String> = emptyList(),
    val estimateModel: String? = null,
    val uncertainty: String? = null,
)
