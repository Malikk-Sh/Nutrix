package com.nutrix.app.feature.water

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nutrix.app.R
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.ui.components.IconBadge
import com.nutrix.app.core.ui.components.NutrixTextField
import com.nutrix.app.core.ui.components.PhotoThumb
import com.nutrix.app.core.ui.icons.AutoAwesome
import com.nutrix.app.core.ui.icons.Sync
import com.nutrix.app.core.ui.labelRes
import com.nutrix.app.core.ui.motion.scanSweep
import com.nutrix.app.core.ui.theme.LocalCategoryColors
import com.nutrix.app.feature.ai.DrinkPhotoResult
import com.nutrix.app.feature.ai.FoodAiError
import com.nutrix.app.feature.ai.messageRes
import com.nutrix.app.feature.food.FoodEditorViewModel

/**
 * Recognition of the drink on its photo with the drink prompt: type, volume, energy and macronutrients for
 * the whole drink. A fresh drink is filled at once; one with values already set asks before replacing them.
 */
@Composable
fun DrinkAiSection(
    photo: String,
    hint: String,
    ai: DrinkAiState,
    hasKey: Boolean,
    enabled: Boolean,
    onHint: (String) -> Unit,
    onAnalyze: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = LocalCategoryColors.current
    Column(Modifier.fillMaxWidth().animateContentSize(tween(180)).testTag("drink-ai"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBadge(Icons.Rounded.AutoAwesome, colors.water, size = 28.dp)
            Text(stringResource(R.string.drink_ai_title), style = MaterialTheme.typography.titleSmall)
        }
        val result = ai.result
        if (result == null && !ai.analyzing) {
            Text(stringResource(R.string.drink_ai_disclosure), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!hasKey) {
            Text(stringResource(R.string.ai_error_key_missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onSettings) { Text(stringResource(R.string.ai_settings)) }
            return@Column
        }
        if (ai.analyzing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PhotoThumb(photo, Modifier.scanSweep(colors.water).testTag("drink-ai-progress"), size = 56.dp, shape = MaterialTheme.shapes.medium)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (ai.retrying) R.string.ai_retry_wait else R.string.drink_ai_analyzing), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Text(stringResource(R.string.ai_attempt, ai.attempt, FoodEditorViewModel.MAX_AI_ATTEMPTS),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            return@Column
        }
        ai.error?.let { error ->
            val tint = if (error == FoodAiError.QUOTA) colors.activity else MaterialTheme.colorScheme.error
            Surface(color = tint.copy(alpha = 0.1f), shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, tint.copy(alpha = 0.35f))) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Info, null, tint = tint, modifier = Modifier.size(20.dp))
                    Text(stringResource(error.messageRes), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("drink-ai-error").semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
        if (result != null) {
            ResultSummary(result)
            if (ai.choice != null) {
                Text(stringResource(R.string.drink_ai_choice_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onApply, modifier = Modifier.fillMaxWidth().testTag("drink-ai-apply"), shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.drink_ai_apply))
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.drink_ai_dismiss))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Check, null, tint = colors.water)
                    Text(stringResource(R.string.drink_ai_filled), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("drink-ai-success").semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
        NutrixTextField(hint, { onHint(it.take(300)) }, stringResource(R.string.ai_hint), placeholder = stringResource(R.string.drink_ai_hint_example),
            enabled = enabled)
        Spacer(Modifier.height(2.dp))
        Button(onClick = onAnalyze, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("drink-ai-action"),
            shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(14.dp)) {
            Icon(if (ai.error != null || result != null) Icons.Rounded.Sync else Icons.Rounded.AutoAwesome, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (ai.error != null) R.string.ai_retry else if (result != null) R.string.drink_ai_again else R.string.drink_ai_analyze))
        }
    }
}

/** What Gemini saw: name, volume, whole-drink energy and macronutrients, additions and doubts. */
@Composable
private fun ResultSummary(result: DrinkPhotoResult) {
    val e = result.estimate
    val unit = stringResource(R.string.unit_kcal)
    fun n(v: Double) = Formatters.decimal(v, if (v >= 10 || v % 1.0 == 0.0) 0 else 1)
    val parts = listOfNotNull(
        "${e.drinkName} (${stringResource(e.type.labelRes)})",
        e.volumeMl?.let { "≈${n(it)} ${stringResource(R.string.unit_ml)}" },
        e.caloriesKcal?.let { "≈${n(it)} $unit" } ?: stringResource(R.string.drink_kcal_unknown),
    )
    Column(Modifier.testTag("drink-ai-result"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        macrosSuffix(e.proteinGrams, e.fatGrams, e.carbsGrams).removePrefix(" · ").takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        if (e.additions.isNotEmpty()) Text(stringResource(R.string.drink_ai_additions, e.additions.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
        e.uncertainty?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
