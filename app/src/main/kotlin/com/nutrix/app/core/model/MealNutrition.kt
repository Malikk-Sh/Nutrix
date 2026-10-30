package com.nutrix.app.core.model

import kotlinx.serialization.Serializable

/**
 * Approximate grams for the whole portion. Per-100 g values are derived from its current weight.
 * For a meal made of dishes these are the eaten totals written by MealTotals, and the counts below say
 * how many of the dishes each total covers.
 */
@Serializable
data class MealNutrition(
    val proteinGrams: Double? = null,
    val fatGrams: Double? = null,
    val carbsGrams: Double? = null,
    val estimateModel: String? = null,
    val estimatedAt: Long? = null,
    val uncertainty: String? = null,
    val dishCount: Int? = null,
    val gramsKnown: Int? = null,
    val kcalKnown: Int? = null,
    val proteinKnown: Int? = null,
    val fatKnown: Int? = null,
    val carbsKnown: Int? = null,
)
