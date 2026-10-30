package com.nutrix.app.domain

import com.nutrix.app.core.model.PlanSource
import com.nutrix.app.database.entity.IntakePlanEntity

object IntakePlans {
    /** The guideline a day had; "no guideline" plans and days before the first plan have none. */
    fun activeOn(day: Long, plans: List<IntakePlanEntity>): IntakePlanEntity? =
        plans.filter { it.startDay <= day }.maxByOrNull { it.startDay }?.takeIf { it.source != PlanSource.NONE }

    /** A range only when both bounds are set; one bound alone is not drawn or compared. */
    fun range(plan: IntakePlanEntity, n: Nutrient): GramRange? = when (n) {
        Nutrient.KCAL -> pair(plan.kcalMin, plan.kcalMax)
        Nutrient.PROTEIN -> pair(plan.proteinMin, plan.proteinMax)
        Nutrient.FAT -> pair(plan.fatMin, plan.fatMax)
        Nutrient.CARBS -> pair(plan.carbsMin, plan.carbsMax)
    }

    private fun pair(min: Int?, max: Int?) = if (min != null && max != null) GramRange(min, max) else null

    data class Comparison(val within: Int, val below: Int, val above: Int) {
        val days: Int get() = within + below + above
    }

    /**
     * Complete days against the guideline each of them had. Partial, unconfirmed and running days are not
     * compared at all: "known 900 kcal" is not "below the guideline".
     */
    fun compare(days: List<DayIntake>, plans: List<IntakePlanEntity>, n: Nutrient): Comparison? {
        var within = 0
        var below = 0
        var above = 0
        days.filter { it.usable(n) }.forEach { d ->
            val range = activeOn(d.day, plans)?.let { range(it, n) } ?: return@forEach
            val value = d.total(n).value ?: return@forEach
            when {
                value < range.min -> below++
                value > range.max -> above++
                else -> within++
            }
        }
        return Comparison(within, below, above).takeIf { it.days > 0 }
    }
}
