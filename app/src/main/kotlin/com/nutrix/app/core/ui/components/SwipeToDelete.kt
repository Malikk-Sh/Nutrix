package com.nutrix.app.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Swipe a list row to the left to delete it. The row keeps its visible delete button, so the
 * gesture is a shortcut, not the only way; deletion still goes through the app's undo snackbar.
 */
@Composable
fun SwipeToDelete(onDelete: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val state = rememberSwipeToDismissBoxState()
    // If the record could not be deleted (it stays in the list), bring the row back.
    LaunchedEffect(state.currentValue) {
        if (state.currentValue != SwipeToDismissBoxValue.Settled) {
            delay(1500)
            state.reset()
        }
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onDelete()
            }
        },
        backgroundContent = {
            val error = MaterialTheme.colorScheme.error
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.large)
                    .graphicsLayer { alpha = if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) 1f else 0f }
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = error,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer {
                            val p = state.progress.coerceIn(0f, 1f)
                            val s = 0.7f + 0.5f * p
                            scaleX = s
                            scaleY = s
                        },
                )
            }
        },
    ) { content() }
}
