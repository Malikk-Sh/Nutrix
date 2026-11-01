package com.nutrix.app.feature.water

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nutrix.app.R
import com.nutrix.app.core.model.DrinkNutrition
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.model.NutrientBasis
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.ui.components.ChoiceChips
import com.nutrix.app.core.ui.theme.LocalCategoryColors
import com.nutrix.app.core.util.Units
import com.nutrix.app.domain.intake
import com.nutrix.app.domain.withoutEnergy
import com.nutrix.app.feature.ai.DrinkEstimate
import androidx.compose.ui.platform.testTag
import com.nutrix.app.feature.food.NutritionValue

enum class EnergyMode { NONE, VALUE, UNKNOWN }

/** The editable form of a drink's energy, shared by the drink editor and the template dialog. */
data class EnergyInput(
    val mode: EnergyMode = EnergyMode.UNKNOWN,
    val basis: NutrientBasis = NutrientBasis.PER_100_ML,
    val kcalText: String = "",
    val proteinText: String = "",
    val fatText: String = "",
    val carbsText: String = "",
    /** Set while the values are exactly a Gemini photo estimate; any manual change clears it. */
    val estimateModel: String? = null,
    val uncertainty: String? = null,
) {
    private val maxKcal get() = if (basis == NutrientBasis.PER_100_ML) 900.0 else 5000.0
    private val maxGrams get() = if (basis == NutrientBasis.PER_100_ML) 100.0 else 1000.0
    private fun grams(text: String): Double? = Units.parseDecimal(text)?.takeIf { it in 0.0..maxGrams }
    private fun badGrams(text: String) = text.isNotBlank() && grams(text) == null

    val kcal: Double? get() = Units.parseDecimal(kcalText)?.takeIf { it in 0.0..maxKcal }

    /** Only "specify" can be wrong: without calories, a valid number is required; macros may stay empty. */
    val invalid: Boolean
        get() = mode == EnergyMode.VALUE && (kcal == null || badGrams(proteinText) || badGrams(fatText) || badGrams(carbsText))

    /** Null is unknown. Zero kcal entered by hand means the same as "without calories". */
    fun toNutrition(): DrinkNutrition? = when (mode) {
        EnergyMode.NONE -> DrinkNutrition.NONE
        EnergyMode.UNKNOWN -> null
        EnergyMode.VALUE -> kcal?.takeIf { !invalid }?.let { k ->
            if (k == 0.0) DrinkNutrition.NONE
            else DrinkNutrition(k, grams(proteinText), grams(fatText), grams(carbsText), basis, estimateModel, uncertainty?.takeIf(String::isNotBlank))
        }
    }

    /** Whole-drink amounts for [volumeMl]: kcal, then protein, fat and carbohydrates when entered. */
    fun totals(volumeMl: Int?): List<Double?>? = toNutrition()?.let { n ->
        val factor = if (n.basis == NutrientBasis.PER_100_ML) (volumeMl ?: return null) / 100.0 else 1.0
        listOf(n.kcal * factor, n.proteinGrams?.times(factor), n.fatGrams?.times(factor), n.carbsGrams?.times(factor))
    }

    /** A manual change: the values are no longer the model's estimate. */
    fun edited(): EnergyInput = copy(estimateModel = null, uncertainty = null)

    companion object {
        fun of(nutrition: DrinkNutrition?): EnergyInput = when {
            nutrition == null -> EnergyInput(EnergyMode.UNKNOWN)
            nutrition.isNone -> EnergyInput(EnergyMode.NONE)
            else -> EnergyInput(EnergyMode.VALUE, nutrition.basis, text(nutrition.kcal), nutrition.proteinGrams?.let(::text).orEmpty(),
                nutrition.fatGrams?.let(::text).orEmpty(), nutrition.carbsGrams?.let(::text).orEmpty(), nutrition.estimateModel, nutrition.uncertainty)
        }

        /**
         * A photo estimate for the whole drink, stored per 100 ml when the volume is known so that a corrected
         * volume scales it; near-zero drinks (plain water, tea, coffee) become "without calories".
         */
        fun fromEstimate(e: DrinkEstimate, model: String): EnergyInput {
            val kcal = e.caloriesKcal ?: return EnergyInput(EnergyMode.UNKNOWN)
            if (kcal < 0.5) return EnergyInput(EnergyMode.NONE)
            val volume = e.volumeMl
            fun per(v: Double?) = v?.let { text(if (volume != null) it * 100 / volume else it) }.orEmpty()
            return EnergyInput(EnergyMode.VALUE, if (volume != null) NutrientBasis.PER_100_ML else NutrientBasis.PER_DRINK,
                per(kcal), per(e.proteinGrams), per(e.fatGrams), per(e.carbsGrams), model, e.uncertainty)
        }

        fun defaultFor(type: DrinkType): EnergyInput = of(DrinkNutrition.defaultFor(type))

        /** Two decimals, so a value per 100 ml reproduces the whole-drink estimate; the app's decimal comma. */
        private fun text(v: Double): String = Units.roundTo(v, 2).let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString().replace('.', ',') }
    }
}

/** Approximate label values for common drinks; the user can always correct them from the actual label. */
data class DrinkPreset(@StringRes val label: Int, val nutrition: DrinkNutrition)

object DrinkPresets {
    private val sugarSpoon = DrinkPreset(R.string.preset_sugar_spoon, DrinkNutrition(20.0, 0.0, 0.0, 5.0, NutrientBasis.PER_DRINK))
    private val milk15 = DrinkPreset(R.string.preset_milk_15, DrinkNutrition(45.0, 3.0, 1.5, 4.8))
    private val milk25 = DrinkPreset(R.string.preset_milk_25, DrinkNutrition(52.0, 2.8, 2.5, 4.7))
    private val milk32 = DrinkPreset(R.string.preset_milk_32, DrinkNutrition(59.0, 2.9, 3.2, 4.7))
    private val kefir = DrinkPreset(R.string.preset_kefir_25, DrinkNutrition(50.0, 2.9, 2.5, 4.0))
    private val juice = DrinkPreset(R.string.preset_juice, DrinkNutrition(46.0, 0.5, 0.1, 11.0))
    private val soda = DrinkPreset(R.string.preset_soda, DrinkNutrition(42.0, 0.0, 0.0, 10.6))
    private val cappuccino = DrinkPreset(R.string.preset_cappuccino, DrinkNutrition(40.0, 2.0, 2.0, 3.2))
    private val milkTea = DrinkPreset(R.string.preset_tea_milk, DrinkNutrition(12.0, 0.6, 0.5, 1.0))
    private val cocoa = DrinkPreset(R.string.preset_cocoa, DrinkNutrition(74.0, 3.0, 2.7, 9.6))

    fun forType(type: DrinkType): List<DrinkPreset> = when (type) {
        DrinkType.WATER -> emptyList()
        DrinkType.TEA -> listOf(sugarSpoon, milkTea)
        DrinkType.COFFEE -> listOf(sugarSpoon, cappuccino)
        DrinkType.MILK -> listOf(milk15, milk25, milk32, kefir)
        DrinkType.JUICE -> listOf(juice)
        DrinkType.OTHER -> listOf(soda, kefir, cocoa, juice)
    }
}

/** Energy of a drink: without calories, a value from the label (or a preset), or not known yet. */
@Composable
fun DrinkEnergyEditor(input: EnergyInput, type: DrinkType, volumeMl: Int?, onEdit: (EnergyInput) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalCategoryColors.current
    val kcalUnit = stringResource(R.string.unit_kcal)
    val onChange: (EnergyInput) -> Unit = { onEdit(it.edited()) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        input.estimateModel?.let { model ->
            Text(stringResource(R.string.drink_energy_from_photo, model), style = MaterialTheme.typography.bodySmall, color = colors.water,
                modifier = Modifier.testTag("drink-energy-source"))
            input.uncertainty?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ChoiceChips(EnergyMode.entries, input.mode, { stringResource(when (it) {
            EnergyMode.NONE -> R.string.drink_energy_none
            EnergyMode.VALUE -> R.string.drink_energy_value
            EnergyMode.UNKNOWN -> R.string.drink_energy_unknown
        }) }, { m -> if (m != null) onChange(input.copy(mode = m)) }, allowDeselect = false, accent = colors.water)
        AnimatedVisibility(input.mode == EnergyMode.VALUE) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChips(NutrientBasis.entries, input.basis, { stringResource(if (it == NutrientBasis.PER_100_ML) R.string.drink_basis_100 else R.string.drink_basis_drink) },
                    { b -> if (b != null) onChange(input.copy(basis = b)) }, allowDeselect = false, accent = colors.water)
                val g = stringResource(R.string.unit_g)
                NutritionValue(stringResource(R.string.food_kcal_label), input.kcalText, kcalUnit, "drink-kcal",
                    { v -> onChange(input.copy(kcalText = v.take(7))) }, Modifier.fillMaxWidth())
                val fields = listOf(
                    Triple(R.string.food_protein_short, input.proteinText, "drink-protein"),
                    Triple(R.string.food_fat_short, input.fatText, "drink-fat"),
                    Triple(R.string.food_carbs_short, input.carbsText, "drink-carbs"),
                )
                @Composable fun field(index: Int, m: Modifier) {
                    val (label, value, tag) = fields[index]
                    NutritionValue(stringResource(label), value, g, tag, { v ->
                        onChange(when (index) {
                            0 -> input.copy(proteinText = v.take(6))
                            1 -> input.copy(fatText = v.take(6))
                            else -> input.copy(carbsText = v.take(6))
                        })
                    }, m)
                }
                if (LocalConfiguration.current.screenWidthDp >= 360 && LocalDensity.current.fontScale <= 1.2f) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { fields.indices.forEach { field(it, Modifier.weight(1f)) } }
                } else fields.indices.forEach { field(it, Modifier.fillMaxWidth()) }
                if (input.invalid) Text(stringResource(R.string.drink_energy_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else input.totals(volumeMl)?.takeIf { input.basis == NutrientBasis.PER_100_ML && volumeMl != null }?.let { t ->
                    Text(stringResource(R.string.drink_energy_total, Formatters.integer(Math.round(t[0] ?: 0.0)), volumeMl ?: 0) +
                        macrosSuffix(t[1], t[2], t[3]), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("drink-energy-total"))
                }
            }
        }
        if (input.mode == EnergyMode.UNKNOWN) Text(stringResource(R.string.drink_energy_unknown_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val presets = DrinkPresets.forType(type)
        if (presets.isNotEmpty()) {
            Text(stringResource(R.string.drink_presets_title), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { p ->
                    val per = if (p.nutrition.basis == NutrientBasis.PER_100_ML) stringResource(R.string.drink_preset_per_100)
                        else " " + stringResource(R.string.drink_preset_per_drink)
                    SuggestionChip(onClick = { onChange(EnergyInput.of(p.nutrition)) },
                        label = { Text("${stringResource(p.label)} · ${Formatters.integer(p.nutrition.kcal.toLong())} $kcalUnit$per") })
                }
            }
            Text(stringResource(R.string.drink_presets_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "≈90 ккал" or "ккал не указаны"; nothing for water and drinks without calories. */
@Composable
fun drinkKcalText(drink: com.nutrix.app.database.entity.DrinkEntity): String? {
    if (drink.withoutEnergy) return null
    val kcal = drink.intake.kcal ?: return stringResource(R.string.drink_kcal_unknown)
    return "≈${Formatters.integer(Math.round(kcal))} ${stringResource(R.string.unit_kcal)}"
}

/** "Б 6 · Ж 5 · У 9" for the whole drink, when its macronutrients are known. */
@Composable
fun drinkMacrosText(drink: com.nutrix.app.database.entity.DrinkEntity): String? {
    if (drink.withoutEnergy) return null
    val i = drink.intake
    return macrosSuffix(i.protein, i.fat, i.carbs).removePrefix(" · ").takeIf { it.isNotEmpty() }
}

/** " · Б 6 · Ж 5 · У 9" with only the known parts; empty when none is known. */
@Composable
fun macrosSuffix(protein: Double?, fat: Double?, carbs: Double?): String {
    val g = stringResource(R.string.unit_g)
    fun f(v: Double) = Formatters.decimal(v, if (v >= 10 || v % 1.0 == 0.0) 0 else 1)
    return listOfNotNull(protein?.let { "Б ${f(it)}" }, fat?.let { "Ж ${f(it)}" }, carbs?.let { "У ${f(it)} $g" })
        .joinToString(" · ").let { if (it.isEmpty()) "" else " · $it" }
}
