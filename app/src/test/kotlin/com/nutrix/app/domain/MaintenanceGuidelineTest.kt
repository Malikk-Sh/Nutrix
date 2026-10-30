package com.nutrix.app.domain

import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.model.ActivityLevel
import com.nutrix.app.core.model.FormulaSex
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.model.NutritionGoal
import com.nutrix.app.core.model.PlanSource
import com.nutrix.app.core.model.SpecialCircumstance
import com.nutrix.app.database.entity.IntakePlanEntity
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.feature.plan.ManualPlanInput
import org.junit.Test

class MaintenanceGuidelineTest {
    private val today = 20_400L
    private val base = GuidelineInputs(
        goal = NutritionGoal.MAINTAIN, heightCm = 168, weightKg = 56.0, weightDay = today - 1, birthYear = 1996,
        sex = FormulaSex.FEMALE, activity = ActivityLevel.LOW, circumstances = emptySet(), today = today, currentYear = 2026,
    )

    private fun ready(i: GuidelineInputs) = (MaintenanceGuideline.evaluate(i) as GuidelineResult.Ready).estimate

    @Test fun `resting energy follows the published equation`() {
        assertThat(MaintenanceGuideline.resting(56.0, 168, 30, FormulaSex.FEMALE)).isWithin(1e-9).of(1299.0)
        assertThat(MaintenanceGuideline.resting(80.0, 180, 40, FormulaSex.MALE)).isWithin(1e-9).of(1730.0)
    }

    @Test fun `maintenance is resting energy times activity, a range with the formula's error, never resting energy alone`() {
        val e = ready(base)
        assertThat(e.basis.restingMin).isEqualTo(1299)
        assertThat(e.kcal).isEqualTo(1950)
        assertThat(e.kcalMin).isEqualTo(1650)
        assertThat(e.kcalMax).isEqualTo(2300)
        assertThat(e.kcalMin).isGreaterThan(e.basis.restingMax)
        assertThat(e.basis.age).isEqualTo(30)
        assertThat(e.basis.weightDay).isEqualTo(today - 1)
    }

    @Test fun `macronutrient ranges are shares of the same energy and can be met together`() {
        val e = ready(base)
        assertThat(e.protein).isEqualTo(GramRange(49, 98))
        assertThat(e.fat).isEqualTo(GramRange(43, 76))
        assertThat(e.carbs).isEqualTo(GramRange(219, 293))
        listOf(base, base.copy(sex = FormulaSex.MALE, weightKg = 95.0, heightCm = 190, activity = ActivityLevel.HIGH),
            base.copy(sex = null, weightKg = 62.0, birthYear = 1950, activity = ActivityLevel.MODERATE)).forEach { i ->
            val x = ready(i)
            assertThat(PlanConsistency.of(x.kcalMin, x.kcalMax, x.protein, x.fat, x.carbs).consistent).isTrue()
            // Protein never falls below 0.83 g per kg of body weight.
            assertThat(x.protein.min.toDouble()).isAtLeast(0.83 * i.weightKg!! - 0.5)
        }
    }

    @Test fun `without a sex for the formula the range covers both variants`() {
        val known = ready(base)
        val unknown = ready(base.copy(sex = null))
        assertThat(unknown.basis.restingMin).isEqualTo(1299)
        assertThat(unknown.basis.restingMax).isEqualTo(1465)
        assertThat(unknown.kcalMin).isAtMost(known.kcalMin)
        assertThat(unknown.kcalMax).isGreaterThan(known.kcalMax)
    }

    @Test fun `only maintaining weight is computed, and never where the formula does not apply`() {
        fun reasons(i: GuidelineInputs) = (MaintenanceGuideline.evaluate(i) as GuidelineResult.NotApplicable).reasons
        assertThat(reasons(base.copy(goal = NutritionGoal.CHANGE))).containsExactly(NotApplicableReason.GOAL_CHANGE)
        assertThat(reasons(base.copy(goal = NutritionGoal.OBSERVE))).containsExactly(NotApplicableReason.GOAL_OBSERVE)
        assertThat(reasons(base.copy(goal = null))).containsExactly(NotApplicableReason.NO_GOAL)
        assertThat(reasons(base.copy(birthYear = 2010))).containsExactly(NotApplicableReason.UNDER_18)
        assertThat(reasons(base.copy(birthYear = 1940))).containsExactly(NotApplicableReason.OVER_80)
        assertThat(reasons(base.copy(weightKg = 45.0))).containsExactly(NotApplicableReason.LOW_BMI)
        assertThat(reasons(base.copy(circumstances = SpecialCircumstance.entries.toSet()))).containsExactly(
            NotApplicableReason.PREGNANCY_OR_LACTATION, NotApplicableReason.MEDICAL_DIET, NotApplicableReason.UNINTENDED_WEIGHT_LOSS)
    }

    @Test fun `missing or outdated inputs are named instead of guessed`() {
        fun missing(i: GuidelineInputs) = (MaintenanceGuideline.evaluate(i) as GuidelineResult.Missing).fields
        assertThat(missing(base.copy(heightCm = null, birthYear = null, activity = null)))
            .containsExactly(MissingField.HEIGHT, MissingField.BIRTH_YEAR, MissingField.ACTIVITY)
        assertThat(missing(base.copy(weightKg = null, weightDay = null))).containsExactly(MissingField.WEIGHT)
        assertThat(missing(base.copy(weightDay = today - 31))).containsExactly(MissingField.WEIGHT_OUTDATED)
        // A weight from 30 days ago is still recent enough; the target weight of the profile is never used.
        assertThat(MaintenanceGuideline.evaluate(base.copy(weightDay = today - 30))).isInstanceOf(GuidelineResult.Ready::class.java)
    }

    @Test fun `a manual plan needs a calorie range, whole macro ranges and is checked against 4-9-4`() {
        assertThat(ManualPlanInput(kcalMin = "1800").invalid).isTrue()
        assertThat(ManualPlanInput(kcalMin = "2000", kcalMax = "1800").invalid).isTrue()
        assertThat(ManualPlanInput(kcalMin = "1800", kcalMax = "2000", proteinMin = "60").invalid).isTrue()
        val ok = ManualPlanInput(kcalMin = "1800", kcalMax = "2000", proteinMin = "60", proteinMax = "90", fatMin = "50", fatMax = "70",
            carbsMin = "200", carbsMax = "260", note = " Диетолог ")
        assertThat(ok.invalid).isFalse()
        assertThat(ok.consistency.consistent).isTrue()
        assertThat(ok.toPlan()!!.note).isEqualTo("Диетолог")
        assertThat(ok.copy(carbsMin = "400", carbsMax = "450").consistency.tooHigh).isTrue()
        assertThat(ok.copy(carbsMin = "50", carbsMax = "60", fatMin = "10", fatMax = "20").consistency.tooLow).isTrue()
        // Calories alone are never "inconsistent".
        assertThat(ManualPlanInput(kcalMin = "1800", kcalMax = "2000").consistency.consistent).isTrue()
    }

    private fun plan(start: Long, source: PlanSource = PlanSource.MANUAL, min: Int? = 1800, max: Int? = 2200) =
        IntakePlanEntity(startDay = start, source = source, kcalMin = min, kcalMax = max)

    private fun day(d: Long, kcal: Int?, confirmed: Boolean = true) = DayIntakes.of(d,
        listOf(MealEntity(epochMillis = d * 86_400_000L, zoneId = "UTC", localDate = d, mealType = MealType.LUNCH, caloriesEstimate = kcal)),
        emptyList(), confirmed, today)

    @Test fun `each day keeps the guideline it had and only complete days are compared`() {
        val plans = listOf(plan(today - 10, min = 1600, max = 2000), plan(today - 5, PlanSource.NONE, null, null), plan(today - 2))
        assertThat(IntakePlans.activeOn(today - 11, plans)).isNull()
        assertThat(IntakePlans.activeOn(today - 7, plans)!!.kcalMax).isEqualTo(2000)
        assertThat(IntakePlans.activeOn(today - 4, plans)).isNull()
        assertThat(IntakePlans.activeOn(today, plans)!!.kcalMax).isEqualTo(2200)
        val days = listOf(
            day(today - 9, 1500), day(today - 8, 2100), day(today - 7, 1900), // 1600–2000: below, above, within
            day(today - 6, 1000, confirmed = false), // not confirmed: not compared
            day(today - 4, 3000), // no guideline that day
            day(today - 1, 2000), day(today - 1, null), // within; unknown
            day(today, 1900), // still running
        )
        assertThat(IntakePlans.compare(days, plans, Nutrient.KCAL)).isEqualTo(IntakePlans.Comparison(within = 2, below = 1, above = 1))
        assertThat(IntakePlans.compare(days, plans, Nutrient.PROTEIN)).isNull()
        assertThat(IntakePlans.range(plan(0, min = 1800, max = null), Nutrient.KCAL)).isNull()
    }
}
