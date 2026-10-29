package com.nutrix.app.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nutrix.app.R
import com.nutrix.app.core.ui.components.IconBadge
import com.nutrix.app.core.ui.icons.*
import com.nutrix.app.core.ui.theme.LocalCategoryColors

enum class QuickAction { FOOD, WATER, WEIGHT, SYMPTOM, NOTE }

/** A discoverable, scrollable action sheet rather than a stack of small floating buttons. */
@Composable
fun QuickAddFab(expanded: Boolean, onExpandedChange: (Boolean) -> Unit, onAction: (QuickAction) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = LocalCategoryColors.current
    FloatingActionButton(
        onClick = { focus.clearFocus(); keyboard?.hide(); haptic.performHapticFeedback(HapticFeedbackType.LongPress); onExpandedChange(!expanded) },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.large,
    ) { Icon(Icons.Rounded.Add, stringResource(R.string.quick_add)) }
    if (expanded) {
        ModalBottomSheet(
            onDismissRequest = { onExpandedChange(false) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.quick_add_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onExpandedChange(false) }) { Icon(Icons.Rounded.Close, stringResource(R.string.action_close)) }
                }
                Spacer(Modifier.height(12.dp))
                QuickItem(QuickAction.FOOD, stringResource(R.string.quick_add_food), stringResource(R.string.quick_food_hint), Icons.Rounded.Restaurant, colors.meal, onAction)
                QuickItem(QuickAction.WATER, stringResource(R.string.quick_add_water), stringResource(R.string.quick_water_hint), Icons.Rounded.WaterDrop, colors.water, onAction)
                QuickItem(QuickAction.WEIGHT, stringResource(R.string.quick_add_weight), stringResource(R.string.quick_weight_hint), Icons.Rounded.MonitorWeight, colors.weight, onAction)
                QuickItem(QuickAction.SYMPTOM, stringResource(R.string.quick_add_symptom), stringResource(R.string.quick_symptom_hint), Icons.Rounded.Healing, colors.symptom, onAction)
                QuickItem(QuickAction.NOTE, stringResource(R.string.quick_add_note), stringResource(R.string.quick_note_hint), Icons.Rounded.StickyNote2, colors.note, onAction)
            }
        }
    }
}

@Composable
private fun QuickItem(action: QuickAction, title: String, hint: String, icon: ImageVector, tint: Color, onAction: (QuickAction) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.testTag("quick-action-" + action.name).clickable(role = Role.Button) { onAction(action) }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(icon, tint, 42.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}
