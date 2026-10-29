package com.nutrix.app.feature.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import com.nutrix.app.core.ui.icons.AutoAwesome
import com.nutrix.app.core.ui.icons.Sync
import androidx.compose.ui.Alignment
import com.nutrix.app.core.ui.components.IconBadge
import com.nutrix.app.core.ui.theme.LocalCategoryColors
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.nutrix.app.core.ui.motion.NutrixMotion
import com.nutrix.app.core.ui.motion.scanSweep
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nutrix.app.R
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixTextField
import com.nutrix.app.core.ui.components.PhotoThumb
import com.nutrix.app.core.util.Units
import com.nutrix.app.feature.food.FoodEditorUiState
import com.nutrix.app.feature.food.FoodEditorViewModel

@Composable
fun FoodAiCard(state: FoodEditorUiState, viewModel: FoodEditorViewModel, onSettings: () -> Unit, onReview: () -> Unit = {}) {
    var selected by rememberSaveable { mutableStateOf(state.form.photos.firstOrNull()) }
    var editHint by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.form.photos) { if (selected !in state.form.photos) selected = state.form.photos.firstOrNull() }
    LaunchedEffect(state.aiResult) { showDetails = false }
    val enabled = state.loaded && !state.isSaving && !state.processingPhoto && !state.analyzingPhoto && state.pendingDraft == null
    val success = !state.analyzingPhoto && state.aiError == null && state.aiResult != null
    val analyze = { selected?.let(viewModel::analyzePhoto); Unit }
    NutrixCard(modifier = Modifier.animateContentSize(tween(180))) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Rounded.AutoAwesome, MaterialTheme.colorScheme.primary, size = 30.dp)
            Text(stringResource(R.string.ai_photo_title), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(10.dp))
        if (!success) Text(stringResource(R.string.ai_disclosure), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.form.photos.size > 1) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.ai_choose_photo), style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.form.photos.forEachIndexed { index, photo ->
                    FilterChip(selected = photo == selected, enabled = enabled,
                        onClick = { viewModel.cancelAiAnalysis(); selected = photo },
                        label = { Text(stringResource(R.string.ai_photo_number, index + 1)) })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (!success || editHint) {
        NutrixTextField(state.form.aiHint, { value -> viewModel.update { it.copy(aiHint = value.take(1000)) } },
            stringResource(R.string.ai_hint), placeholder = stringResource(R.string.ai_hint_example), singleLine = false, minLines = 2, enabled = enabled)
        } else TextButton(onClick = { editHint = true }) { Text(stringResource(R.string.ai_hint)) }
        Spacer(Modifier.height(14.dp))
        if (state.analyzingPhoto) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (selected != null) {
                    // The photo being analysed is shown with a scanning light instead of a bare spinner.
                    Box(Modifier.testTag("food-ai-progress")) {
                        PhotoThumb(selected, Modifier.scanSweep(MaterialTheme.colorScheme.primary), size = 56.dp, shape = MaterialTheme.shapes.medium)
                    }
                } else {
                    CircularProgressIndicator(Modifier.size(36.dp).testTag("food-ai-progress"), strokeWidth = 3.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (state.aiRetrying) R.string.ai_retry_wait else R.string.ai_analyzing),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Text(stringResource(R.string.ai_attempt, state.aiAttempt, FoodEditorViewModel.MAX_AI_ATTEMPTS),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = viewModel::cancelAiAnalysis) { Text(stringResource(R.string.action_cancel)) }
        } else {
            state.aiError?.let { error ->
                val tint = if (error == FoodAiError.QUOTA) LocalCategoryColors.current.activity else MaterialTheme.colorScheme.error
                Surface(color = tint.copy(alpha = 0.1f), shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, tint.copy(alpha = 0.35f))) {
                    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Info, null, tint = tint, modifier = Modifier.size(20.dp))
                        Text(stringResource(error.messageRes), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("food-ai-error").semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            val choice = state.aiChoice
            if (success && choice != null) {
                AiChoice(choice, state, viewModel)
            } else if (success) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val pop = remember { androidx.compose.animation.core.Animatable(0.4f) }
                    LaunchedEffect(Unit) { pop.animateTo(1f, NutrixMotion.bouncy()) }
                    Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = pop.value })
                    Text(stringResource(R.string.ai_autofilled), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).testTag("food-ai-success").semantics { liveRegion = LiveRegionMode.Polite })
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onReview, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(14.dp)) {
                    Text(stringResource(R.string.ai_review_fields))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = analyze, enabled = enabled) { Text(stringResource(R.string.ai_analyze)) }
                    TextButton(onClick = { showDetails = true }) { Text(stringResource(R.string.ai_show_result)) }
                }
            } else if (state.hasGeminiKey) {
                Button(onClick = analyze, enabled = enabled && selected != null,
                    shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(14.dp),
                    modifier = Modifier.fillMaxWidth().testTag("food-ai-action")) {
                    Icon(if (state.aiError != null) Icons.Rounded.Sync else Icons.Rounded.AutoAwesome, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (state.aiError != null) R.string.ai_retry else R.string.ai_analyze))
                }
            } else {
                Text(stringResource(R.string.ai_error_key_missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!success) TextButton(onClick = onSettings, enabled = enabled) { Text(stringResource(R.string.ai_settings)) }
        }
    }
    if (showDetails) state.aiResult?.let { FoodAiResultDialog(it, onDismiss = { showDetails = false }) }
}

/**
 * A recognised photo that would change numbers already in the meal: the user picks a new dish, the same
 * dish seen again (with the change shown first) or, for a whole-meal estimate, a replacement.
 */
@Composable
private fun AiChoice(result: FoodPhotoResult, state: FoodEditorUiState, viewModel: FoodEditorViewModel) {
    val form = state.form
    @Composable fun kcal(value: String) = value.takeIf { it.isNotBlank() }?.let { stringResource(R.string.kcal_value, it) } ?: stringResource(R.string.kcal_unknown)
    val newKcal = kcal(result.estimate.caloriesKcal?.let { kotlin.math.round(it).toInt().toString() }.orEmpty())
    Column(Modifier.testTag("food-ai-choice"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.ai_choice_title, result.estimate.dishName), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.ai_choice_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = viewModel::applyAiAsNewDish, modifier = Modifier.fillMaxWidth().testTag("food-ai-new-dish"), shape = MaterialTheme.shapes.medium) {
            Text(stringResource(R.string.ai_choice_new_dish))
        }
        // Dishes already shown on this photo first: those are the likely "same dish from another angle".
        form.dishes.sortedByDescending { result.photoFile in it.photos }.forEach { dish ->
            OutlinedButton(onClick = { viewModel.applyAiToDish(dish.id) }, modifier = Modifier.fillMaxWidth().testTag("food-ai-update-${dish.id}"),
                shape = MaterialTheme.shapes.medium) {
                Text(stringResource(R.string.ai_choice_update, dish.name.ifBlank { stringResource(R.string.food_dish_name) }, kcal(dish.kcalText), newKcal))
            }
        }
        if (!form.hasDishes) {
            OutlinedButton(onClick = viewModel::applyAiReplace, modifier = Modifier.fillMaxWidth().testTag("food-ai-replace"), shape = MaterialTheme.shapes.medium) {
                Text(stringResource(R.string.ai_choice_replace, kcal(form.kcalText), newKcal))
            }
        }
        TextButton(onClick = viewModel::dismissAiChoice) { Text(stringResource(R.string.ai_choice_dismiss)) }
    }
}

@Composable
fun FoodAiResultDialog(result: FoodPhotoResult, onDismiss: () -> Unit) {
    val e = result.estimate
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ai_result_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.action_close)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PhotoThumb(result.photoFile, size = 88.dp)
                Column(Modifier.weight(1f)) {
                    Text(e.dishName, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.ai_portion_weight) + ": " + approximate(e.portionGrams) + " " + stringResource(R.string.unit_g), style = MaterialTheme.typography.bodyMedium)
                }
            }
            e.ingredients.forEach { ingredient ->
                Text("• " + ingredient.name + (ingredient.grams?.let { " · ≈" + number(it) + " " + stringResource(R.string.unit_g) } ?: ""), style = MaterialTheme.typography.bodyMedium)
            }
            NutritionTable(e.caloriesKcal, e.proteinGrams, e.fatGrams, e.carbsGrams, e.portionGrams)
            e.uncertainty?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.ai_result_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.action_close)) }
        }
    }
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
