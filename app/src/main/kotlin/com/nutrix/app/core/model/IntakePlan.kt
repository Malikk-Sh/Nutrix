package com.nutrix.app.core.model

import kotlinx.serialization.Serializable

/** The sex constant of the resting-energy formula; left unset, the range covers both and is wider. */
@Serializable
enum class FormulaSex { FEMALE, MALE }

/** Physical activity level as a range of the activity coefficient (PAL); never added on top as "burned" calories. */
@Serializable
enum class ActivityLevel(val palMin: Double, val palMax: Double) {
    LOW(1.4, 1.6),
    MODERATE(1.6, 1.8),
    HIGH(1.8, 2.1),
}

@Serializable
enum class NutritionGoal { MAINTAIN, CHANGE, OBSERVE }

/** Situations where a formula is not enough: the app keeps records and the user's or a specialist's guideline only. */
@Serializable
enum class SpecialCircumstance { PREGNANCY_OR_LACTATION, MEDICAL_DIET, UNINTENDED_WEIGHT_LOSS }

@Serializable
enum class PlanSource {
    /** Maintenance range from the formula, accepted by the user. */
    FORMULA,
    /** Entered by the user, for example from a dietitian. */
    MANUAL,
    /** No calorie guideline from this day on. */
    NONE,
}

/** What a formula guideline was computed from, kept with it so later changes never rewrite its basis. */
@Serializable
data class PlanBasis(
    val weightKg: Double,
    val weightDay: Long,
    val heightCm: Int,
    val age: Int,
    val sex: FormulaSex?,
    val activity: ActivityLevel,
    val restingMin: Int,
    val restingMax: Int,
)
