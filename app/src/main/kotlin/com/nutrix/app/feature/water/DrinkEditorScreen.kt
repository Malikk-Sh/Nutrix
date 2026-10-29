package com.nutrix.app.feature.water

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import com.nutrix.app.core.ui.icons.PhotoCamera
import com.nutrix.app.core.ui.icons.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrix.app.R
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.ui.components.ChoiceChips
import com.nutrix.app.core.ui.components.ConfirmDialog
import com.nutrix.app.core.ui.components.DateTimeRow
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixBottomAction
import com.nutrix.app.core.ui.components.NutrixScreen
import com.nutrix.app.core.ui.components.NutrixTextField
import com.nutrix.app.core.ui.components.PhotoThumb
import com.nutrix.app.core.ui.components.ScreenList
import com.nutrix.app.core.ui.icon
import com.nutrix.app.core.ui.theme.MetricTextStyle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalDensity
import com.nutrix.app.core.ui.labelRes
import com.nutrix.app.core.ui.theme.LocalCategoryColors
import com.nutrix.app.navigation.PhotoNavigationResults

@Composable
fun DrinkEditorScreen(
    photoResults: PhotoNavigationResults,
    onClose: () -> Unit,
    onOpenCamera: () -> Unit,
    viewModel: DrinkEditorViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val closed by viewModel.closed.collectAsStateWithLifecycle()
    val camera by photoResults.cameraPhoto.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val colors = LocalCategoryColors.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var allVolumes by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(closed) { if (closed) onClose() }
    LaunchedEffect(camera, loaded) { camera?.let { if (viewModel.consumeCamera(it)) photoResults.cameraConsumed(it) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::setPhotoFromUri) }
    BackHandler { viewModel.cancel() }

    NutrixScreen(
        title = stringResource(if (form.id == 0L) R.string.drink_add_title else R.string.drink_edit_title),
        onBack = viewModel::cancel,
        actions = {
            if (form.id != 0L) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete)) }
        },
        bottomBar = {
            NutrixBottomAction(stringResource(R.string.action_save), viewModel::save, enabled = loaded && form.volume != null, loading = busy)
        },
    ) { padding ->
        ScreenList(padding, bottomExtra = 24.dp) {
            item {
                NutrixCard {
                    Text(stringResource(R.string.water_type), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    val columns = if (LocalDensity.current.fontScale > 1.2f) 1 else 2
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DrinkType.entries.chunked(columns).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { type ->
                                    val selected = type == form.type
                                    FilledTonalButton(onClick = { viewModel.update { it.copy(type = type) } }, modifier = Modifier.weight(1f),
                                        shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (selected) colors.water.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                                            contentColor = if (selected) colors.water else MaterialTheme.colorScheme.onSurfaceVariant)) {
                                        Icon(type.icon, null, Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(type.labelRes), style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    NutrixTextField(
                        value = form.volumeText,
                        onValueChange = { v -> viewModel.update { it.copy(volumeText = v.filter(Char::isDigit).take(4)) } },
                        label = stringResource(R.string.water_volume),
                        keyboardType = KeyboardType.Number,
                        suffix = stringResource(R.string.unit_ml),
                        isError = form.volumeText.isNotEmpty() && form.volume == null,
                        supportingText = if (form.volumeText.isNotEmpty() && form.volume == null) stringResource(R.string.drink_volume_invalid) else null,
                        textStyle = MetricTextStyle,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (if (allVolumes) listOf(100, 150, 200, 250, 300, 330, 500) else listOf(100, 250, 500)).forEach { v ->
                            SuggestionChip(onClick = { viewModel.update { it.copy(volumeText = v.toString()) } }, label = { Text("$v") })
                        }
                        androidx.compose.material3.TextButton(onClick = { allVolumes = !allVolumes }) { Text(stringResource(if (allVolumes) R.string.alignment_collapse else R.string.alignment_more)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    NutrixTextField(form.name, { v -> viewModel.update { it.copy(name = v) } }, stringResource(R.string.drink_name_label))
                }
            }
            item {
                NutrixCard {
                    Text(stringResource(R.string.food_when), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    DateTimeRow(form.date, form.time, { d -> viewModel.update { it.copy(date = d) } }, { t -> viewModel.update { it.copy(time = t) } })
                }
            }
            item {
                NutrixCard {
                    Text(stringResource(R.string.label_photo), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (form.photo != null) {
                            PhotoThumb(form.photo, size = 72.dp)
                            IconButton(onClick = viewModel::removePhoto) { Icon(Icons.Rounded.Close, stringResource(R.string.food_photo_remove)) }
                        }
                        FilledTonalButton(onClick = onOpenCamera) {
                            Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.food_camera))
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) {
                            Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    NutrixTextField(form.note, { v -> viewModel.update { it.copy(note = v) } }, stringResource(R.string.label_note), singleLine = false, minLines = 2)
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_title),
            text = stringResource(R.string.confirm_delete_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { confirmDelete = false; viewModel.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
}
