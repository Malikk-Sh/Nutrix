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
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
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
import com.nutrix.app.core.ui.components.LocalExternalTransition
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixScreen
import com.nutrix.app.core.ui.components.NutrixTextField
import com.nutrix.app.core.ui.components.PhotoThumb
import com.nutrix.app.core.ui.components.ScreenList
import com.nutrix.app.core.ui.labelRes
import com.nutrix.app.core.ui.theme.LocalCategoryColors

@Composable
fun DrinkEditorScreen(
    onClose: () -> Unit,
    onOpenCamera: () -> Unit,
    viewModel: DrinkEditorViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val closed by viewModel.closed.collectAsStateWithLifecycle()
    val camera by viewModel.cameraResult.collectAsStateWithLifecycle()
    val colors = LocalCategoryColors.current
    val external = LocalExternalTransition.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(closed) { if (closed) onClose() }
    LaunchedEffect(camera) { camera?.let(viewModel::consumeCamera) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::setPhotoFromUri) }
    BackHandler { viewModel.cancel() }

    NutrixScreen(
        title = stringResource(if (form.id == 0L) R.string.drink_add_title else R.string.drink_edit_title),
        onBack = viewModel::cancel,
        actions = {
            if (form.id != 0L) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete)) }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Button(
                    onClick = viewModel::save,
                    enabled = form.volume != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .height(54.dp),
                ) { Text(stringResource(R.string.action_save)) }
            }
        },
    ) { padding ->
        ScreenList(padding, bottomExtra = 24.dp) {
            item {
                NutrixCard {
                    Text(stringResource(R.string.water_type), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    ChoiceChips(DrinkType.entries, form.type, { stringResource(it.labelRes) }, { t -> if (t != null) viewModel.update { it.copy(type = t) } }, allowDeselect = false, accent = colors.water)
                    Spacer(Modifier.height(12.dp))
                    NutrixTextField(
                        value = form.volumeText,
                        onValueChange = { v -> viewModel.update { it.copy(volumeText = v.filter(Char::isDigit).take(4)) } },
                        label = stringResource(R.string.water_volume),
                        keyboardType = KeyboardType.Number,
                        suffix = stringResource(R.string.unit_ml),
                        isError = form.volumeText.isNotEmpty() && form.volume == null,
                        supportingText = if (form.volumeText.isNotEmpty() && form.volume == null) stringResource(R.string.drink_volume_invalid) else null,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(100, 150, 200, 250, 300, 330, 500).forEach { v ->
                            SuggestionChip(onClick = { viewModel.update { it.copy(volumeText = v.toString()) } }, label = { Text("$v") })
                        }
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
                            external()
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
