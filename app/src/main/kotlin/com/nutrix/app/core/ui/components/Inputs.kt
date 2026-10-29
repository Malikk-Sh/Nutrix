package com.nutrix.app.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** Single-choice chip row. Tapping the selected chip again clears it when [allowDeselect]. */
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T?) -> Unit,
    modifier: Modifier = Modifier,
    allowDeselect: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val haptic = LocalHapticFeedback.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            FilterChip(
                selected = isSelected,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelect(if (isSelected && allowDeselect) null else option)
                },
                label = { Text(label(option)) },
                leadingIcon = if (isSelected) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = accent,
                    selectedLabelColor = if (accent.luminance() > 0.3f) Color(0xFF101619) else Color.White,
                    selectedLeadingIconColor = if (accent.luminance() > 0.3f) Color(0xFF101619) else Color.White,
                ),
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, if (isSelected) accent.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
fun <T> MultiChoiceChips(
    options: List<T>,
    selected: Set<T>,
    label: @Composable (T) -> String,
    onToggle: (T) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val haptic = LocalHapticFeedback.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option ->
            val isSelected = option in selected
            FilterChip(
                selected = isSelected,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onToggle(option)
                },
                label = { Text(label(option)) },
                leadingIcon = if (isSelected) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = accent,
                    selectedLabelColor = if (accent.luminance() > 0.3f) Color(0xFF101619) else Color.White,
                    selectedLeadingIconColor = if (accent.luminance() > 0.3f) Color(0xFF101619) else Color.White,
                ),
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, if (isSelected) accent.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

/** Equal-width options stay visible; large text wraps into accessible rows. */
@Composable
fun <T> SegmentedChoices(options: List<T>, selected: T?, label: @Composable (T) -> String,
    onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val largeText = LocalDensity.current.fontScale > 1.2f
    @Composable fun choice(option: T, itemModifier: Modifier) {
        val active = option == selected
        Box(itemModifier.clip(MaterialTheme.shapes.small)
            .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
            .selectable(active, role = Role.RadioButton, onClick = { onSelect(option) })
            .heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(label(option), style = MaterialTheme.typography.labelMedium,
                color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
    }
    if (largeText) FlowRow(modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { choice(it, Modifier) }
    } else Row(modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp).selectableGroup()) {
        options.forEach { choice(it, Modifier.weight(1f)) }
    }
}

/** 0..max scale of round buttons with a caption for the current value. */
@Composable
fun ScaleSelector(
    value: Int?,
    onValueChange: (Int?) -> Unit,
    caption: @Composable (Int) -> String,
    modifier: Modifier = Modifier,
    max: Int = 5,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val haptic = LocalHapticFeedback.current
    Column(modifier) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0..max) {
                val selected = value == i
                val bg by animateColorAsState(
                    if (selected) accent else MaterialTheme.colorScheme.surfaceContainerHighest,
                    label = "scale",
                )
                val text = caption(i)
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(bg)
                        .border(1.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable(role = Role.RadioButton) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onValueChange(if (selected) null else i)
                        }
                        .semantics {
                            this.selected = selected
                            contentDescription = "$i — $text"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$i",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        if (value != null) {
            Text(
                caption(value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
fun NutrixTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    suffix: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    enabled: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    showLabel: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        enabled = enabled,
        onValueChange = onValueChange,
        label = if (showLabel) ({ Text(label) }) else null,
        textStyle = textStyle,
        placeholder = placeholder?.let { { Text(it) } },
        modifier = modifier
            .fillMaxWidth()
            .then(if (!showLabel) Modifier.semantics { contentDescription = label } else Modifier)
            .heightIn(min = 56.dp),
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            capitalization = capitalization,
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
        ),
        suffix = suffix?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        isError = isError,
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            errorContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
        ),
    )
}
