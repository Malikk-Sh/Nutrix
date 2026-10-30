package com.nutrix.app.domain

import com.nutrix.app.core.model.ActivityLevel
import com.nutrix.app.core.model.FormulaSex
import com.nutrix.app.core.model.NutritionGoal
import com.nutrix.app.core.model.PlanBasis
import com.nutrix.app.core.model.SpecialCircumstance
import kotlin.math.max
import kotlin.math.roundToInt

/** What the maintenance estimate is computed from; every field the user has not given stays null. */
data class GuidelineInputs(
    val goal: NutritionGoal?,
    val heightCm: Int?,
    val weightKg: Double?,
    val weightDay: Long?,
    val birthYear: Int?,
    val sex: FormulaSex?,
    val activity: ActivityLevel?,
    val circumstances: Set<SpecialCircumstance>,
    val today: Long,
    val currentYear: Int,
)

/** A gram range of one macronutrient. */
data class GramRange(val min: Int, val max: Int)

data class MaintenanceEstimate(
    /** Most likely value: the middle of the resting estimate times the middle of the activity range. */
    val kcal: Int,
    val kcalMin: Int,
    val kcalMax: Int,
    val protein: GramRange,
    val fat: GramRange,
    val carbs: GramRange,
    val basis: PlanBasis,
)

sealed interface GuidelineResult {
    data class Ready(val estimate: MaintenanceEstimate) : GuidelineResult
    /** The formula is not for this situation; records and a specialist's guideline still work. */
    data class NotApplicable(val reasons: List<NotApplicableReason>) : GuidelineResult
    data class Missing(val fields: List<MissingField>) : GuidelineResult
}

enum class NotApplicableReason { GOAL_CHANGE, GOAL_OBSERVE, NO_GOAL, UNDER_18, OVER_80, LOW_BMI, PREGNANCY_OR_LACTATION, MEDICAL_DIET, UNINTENDED_WEIGHT_LOSS }

enum class MissingField { HEIGHT, WEIGHT, WEIGHT_OUTDATED, BIRTH_YEAR, ACTIVITY }

/**
 * A local, checkable starting point for maintaining weight, for healthy adults only: resting energy by the
 * Mifflin–St Jeor equation (1990) times the user's activity coefficient, shown as a range that includes the
 * formula's typical error. Resting energy is never a daily target. Macronutrient ranges are energy shares of
 * the same estimate (fat 20–35 %, carbohydrates 45–60 %, protein from 0.83 g per kg up to 20 %), so all
 * three can be met together. It is a guideline to compare recorded days with, not a prescription.
 */
object MaintenanceGuideline {
    /** Version of the method; stored with each accepted guideline. */
    const val METHOD = "mifflin_st_jeor_1990_pal_v1"
    /** The equation predicts resting energy within about ten percent for most adults it was tested on. */
    const val FORMULA_ERROR = 0.10
    const val MAX_WEIGHT_AGE_DAYS = 30
    private const val MIN_AGE = 18
    private const val MAX_AGE = 80
    private const val MIN_BMI = 18.5
    private const val PROTEIN_G_PER_KG = 0.83

    fun resting(weightKg: Double, heightCm: Int, age: Int, sex: FormulaSex): Double =
        10 * weightKg + 6.25 * heightCm - 5 * age + if (sex == FormulaSex.MALE) 5 else -161

    fun evaluate(i: GuidelineInputs): GuidelineResult {
        when (i.goal) {
            null -> return GuidelineResult.NotApplicable(listOf(NotApplicableReason.NO_GOAL))
            NutritionGoal.CHANGE -> return GuidelineResult.NotApplicable(listOf(NotApplicableReason.GOAL_CHANGE))
            NutritionGoal.OBSERVE -> return GuidelineResult.NotApplicable(listOf(NotApplicableReason.GOAL_OBSERVE))
            NutritionGoal.MAINTAIN -> Unit
        }
        val age = i.birthYear?.let { i.currentYear - it }
        val reasons = buildList {
            if (age != null && age < MIN_AGE) add(NotApplicableReason.UNDER_18)
            if (age != null && age > MAX_AGE) add(NotApplicableReason.OVER_80)
            if (SpecialCircumstance.PREGNANCY_OR_LACTATION in i.circumstances) add(NotApplicableReason.PREGNANCY_OR_LACTATION)
            if (SpecialCircumstance.MEDICAL_DIET in i.circumstances) add(NotApplicableReason.MEDICAL_DIET)
            if (SpecialCircumstance.UNINTENDED_WEIGHT_LOSS in i.circumstances) add(NotApplicableReason.UNINTENDED_WEIGHT_LOSS)
            if (i.weightKg != null && i.heightCm != null && i.weightKg / (i.heightCm / 100.0).let { it * it } < MIN_BMI) add(NotApplicableReason.LOW_BMI)
        }
        if (reasons.isNotEmpty()) return GuidelineResult.NotApplicable(reasons)
        val missing = buildList {
            if (i.heightCm == null) add(MissingField.HEIGHT)
            when {
                i.weightKg == null || i.weightDay == null -> add(MissingField.WEIGHT)
                i.today - i.weightDay > MAX_WEIGHT_AGE_DAYS -> add(MissingField.WEIGHT_OUTDATED)
            }
            if (age == null) add(MissingField.BIRTH_YEAR)
            if (i.activity == null) add(MissingField.ACTIVITY)
        }
        if (missing.isNotEmpty()) return GuidelineResult.Missing(missing)
        return GuidelineResult.Ready(estimate(i.weightKg!!, i.weightDay!!, i.heightCm!!, age!!, i.sex, i.activity!!))
    }

    fun estimate(weightKg: Double, weightDay: Long, heightCm: Int, age: Int, sex: FormulaSex?, activity: ActivityLevel): MaintenanceEstimate {
        val restingLow = resting(weightKg, heightCm, age, sex ?: FormulaSex.FEMALE)
        val restingHigh = resting(weightKg, heightCm, age, sex ?: FormulaSex.MALE)
        val kcal = round50((restingLow + restingHigh) / 2 * (activity.palMin + activity.palMax) / 2)
        return MaintenanceEstimate(
            kcal = kcal,
            kcalMin = round50(restingLow * activity.palMin * (1 - FORMULA_ERROR)),
            kcalMax = round50(restingHigh * activity.palMax * (1 + FORMULA_ERROR)),
            protein = GramRange(max(PROTEIN_G_PER_KG * weightKg, kcal * 0.10 / 4).roundToInt(), (kcal * 0.20 / 4).roundToInt()),
            fat = GramRange((kcal * 0.20 / 9).roundToInt(), (kcal * 0.35 / 9).roundToInt()),
            carbs = GramRange((kcal * 0.45 / 4).roundToInt(), (kcal * 0.60 / 4).roundToInt()),
            basis = PlanBasis(weightKg, weightDay, heightCm, age, sex, activity, restingLow.roundToInt(), restingHigh.roundToInt()),
        )
    }

    private fun round50(v: Double): Int = (v / 50).roundToInt() * 50
}

/** Whether macronutrient ranges can be met inside a calorie range at all (4/9/4 kcal per gram, with a 10 % margin for labels). */
data class PlanConsistency(val minKcalFromMacros: Int?, val maxKcalFromMacros: Int?, val kcalMin: Int?, val kcalMax: Int?) {
    val tooHigh: Boolean get() = minKcalFromMacros != null && kcalMax != null && minKcalFromMacros > kcalMax * 1.1
    val tooLow: Boolean get() = maxKcalFromMacros != null && kcalMin != null && maxKcalFromMacros < kcalMin * 0.9
    val consistent: Boolean get() = !tooHigh && !tooLow

    companion object {
        /** Only complete macro ranges are compared; a partly given plan is never called inconsistent. */
        fun of(kcalMin: Int?, kcalMax: Int?, protein: GramRange?, fat: GramRange?, carbs: GramRange?): PlanConsistency {
            if (protein == null || fat == null || carbs == null) return PlanConsistency(null, null, kcalMin, kcalMax)
            return PlanConsistency(protein.min * 4 + fat.min * 9 + carbs.min * 4, protein.max * 4 + fat.max * 9 + carbs.max * 4, kcalMin, kcalMax)
        }
    }
}
