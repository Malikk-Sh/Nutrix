package com.nutrix.app.feature.food

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nutrix.app.R
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixTextField
import com.nutrix.app.core.util.Units
import com.nutrix.app.feature.ai.NutritionTable

/** Always visible and editable. Portion mass is deliberately separate from body weight. */
@Composable
fun NutritionCard(state: FoodEditorUiState, viewModel: FoodEditorViewModel, onEstimateFailed: () -> Unit) {
    val form = state.form
    var showTable by rememberSaveable { mutableStateOf(false) }
    val inline = LocalConfiguration.current.screenWidthDp >= 360 && LocalDensity.current.fontScale <= 1.2f
    NutrixCard {
        Text(stringResource(R.string.food_nutrition_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.food_nutrition_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NutritionValue(stringResource(R.string.food_portion_mass), form.gramsText, stringResource(R.string.unit_g), "food-grams",
                { value -> viewModel.update { it.copy(gramsText = value.filter(Char::isDigit).take(5)) } }, Modifier.weight(1f), KeyboardType.Number)
            NutritionValue(stringResource(R.string.food_kcal_label), form.kcalText, stringResource(R.string.unit_kcal), "food-kcal",
                { value -> viewModel.update { it.copy(kcalText = value.filter(Char::isDigit).take(5), kcalFromReference = false) } }, Modifier.weight(1f), KeyboardType.Number)
        }
        Spacer(Modifier.height(12.dp))
        val fields = listOf(
            Triple(R.string.food_protein_short, form.proteinText, "food-protein"),
            Triple(R.string.food_fat_short, form.fatText, "food-fat"),
            Triple(R.string.food_carbs_short, form.carbsText, "food-carbs"),
        )
        @Composable fun field(index: Int, modifier: Modifier) {
            val (label, value, tag) = fields[index]
            NutritionValue(stringResource(label), value, stringResource(R.string.unit_g), tag, { newValue ->
                viewModel.update { when (index) {
                    0 -> it.copy(proteinText = newValue.take(8))
                    1 -> it.copy(fatText = newValue.take(8))
                    else -> it.copy(carbsText = newValue.take(8))
                } }
            }, modifier)
        }
        if (inline) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fields.indices.forEach { field(it, Modifier.weight(1f)) }
        } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            fields.indices.forEach { field(it, Modifier.fillMaxWidth()) }
        }
        if (viewModel.isNutritionInvalid()) Text(stringResource(R.string.ai_invalid_macros), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (state.calorieEstimatesEnabled) TextButton(onClick = { if (!viewModel.estimateCalories()) onEstimateFailed() }) {
            Text(stringResource(R.string.food_kcal_estimate))
        }
        if (listOf(form.proteinText, form.fatText, form.carbsText).any(String::isNotBlank) || form.nutritionInfo != null) {
            TextButton(onClick = { showTable = !showTable }) { Text(stringResource(R.string.ai_per100)) }
            AnimatedVisibility(showTable) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NutritionTable(form.kcalText.toDoubleOrNull(), Units.parseDecimal(form.proteinText), Units.parseDecimal(form.fatText), Units.parseDecimal(form.carbsText), form.gramsText.toDoubleOrNull())
                    form.nutritionInfo?.uncertainty?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun NutritionValue(label: String, value: String, unit: String, tag: String, onValue: (String) -> Unit,
    modifier: Modifier, keyboard: KeyboardType = KeyboardType.Decimal) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        NutrixTextField(value, onValue, label, modifier = Modifier.testTag(tag), suffix = unit,
            keyboardType = keyboard, textStyle = MaterialTheme.typography.titleLarge, showLabel = false)
    }
}
