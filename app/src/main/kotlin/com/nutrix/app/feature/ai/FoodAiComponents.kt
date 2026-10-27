package com.nutrix.app.feature.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nutrix.app.R
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixTextField
import com.nutrix.app.core.ui.components.PhotoThumb
import com.nutrix.app.core.util.Units
import com.nutrix.app.feature.food.FoodEditorUiState
import com.nutrix.app.feature.food.FoodEditorViewModel

@Composable
fun FoodAiCard(state: FoodEditorUiState, viewModel: FoodEditorViewModel, onSettings: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(state.form.photos.firstOrNull()) }
    LaunchedEffect(state.form.photos) { if (selected !in state.form.photos) selected = state.form.photos.firstOrNull() }
    val enabled = state.loaded && !state.isSaving && !state.processingPhoto && !state.analyzingPhoto && state.pendingDraft == null
    NutrixCard {
        Text(stringResource(R.string.ai_photo_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.ai_disclosure), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.form.photos.size > 1) {
            Text(stringResource(R.string.ai_choose_photo), style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.form.photos.forEachIndexed { index, photo ->
                    FilterChip(selected = photo == selected, enabled = enabled,
                        onClick = { viewModel.cancelAiAnalysis(); selected = photo },
                        label = { Text(stringResource(R.string.ai_photo_number, index + 1)) })
                }
            }
        }
        selected?.let { PhotoThumb(it, size = 72.dp) }
        NutrixTextField(state.form.aiHint, { value -> viewModel.update { it.copy(aiHint = value.take(1000)) } },
            stringResource(R.string.ai_hint), singleLine = false, enabled = enabled)
        if (state.analyzingPhoto) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.ai_analyzing))
            TextButton(onClick = viewModel::cancelAiAnalysis) { Text(stringResource(R.string.action_cancel)) }
        } else {
            Button(onClick = { selected?.let(viewModel::analyzePhoto) }, enabled = enabled && selected != null && state.hasGeminiKey) { Text(stringResource(R.string.ai_analyze)) }
            if (!state.hasGeminiKey) Text(stringResource(R.string.ai_error_key_missing), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onSettings, enabled = enabled) { Text(stringResource(R.string.ai_settings)) }
        state.aiError?.let { Text(stringResource(it.messageRes), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun FoodAiResultDialog(result: FoodPhotoResult, replacing: Boolean, onApply: () -> Unit, onDismiss: () -> Unit) {
    val e = result.estimate
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_result_title)) },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PhotoThumb(result.photoFile, size = 72.dp)
                Text(e.dishName, style = MaterialTheme.typography.titleMedium)
                e.ingredients.forEach { ingredient ->
                    Text("• ${ingredient.name}" + (ingredient.grams?.let { " · ≈${number(it)} ${stringResource(R.string.unit_g)}" } ?: ""))
                }
                Text(stringResource(R.string.ai_portion_weight) + ": " + approximate(e.portionGrams) + " " + stringResource(R.string.unit_g))
                NutritionTable(e.caloriesKcal, e.proteinGrams, e.fatGrams, e.carbsGrams, e.portionGrams)
                e.uncertainty?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.ai_result_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onApply) { Text(stringResource(if (replacing) R.string.ai_replace else R.string.ai_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun NutritionTable(kcal: Double?, protein: Double?, fat: Double?, carbs: Double?, grams: Double?) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row {
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.ai_portion), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.ai_per100), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
        }
        listOf(R.string.ai_calories to kcal, R.string.ai_protein to protein, R.string.ai_fat to fat, R.string.ai_carbs to carbs).forEach { (label, value) ->
            Row {
                Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(approximate(value), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(approximate(if (grams != null && grams > 0) value?.let { it * 100 / grams } else null), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun number(value: Double): String = Units.roundTo(value, 1).toString().removeSuffix(".0").replace('.', ',')
private fun approximate(value: Double?): String = value?.let { "≈${number(it)}" } ?: "—"
