package com.nutrix.app.core.util

import com.nutrix.app.core.model.WeightUnit
import com.nutrix.app.core.time.Formatters
import kotlin.math.roundToInt

object Units {
    const val KG_PER_LB = 0.45359237

    fun toDisplay(kg: Double, unit: WeightUnit): Double = when (unit) {
        WeightUnit.KG -> kg
        WeightUnit.LB -> kg / KG_PER_LB
    }

    fun toKg(value: Double, unit: WeightUnit): Double = when (unit) {
        WeightUnit.KG -> value
        WeightUnit.LB -> value * KG_PER_LB
    }

    fun formatWeight(kg: Double, unit: WeightUnit, unitLabel: String): String =
        "${Formatters.decimal(toDisplay(kg, unit), 1)} $unitLabel"

    /** Parses user input like "54,8" or "54.8". */
    fun parseDecimal(text: String): Double? =
        text.trim().replace(',', '.').replace(" ", "").toDoubleOrNull()

    fun roundTo(value: Double, digits: Int): Double {
        var factor = 1.0
        repeat(digits) { factor *= 10 }
        return (value * factor).roundToInt() / factor
    }

    fun isPlausibleWeightKg(kg: Double): Boolean = kg in 2.0..400.0
}
