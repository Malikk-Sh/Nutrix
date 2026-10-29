package com.nutrix.app.core.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nutrix.app.core.ui.theme.NutrixTokens
import com.nutrix.app.R

/** Consistent, keyboard-safe primary action for editors. */
@Composable
fun NutrixBottomAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = NutrixTokens.ScreenPadding, vertical = 12.dp)) {
            Button(
                onClick = onClick,
                enabled = enabled && !loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(text, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

data class NutrixContextAction(val label: String, val icon: ImageVector, val destructive: Boolean = false, val onClick: () -> Unit)

@Composable
fun NutrixActionSheet(title: String, actions: List<NutrixContextAction>, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.action_close)) }
            }
            Spacer(Modifier.height(12.dp))
            actions.forEach { action ->
                val tint = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                Surface(shape = MaterialTheme.shapes.medium, color = if (action.destructive) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Row(Modifier.clickable(role = Role.Button) { onDismiss(); action.onClick() }.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(action.icon, null, tint = tint, modifier = Modifier.size(24.dp))
                        Text(action.label, style = MaterialTheme.typography.bodyLarge, color = tint)
                    }
                }
            }
        }
    }
}
