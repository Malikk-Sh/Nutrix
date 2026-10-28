package com.nutrix.app.feature.symptoms

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import com.nutrix.app.core.ui.icons.Healing
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrix.app.R
import com.nutrix.app.core.model.BodyPosition
import com.nutrix.app.core.model.SymptomType
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.ui.components.ChoiceChips
import com.nutrix.app.core.ui.components.ConfirmDialog
import com.nutrix.app.core.ui.components.DateTimeRow
import com.nutrix.app.core.ui.components.EmptyState
import com.nutrix.app.core.ui.components.IconBadge
import com.nutrix.app.core.ui.components.InfoBanner
import com.nutrix.app.core.ui.components.MultiChoiceChips
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixScreen
import com.nutrix.app.core.ui.components.NutrixTextField
import com.nutrix.app.core.ui.components.Pill
import com.nutrix.app.core.ui.components.ScaleSelector
import com.nutrix.app.core.ui.components.ScreenList
import com.nutrix.app.core.ui.components.SectionHeader
import com.nutrix.app.core.ui.intensityLabel
import com.nutrix.app.core.ui.labelRes
import com.nutrix.app.core.ui.theme.LocalCategoryColors
import com.nutrix.app.database.entity.MealWithPhotos
import com.nutrix.app.database.entity.SymptomEntity
import com.nutrix.app.feature.food.dayTitle

@Composable
fun SymptomsScreen(
    openAdd: Boolean,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    viewModel: SymptomsViewModel = hiltViewModel(),
) {
    val days by viewModel.days.collectAsStateWithLifecycle()
    val meals by viewModel.recentMeals.collectAsStateWithLifecycle()
    val colors = LocalCategoryColors.current
    val haptic = LocalHapticFeedback.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var openedAdd by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openAdd) {
        if (openAdd && !openedAdd) {
            openedAdd = true
            onAdd()
        }
    }
    LaunchedEffect(resources) {
        viewModel.quickAdded.collect { (id, type) ->
            val result = snackbar.showSnackbar(
                resources.getString(R.string.symptoms_marked, resources.getString(type.labelRes)),
                actionLabel = resources.getString(R.string.symptoms_add_details),
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) onOpen(id)
        }
    }

    NutrixScreen(
        title = stringResource(R.string.symptoms_title),
        onBack = onBack,
        snackbarHost = snackbar,
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(stringResource(R.string.action_add)) })
        },
    ) { padding ->
        ScreenList(padding) {
            item(key = "quick") {
                NutrixCard {
                    Text(stringResource(R.string.symptoms_quick), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.symptoms_quick_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SymptomType.entries.filter { it != SymptomType.OTHER }.forEach { type ->
                            FilledTonalButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.quickAdd(type)
                            }) { Text(stringResource(type.labelRes)) }
                        }
                    }
                }
            }
            val list = days
            if (list != null && list.isEmpty()) {
                item { EmptyState(Icons.Rounded.Healing, stringResource(R.string.symptoms_empty), stringResource(R.string.symptoms_empty_text)) }
            }
            list?.forEach { day ->
                item(key = "h-${day.date}") { SectionHeader(dayTitle(day.date)) }
                items(day.items, key = { it.id }) { s ->
                    SymptomRow(s, meals[s.mealId], onClick = { onOpen(s.id) })
                }
            }
            if (!list.isNullOrEmpty()) {
                item(key = "more") {
                    OutlinedButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.food_load_more)) }
                }
            }
        }
    }
}

@Composable
fun SymptomRow(s: SymptomEntity, meal: MealWithPhotos?, onClick: () -> Unit) {
    val colors = LocalCategoryColors.current
    NutrixCard(onClick = onClick, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Healing, colors.symptom, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    s.customName ?: stringResource(s.type.labelRes),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val details = listOfNotNull(
                    Formatters.time(s.time),
                    s.durationMinutes?.let { Formatters.duration(it.toLong(), stringResource(R.string.unit_hour_short), stringResource(R.string.unit_minute_short)) },
                    s.position?.let { stringResource(it.labelRes) },
                    meal?.let { stringResource(R.string.symptom_after_meal, it.meal.title ?: stringResource(it.meal.mealType.labelRes)) },
                ).joinToString(" · ")
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                s.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            s.intensity?.let { Pill("$it/5", colors.symptom) }
        }
    }
}

@Composable
fun SymptomEditorScreen(onClose: () -> Unit, viewModel: SymptomEditorViewModel = hiltViewModel()) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val closed by viewModel.closed.collectAsStateWithLifecycle()
    val candidateMeals by viewModel.candidateMeals.collectAsStateWithLifecycle()
    val colors = LocalCategoryColors.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(closed) { if (closed) onClose() }

    NutrixScreen(
        title = stringResource(if (viewModel.isEdit) R.string.symptom_edit_title else R.string.symptom_add_title),
        onBack = onClose,
        actions = {
            if (viewModel.isEdit) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete)) }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Button(
                    onClick = viewModel::save,
                    enabled = form.types.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .height(54.dp),
                ) { Text(stringResource(if (form.types.isEmpty()) R.string.symptom_need_selection else R.string.action_save)) }
            }
        },
    ) { padding ->
        ScreenList(padding, bottomExtra = 24.dp) {
            item {
                NutrixCard {
                    Text(stringResource(R.string.symptom_select), style = MaterialTheme.typography.titleSmall)
                    if (!viewModel.isEdit) {
                        Text(stringResource(R.string.symptom_select_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    MultiChoiceChips(SymptomType.entries, form.types, { stringResource(it.labelRes) }, viewModel::toggleType, accent = colors.symptom)
                    AnimatedVisibility(SymptomType.OTHER in form.types) {
                        NutrixTextField(form.customName, { v -> viewModel.update { it.copy(customName = v) } }, stringResource(R.string.symptom_other_name), modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            if (form.types.isNotEmpty()) {
                item {
                    NutrixCard {
                        Text(stringResource(R.string.symptom_intensity), style = MaterialTheme.typography.titleSmall)
                        form.types.sortedBy { it.ordinal }.forEach { type ->
                            Spacer(Modifier.height(10.dp))
                            if (form.types.size > 1) Text(stringResource(type.labelRes), style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(6.dp))
                            ScaleSelector(form.intensities[type], { viewModel.setIntensity(type, it) }, { stringResource(intensityLabel(it)) }, accent = colors.symptom)
                        }
                    }
                }
            }
            if (form.needsCaution) {
                item { InfoBanner(stringResource(R.string.symptom_caution), Icons.Rounded.Info, tint = colors.symptom) }
            }
            item {
                NutrixCard {
                    Text(stringResource(R.string.symptom_start), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    DateTimeRow(form.date, form.time, { d -> viewModel.update { it.copy(date = d) } }, { t -> viewModel.update { it.copy(time = t) } })
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.symptom_duration), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    val presets = listOf(5 to R.string.duration_5, 15 to R.string.duration_15, 30 to R.string.duration_30, 60 to R.string.duration_60, 120 to R.string.duration_120)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        presets.forEach { (min, label) ->
                            SuggestionChip(onClick = { viewModel.update { it.copy(durationText = min.toString()) } }, label = { Text(stringResource(label)) })
                        }
                    }
                    NutrixTextField(
                        form.durationText,
                        { v -> viewModel.update { it.copy(durationText = v.filter(Char::isDigit).take(5)) } },
                        stringResource(R.string.symptom_duration_custom),
                        keyboardType = KeyboardType.Number,
                        suffix = stringResource(R.string.unit_minute_short),
                    )
                }
            }
            item {
                NutrixCard {
                    Text(stringResource(R.string.symptom_meal_link), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    if (candidateMeals.isEmpty()) {
                        Text(stringResource(R.string.symptom_meal_none_available), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val noneLabel = stringResource(R.string.symptom_meal_none)
                        val options: List<Long?> = listOf<Long?>(null) + candidateMeals.map { it.meal.id }
                        val byId = candidateMeals.associateBy { it.meal.id }
                        ChoiceChips(
                            options = options,
                            selected = form.mealId,
                            label = { id ->
                                if (id == null) noneLabel else byId[id]?.let { m ->
                                    "${Formatters.time(m.meal.time)} · ${m.meal.title ?: stringResource(m.meal.mealType.labelRes)}"
                                } ?: ""
                            },
                            onSelect = { id -> viewModel.update { it.copy(mealId = id) } },
                            accent = colors.meal,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.symptom_position), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    ChoiceChips(BodyPosition.entries, form.position, { stringResource(it.labelRes) }, { p -> viewModel.update { it.copy(position = p) } }, accent = colors.symptom)
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
