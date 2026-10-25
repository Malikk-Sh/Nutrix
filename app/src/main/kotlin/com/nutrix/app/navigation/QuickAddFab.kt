package com.nutrix.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nutrix.app.R
import com.nutrix.app.core.ui.theme.LocalCategoryColors

enum class QuickAction { FOOD, WATER, WEIGHT, SYMPTOM, NOTE }

/** "+" button that expands into quick actions (food, water, weight, symptom, note). */
@Composable
fun QuickAddFab(expanded: Boolean, onExpandedChange: (Boolean) -> Unit, onAction: (QuickAction) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val rotation by animateFloatAsState(if (expanded) 45f else 0f, label = "fab")
    val colors = LocalCategoryColors.current
    BackHandler(enabled = expanded) { onExpandedChange(false) }
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + slideInVertically { it / 3 } + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + slideOutVertically { it / 3 } + scaleOut(targetScale = 0.9f),
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickItem(stringResource(R.string.quick_add_note), Icons.Rounded.StickyNote2, colors.note) { onAction(QuickAction.NOTE) }
                QuickItem(stringResource(R.string.quick_add_symptom), Icons.Rounded.Healing, colors.symptom) { onAction(QuickAction.SYMPTOM) }
                QuickItem(stringResource(R.string.quick_add_weight), Icons.Rounded.MonitorWeight, colors.weight) { onAction(QuickAction.WEIGHT) }
                QuickItem(stringResource(R.string.quick_add_water), Icons.Rounded.WaterDrop, colors.water) { onAction(QuickAction.WATER) }
                QuickItem(stringResource(R.string.quick_add_food), Icons.Rounded.PhotoCamera, colors.meal) { onAction(QuickAction.FOOD) }
            }
        }
        FloatingActionButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onExpandedChange(!expanded)
            },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.quick_add), modifier = Modifier.rotate(rotation))
        }
    }
}

@Composable
private fun QuickItem(label: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest, tonalElevation = 2.dp, onClick = onClick) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
        Spacer(Modifier.width(10.dp))
        SmallFloatingActionButton(onClick = onClick, containerColor = tint, contentColor = Color(0xFF0E1012)) {
            Icon(icon, contentDescription = label)
        }
    }
}

