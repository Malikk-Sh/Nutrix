package com.nutrix.app.feature.food

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import com.nutrix.app.core.ui.icons.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import com.nutrix.app.core.ui.icons.Crop
import androidx.compose.material.icons.rounded.Delete
import com.nutrix.app.core.ui.icons.ExpandLess
import com.nutrix.app.core.ui.icons.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import com.nutrix.app.core.ui.icons.PhotoCamera
import com.nutrix.app.core.ui.icons.PhotoLibrary
import com.nutrix.app.core.ui.icons.Repeat
import androidx.compose.material.icons.rounded.Star
import com.nutrix.app.core.ui.icons.StarOutline
import com.nutrix.app.core.ui.icons.Healing
import com.nutrix.app.core.ui.icons.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrix.app.R
import com.nutrix.app.core.model.AfterMealFeeling
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.model.PortionEaten
import com.nutrix.app.core.model.Satiety
import com.nutrix.app.core.model.WeightUnit
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.ui.appetiteLabel
import com.nutrix.app.core.ui.components.ChoiceChips
import com.nutrix.app.core.ui.components.ConfirmDialog
import com.nutrix.app.core.ui.components.DateTimeRow
import com.nutrix.app.core.ui.components.InfoBanner
import com.nutrix.app.core.ui.components.MultiChoiceChips
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixActionSheet
import com.nutrix.app.core.ui.components.NutrixContextAction
import com.nutrix.app.core.ui.components.NutrixBottomAction
import com.nutrix.app.core.ui.components.NutrixScreen
import com.nutrix.app.core.ui.components.NutrixTextField
import com.nutrix.app.core.ui.components.PhotoHero
import com.nutrix.app.core.ui.components.PhotoThumb
import com.nutrix.app.core.ui.components.ScaleSelector
import com.nutrix.app.core.ui.components.ScreenList
import com.nutrix.app.core.ui.labelRes
import com.nutrix.app.core.ui.theme.LocalCategoryColors
import com.nutrix.app.core.util.Units
import com.nutrix.app.feature.ai.FoodAiCard
import com.nutrix.app.feature.ai.NutritionTable
import com.nutrix.app.feature.ai.FoodReference
import com.nutrix.app.navigation.PhotoNavigationResults
import kotlinx.coroutines.launch

@Composable
fun FoodEditorScreen(
    photoResults: PhotoNavigationResults,
    openCamera: Boolean,
    onClose: () -> Unit,
    onOpenCamera: () -> Unit,
    onCrop: (String) -> Unit,
    onAddSymptom: (mealId: Long) -> Unit,
    onRepeat: (mealId: Long) -> Unit,
    onOpenGeminiSettings: () -> Unit = {},
    viewModel: FoodEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()
    val cameraResult by photoResults.cameraPhoto.collectAsStateWithLifecycle()
    val cropResult by photoResults.croppedPhoto.collectAsStateWithLifecycle()
    val form = state.form
    val colors = LocalCategoryColors.current
    val haptic = LocalHapticFeedback.current

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showActions by rememberSaveable { mutableStateOf(false) }
    var showLeaveDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var cameraLaunched by rememberSaveable { mutableStateOf(false) }
    var kcalNotFound by remember { mutableStateOf(false) }

    LaunchedEffect(event) {
        when (event) {
            FoodEditorEvent.Saved, FoodEditorEvent.Closed, FoodEditorEvent.Deleted -> {
                viewModel.consumeEvent()
                onClose()
            }
            null -> Unit
        }
    }
    LaunchedEffect(cameraResult, state.loaded, state.pendingDraft, state.isSaving) {
        cameraResult?.let { if (viewModel.consumeCameraResult(it)) photoResults.cameraConsumed(it) }
    }
    LaunchedEffect(cropResult, state.loaded, state.pendingDraft, state.isSaving) {
        cropResult?.let { if (viewModel.consumeCropResult(it)) photoResults.cropConsumed(it) }
    }
    LaunchedEffect(state.loaded, state.pendingDraft) {
        if (state.loaded && openCamera && !cameraLaunched && state.pendingDraft == null) {
            cameraLaunched = true
            onOpenCamera()
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)) { uris ->
        viewModel.addPhotoFromUris(uris)
    }

    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { viewModel.flushDraft() }
    val handleBack: () -> Unit = {
        if (!state.isSaving && !state.processingPhoto) {
            viewModel.cancelAiAnalysis()
            if (state.dirty) showLeaveDialog = true else onClose()
        }
    }
    BackHandler(enabled = state.dirty || state.isSaving || state.processingPhoto || state.analyzingPhoto) { handleBack() }

    NutrixScreen(
        title = stringResource(if (state.isEdit) R.string.food_edit_title else R.string.food_add_title),
        onBack = handleBack,
        actions = {
            if (state.isEdit) IconButton(enabled = !state.isSaving && !state.processingPhoto && !state.analyzingPhoto,
                onClick = { showActions = true }) {
                Icon(Icons.Rounded.MoreVert, stringResource(R.string.food_entry_actions))
            }
        },
        bottomBar = {
            NutrixBottomAction(
                text = stringResource(if (state.isSaving) R.string.action_saving else R.string.action_save),
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.save() },
                enabled = state.loaded && !state.processingPhoto && !state.analyzingPhoto && state.pendingDraft == null && !viewModel.isWeightInvalid() && !viewModel.isNutritionInvalid(),
                loading = state.isSaving,
            )
        },
    ) { padding ->
        ScreenList(padding, state = listState, bottomExtra = 24.dp) {
            if (state.error == "save" || state.error == "draft") {
                item(key = "save-error") {
                    NutrixCard(color = MaterialTheme.colorScheme.errorContainer) {
                        Text(stringResource(if (state.error == "save") R.string.food_save_error else R.string.food_draft_error), color = MaterialTheme.colorScheme.onErrorContainer)
                        if (state.error == "save") TextButton(onClick = viewModel::save, enabled = !state.isSaving && !state.processingPhoto && !state.analyzingPhoto && state.pendingDraft == null && !viewModel.isWeightInvalid() && !viewModel.isNutritionInvalid()) {
                            Text(stringResource(R.string.alignment_retry_save))
                        }
                    }
                }
            }
            state.pendingDraft?.let {
                item(key = "draft") {
                    NutrixCard(color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(stringResource(R.string.food_draft_found_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.food_draft_found_text), style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = viewModel::dismissDraft) { Text(stringResource(R.string.food_draft_discard)) }
                            TextButton(onClick = viewModel::restoreDraft) { Text(stringResource(R.string.food_draft_continue)) }
                        }
                    }
                }
            }

            item(key = "photos") {
                NutrixCard {
                    if (form.photos.isEmpty()) {
                        Text(stringResource(R.string.food_photo_section), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(10.dp))
                    }
                    if (form.photos.size == 1) {
                        val photo = form.photos.first()
                        PhotoWithMenu(photo, isCover = true,
                            onCrop = { if (!state.isSaving && !state.processingPhoto && !state.analyzingPhoto && state.pendingDraft == null) { viewModel.prepareCrop(photo); onCrop(photo) } },
                            onRemove = { viewModel.removePhoto(photo) }, onCover = { viewModel.movePhotoFirst(photo) }, hero = true)
                    } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(form.photos, key = { it }) { photo ->
                            PhotoWithMenu(
                                fileName = photo,
                                isCover = photo == form.photos.firstOrNull(),
                                onCrop = { if (!state.isSaving && !state.processingPhoto && !state.analyzingPhoto && state.pendingDraft == null) { viewModel.prepareCrop(photo); onCrop(photo) } },
                                onRemove = { viewModel.removePhoto(photo) },
                                onCover = { viewModel.movePhotoFirst(photo) },
                            )
                        }
                        if (state.processingPhoto) {
                            item { Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                        }
                    }
                    }
                    if (form.photos.isNotEmpty() || state.processingPhoto) Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(onClick = onOpenCamera, enabled = state.loaded && !state.isSaving && !state.processingPhoto && !state.analyzingPhoto && state.pendingDraft == null && form.photos.size < MAX_PHOTOS) {
                            Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.food_camera))
                        }
                        FilledTonalButton(
                            onClick = {
                                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            enabled = state.loaded && !state.isSaving && !state.processingPhoto && !state.analyzingPhoto && state.pendingDraft == null && form.photos.size < MAX_PHOTOS,
                        ) {
                            Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.food_gallery))
                        }
                    }
                    if (state.error == "photo") {
                        Text(stringResource(R.string.food_photo_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (form.photos.isNotEmpty()) {
                item(key = "food-ai") { FoodAiCard(state, viewModel, onOpenGeminiSettings, onReview = {
                    val beforeNutrition = 3 + (if (state.error == "save" || state.error == "draft") 1 else 0) + (if (state.pendingDraft != null) 1 else 0)
                    scope.launch { listState.animateScrollToItem(beforeNutrition) }
                }) }
            }

            item(key = "dish") {
                DishCard(state, viewModel)
            }

            item(key = "nutrition") { NutritionCard(state, viewModel, onEstimateFailed = { kcalNotFound = true }) }

            item(key = "when") {
                NutrixCard {
                    Text(stringResource(R.string.food_when), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    DateTimeRow(form.date, form.time, viewModel::setDate, viewModel::setTime)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.food_type), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    ChoiceChips(
                        options = MealType.entries,
                        selected = form.mealType,
                        label = { stringResource(it.labelRes) },
                        onSelect = { t -> if (t != null) viewModel.update { it.copy(mealType = t) } },
                        allowDeselect = false,
                        accent = colors.meal,
                    )
                }
            }

            item(key = "details") {
                NutrixCard {
                    Text(stringResource(R.string.food_details), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.food_details_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.food_appetite), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    ScaleSelector(
                        value = form.appetite,
                        onValueChange = { v -> viewModel.update { it.copy(appetite = v) } },
                        caption = { stringResource(appetiteLabel(it)) },
                        accent = colors.meal,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.food_portion), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    ChoiceChips(
                        options = PortionEaten.entries,
                        selected = form.portion,
                        label = { stringResource(it.labelRes) },
                        onSelect = { p -> viewModel.update { it.copy(portion = p) } },
                        accent = colors.meal,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.food_satiety), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    ChoiceChips(
                        options = Satiety.entries,
                        selected = form.satiety,
                        label = { stringResource(it.labelRes) },
                        onSelect = { v -> viewModel.update { it.copy(satiety = v) } },
                        accent = colors.meal,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.food_feelings), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    MultiChoiceChips(
                        options = AfterMealFeeling.entries,
                        selected = form.feelings,
                        label = { stringResource(it.labelRes) },
                        onToggle = viewModel::toggleFeeling,
                        accent = colors.symptom,
                    )
                    AnimatedVisibility(AfterMealFeeling.OTHER in form.feelings) {
                        NutrixTextField(
                            value = form.feelingOther,
                            onValueChange = { v -> viewModel.update { it.copy(feelingOther = v) } },
                            label = stringResource(R.string.food_feeling_other_hint),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            item(key = "more") {
                MoreCard(state, viewModel)
            }
        }
    }

    if (showActions) NutrixActionSheet(
        title = form.title.ifBlank { stringResource(R.string.food_entry_actions) },
        actions = listOf(
            NutrixContextAction(stringResource(R.string.food_repeat), Icons.Rounded.Repeat) { onRepeat(form.mealId) },
            NutrixContextAction(stringResource(R.string.food_add_symptom), Icons.Rounded.Healing) { onAddSymptom(form.mealId) },
            NutrixContextAction(stringResource(R.string.action_delete), Icons.Rounded.Delete, destructive = true) { showDeleteDialog = true },
        ), onDismiss = { showActions = false },
    )

    if (kcalNotFound) {
        AlertDialog(
            onDismissRequest = { kcalNotFound = false },
            confirmButton = { TextButton(onClick = { kcalNotFound = false }) { Text(stringResource(R.string.action_ok)) } },
            text = { Text(stringResource(R.string.food_kcal_not_found)) },
        )
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text(stringResource(R.string.food_leave_title)) },
            text = { Text(stringResource(R.string.food_leave_text)) },
            confirmButton = {
                TextButton(onClick = { showLeaveDialog = false; viewModel.saveDraftAndClose() }) {
                    Text(stringResource(R.string.food_leave_save_draft))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showLeaveDialog = false; viewModel.discardAndClose() }) {
                        Text(stringResource(R.string.food_leave_discard))
                    }
                    TextButton(onClick = { showLeaveDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }

    if (showDeleteDialog) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_title),
            text = stringResource(R.string.confirm_delete_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { showDeleteDialog = false; viewModel.delete() },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Composable
private fun PhotoWithMenu(fileName: String, isCover: Boolean, onCrop: () -> Unit, onRemove: () -> Unit, onCover: () -> Unit, hero: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    if (preview) PhotoPreviewDialog(fileName) { preview = false }
    Box {
        if (hero) PhotoHero(fileName, onClick = { menu = true })
        else PhotoThumb(fileName, size = 112.dp, shape = MaterialTheme.shapes.medium, contentDescription = stringResource(R.string.food_photo_actions), onClick = { menu = true })
        if (isCover) {
            Icon(
                Icons.Rounded.Star,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(18.dp),
            )
        }
        if (menu) NutrixActionSheet(
            title = stringResource(R.string.food_photo_actions),
            actions = buildList {
                add(NutrixContextAction(stringResource(R.string.food_photo_view), Icons.Rounded.ZoomIn) { preview = true })
                add(NutrixContextAction(stringResource(R.string.food_photo_crop), Icons.Rounded.Crop, onClick = onCrop))
                if (!isCover) add(NutrixContextAction(stringResource(R.string.food_photo_cover), Icons.Rounded.Star, onClick = onCover))
                add(NutrixContextAction(stringResource(R.string.food_photo_remove), Icons.Rounded.Close, destructive = true, onClick = onRemove))
            }, onDismiss = { menu = false },
        )
    }
}

/** Full-screen photo preview with pinch-to-zoom. */
@Composable
fun PhotoPreviewDialog(fileName: String, onDismiss: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = com.nutrix.app.core.ui.components.photoFile(fileName),
                contentDescription = stringResource(R.string.food_photo_view),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Rounded.Close, stringResource(R.string.action_close), tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}

@Composable
private fun DishCard(state: FoodEditorUiState, viewModel: FoodEditorViewModel) {
    val form = state.form
    var showFavorites by rememberSaveable { mutableStateOf(false) }
    var itemText by rememberSaveable { mutableStateOf("") }
    var titleFocused by remember { mutableStateOf(false) }
    NutrixCard {
        if (state.timeSuggestions.isNotEmpty() && form.title.isBlank()) {
            Text(stringResource(R.string.food_suggestions_time), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.timeSuggestions.forEach { dish ->
                    SuggestionChip(
                        onClick = { viewModel.update { it.copy(title = dish) } },
                        label = { Text(dish) },
                        icon = { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(16.dp)) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = form.title,
            onValueChange = { v -> viewModel.update { it.copy(title = v) } },
            label = { Text(stringResource(R.string.food_title_label)) },
            placeholder = { Text(stringResource(R.string.food_title_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { titleFocused = it.isFocused },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            shape = MaterialTheme.shapes.medium,
        )
        val query = form.title.trim()
        val suggestions = remember(query, state.titleSuggestions) {
            if (query.length < 2) emptyList() else {
                val own = state.titleSuggestions.filter { it.contains(query, ignoreCase = true) && !it.equals(query, ignoreCase = true) }
                val ref = FoodReference.search(query).map { it.name }.filter { r -> own.none { it.equals(r, ignoreCase = true) } && !r.equals(query, ignoreCase = true) }
                (own + ref).take(6)
            }
        }
        if (titleFocused && suggestions.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { s ->
                    SuggestionChip(onClick = { viewModel.update { it.copy(title = s) } }, label = { Text(s) })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.food_items_label), style = MaterialTheme.typography.labelLarge)
        if (form.items.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                form.items.forEach { item ->
                    InputChip(
                        selected = false,
                        onClick = { viewModel.removeItem(item) },
                        label = { Text(item) },
                        trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_delete), modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = itemText,
                onValueChange = { itemText = it },
                placeholder = { Text(stringResource(R.string.food_item_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.addItem(itemText); itemText = "" }),
                shape = MaterialTheme.shapes.medium,
            )
            IconButton(onClick = { viewModel.addItem(itemText); itemText = "" }, enabled = itemText.isNotBlank()) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.food_item_add))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = form.saveAsFavorite,
                onCheckedChange = { c -> viewModel.update { it.copy(saveAsFavorite = c) } },
                enabled = form.title.isNotBlank() || form.items.isNotEmpty(),
            )
            Icon(
                if (form.saveAsFavorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(if (form.title.isBlank() && form.items.isEmpty()) R.string.food_favorite_needs_title else R.string.food_save_favorite),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.favorites.isNotEmpty()) {
            TextButton(onClick = { showFavorites = !showFavorites }) { Text(stringResource(R.string.alignment_choose_favorite)) }
            if (showFavorites) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.favorites.take(12), key = { it.id }) { fav ->
                    SuggestionChip(
                        onClick = { viewModel.applyFavorite(fav) },
                        label = { Text(fav.title) },
                        icon = { Icon(Icons.Rounded.Star, null, Modifier.size(16.dp)) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

    }
}

@Composable
private fun MoreCard(state: FoodEditorUiState, viewModel: FoodEditorViewModel) {
    val form = state.form
    var expanded by rememberSaveable { mutableStateOf(form.weightText.isNotBlank() || form.note.isNotBlank()) }
    val unitLabel = stringResource(if (state.weightUnit == WeightUnit.KG) R.string.unit_kg else R.string.unit_lb)
    NutrixCard(onClick = if (!expanded) ({ expanded = true }) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.food_more_details),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { expanded = !expanded }) {
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Weight snapshot
                Text(stringResource(R.string.food_weight_section), style = MaterialTheme.typography.labelLarge)
                state.lastWeight?.let { last ->
                    val lastText = Units.formatWeight(last.weightKg, state.weightUnit, unitLabel)
                    Text(
                        stringResource(R.string.food_weight_last, lastText, Formatters.dayMonthShort(last.time.localDate)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (form.weightText.isBlank()) {
                        SuggestionChip(onClick = viewModel::useLastWeight, label = { Text(stringResource(R.string.food_weight_use_last, lastText)) })
                    }
                }
                NutrixTextField(
                    value = form.weightText,
                    onValueChange = { v -> viewModel.update { it.copy(weightText = v) } },
                    label = "${stringResource(R.string.food_weight_label)} (${stringResource(R.string.label_optional)})",
                    keyboardType = KeyboardType.Decimal,
                    suffix = unitLabel,
                    isError = viewModel.isWeightInvalid(),
                    supportingText = if (viewModel.isWeightInvalid()) stringResource(R.string.food_weight_invalid) else null,
                )
                if (form.weightText.isNotBlank() && !viewModel.isWeightInvalid() && viewModel.isWeightDifferentFromLast()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = form.alsoSaveWeight, onCheckedChange = { c -> viewModel.update { it.copy(alsoSaveWeight = c) } })
                        Text(stringResource(R.string.food_weight_also_save), style = MaterialTheme.typography.bodyMedium)
                    }
                }

                NutrixTextField(
                    value = form.note,
                    onValueChange = { v -> viewModel.update { it.copy(note = v) } },
                    label = stringResource(R.string.label_note),
                    placeholder = stringResource(R.string.label_note_hint),
                    singleLine = false,
                    minLines = 3,
                )
            }
        }
    }
}

private const val MAX_PHOTOS = 6
