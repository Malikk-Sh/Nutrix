package com.nutrix.app.core.model

import kotlinx.serialization.Serializable

/** Approximate grams for the whole portion. Per-100 g values are derived from its current weight. */
@Serializable
data class MealNutrition(
    val proteinGrams: Double? = null,
    val fatGrams: Double? = null,
    val carbsGrams: Double? = null,
    val estimateModel: String? = null,
    val estimatedAt: Long? = null,
    val uncertainty: String? = null,
)
