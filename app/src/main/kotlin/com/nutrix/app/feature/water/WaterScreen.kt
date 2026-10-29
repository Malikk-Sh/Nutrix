package com.nutrix.app.feature.water

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import com.nutrix.app.core.ui.icons.Tune
import com.nutrix.app.core.ui.icons.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrix.app.R
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.ui.components.ChoiceChips
import com.nutrix.app.core.ui.components.EmptyState
import com.nutrix.app.core.ui.components.IconBadge
import com.nutrix.app.core.ui.components.InfoBanner
import com.nutrix.app.core.ui.components.MiniBars
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixBottomAction
import com.nutrix.app.core.ui.components.NutrixScreen
import com.nutrix.app.core.ui.components.NutrixTextField
import com.nutrix.app.core.ui.components.PhotoThumb
import com.nutrix.app.core.ui.components.ProgressRing
import com.nutrix.app.core.ui.components.ScreenList
import com.nutrix.app.core.ui.components.SectionHeader
import com.nutrix.app.core.ui.icon
import com.nutrix.app.core.ui.labelRes
import com.nutrix.app.core.ui.theme.LocalCategoryColors
import com.nutrix.app.core.ui.theme.MetricTextStyle
import com.nutrix.app.database.entity.DrinkEntity
import com.nutrix.app.database.entity.DrinkTemplateEntity

@Composable
fun WaterScreen(
    openAdd: Boolean,
    onBack: () -> Unit,
    onAddDrink: () -> Unit,
    onOpenDrink: (Long) -> Unit,
    viewModel: WaterViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val quickType by viewModel.quickType.collectAsStateWithLifecycle()
    val colors = LocalCategoryColors.current
    val haptic = LocalHapticFeedback.current
    var goalDialog by rememberSaveable { mutableStateOf(false) }
    var volumesDialog by rememberSaveable { mutableStateOf(false) }
    var templateDialog by remember { mutableStateOf<DrinkTemplateEntity?>(null) }
    var openedAdd by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openAdd) {
        if (openAdd && !openedAdd) {
            openedAdd = true
            onAddDrink()
        }
    }

    NutrixScreen(
        title = stringResource(R.string.water_title),
        onBack = onBack,
        bottomBar = { NutrixBottomAction(stringResource(R.string.drink_add_action), onAddDrink) },
    ) { padding ->
        ScreenList(padding) {
            item(key = "summary") {
                NutrixCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val goal = state.goalMl
                        ProgressRing(
                            fraction = if (goal != null && goal > 0) state.totalMl / goal.toFloat() else 0f,
                            color = colors.water,
                            size = 96.dp,
                            stroke = 10.dp,
                        ) {
                            Icon(Icons.Rounded.WaterDrop, null, tint = colors.water, modifier = Modifier.size(30.dp))
                        }
                        Spacer(Modifier.width(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.water_today), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${Formatters.integer(state.totalMl.toLong())} ${stringResource(R.string.unit_ml)}", style = MetricTextStyle)
                            if (goal != null) {
                                Text(stringResource(R.string.water_of_goal, Formatters.integer(goal.toLong())), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { goalDialog = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                                Text(stringResource(if (goal == null) R.string.water_set_goal else R.string.water_goal_title))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.water_quick), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { volumesDialog = true }) { Icon(Icons.Rounded.Tune, stringResource(R.string.water_quick_edit)) }
                    }
                    ChoiceChips(
                        options = DrinkType.entries,
                        selected = quickType,
                        label = { stringResource(it.labelRes) },
                        onSelect = { t -> if (t != null) viewModel.quickType.value = t },
                        allowDeselect = false,
                        accent = colors.water,
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.quickVolumes.forEach { v ->
                            FilledTonalButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.quickAdd(v)
                            }) { Text("+$v ${stringResource(R.string.unit_ml)}") }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    InfoBanner(stringResource(R.string.water_all_fluids_note), Icons.Rounded.Info, tint = colors.water)
                }
            }

            item(key = "templates") {
                NutrixCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.water_templates), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { templateDialog = DrinkTemplateEntity(name = "", drinkType = DrinkType.TEA, volumeMl = 300) }) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                            Text(stringResource(R.string.water_template_add))
                        }
                    }
                    if (state.templates.isEmpty()) {
                        Text(stringResource(R.string.water_template_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(stringResource(R.string.water_template_longpress), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.templates.forEach { t ->
                                TemplateChip(t, onAdd = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.addTemplate(t)
                                }, onEdit = { templateDialog = t })
                            }
                        }
                    }
                }
            }

            item(key = "history") {
                val ml = stringResource(R.string.unit_ml)
                NutrixCard {
                    Text(stringResource(R.string.water_history), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    MiniBars(
                        values = state.history.map { it.second.toDouble() },
                        labels = state.history.map { Formatters.dayMonthShort(it.first) },
                        color = colors.water,
                        target = state.goalMl?.toDouble(),
                        valueText = { "${Formatters.integer(it.toLong())} $ml" },
                    )
                }
            }

            item(key = "today-h") { SectionHeader(stringResource(R.string.water_today_list)) }
            if (state.todayDrinks.isEmpty()) {
                item { EmptyState(Icons.Rounded.WaterDrop, stringResource(R.string.water_empty), null) }
            }
            items(state.todayDrinks, key = { it.id }) { drink ->
                DrinkRow(drink, onClick = { onOpenDrink(drink.id) }, onDelete = { viewModel.delete(drink) })
            }
        }
    }

    if (goalDialog) GoalDialog(state.goalMl, onDismiss = { goalDialog = false }, onSave = { viewModel.setGoal(it); goalDialog = false })
    if (volumesDialog) QuickVolumesDialog(state.quickVolumes, onDismiss = { volumesDialog = false }, onSave = { viewModel.setQuickVolumes(it); volumesDialog = false })
    templateDialog?.let { t ->
        TemplateDialog(
            template = t,
            onDismiss = { templateDialog = null },
            onSave = { viewModel.saveTemplate(it); templateDialog = null },
            onDelete = if (t.id != 0L) ({ viewModel.deleteTemplate(t); templateDialog = null }) else null,
        )
    }
}

/** Tap adds the drink, long press opens the template editor. */
@Composable
private fun TemplateChip(t: DrinkTemplateEntity, onAdd: () -> Unit, onEdit: () -> Unit) {
    val colors = LocalCategoryColors.current
    Surface(
        shape = MaterialTheme.shapes.small,
        color = colors.water.copy(alpha = 0.12f),
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(onClick = onAdd, onLongClick = onEdit, onLongClickLabel = stringResource(R.string.action_edit)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(t.drinkType.icon, null, tint = colors.water, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("${t.name} · ${t.volumeMl} ${stringResource(R.string.unit_ml)}", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun DrinkRow(drink: DrinkEntity, onClick: () -> Unit, onDelete: (() -> Unit)?) {
    val colors = LocalCategoryColors.current
    NutrixCard(onClick = onClick, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (drink.photoFile != null) PhotoThumb(drink.photoFile, size = 40.dp) else IconBadge(drink.drinkType.icon, colors.water, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    drink.name ?: stringResource(drink.drinkType.labelRes),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    Formatters.time(drink.time) + (drink.note?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("${drink.volumeMl} ${stringResource(R.string.unit_ml)}", style = MaterialTheme.typography.titleMedium)
            if (onDelete != null) {
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete), Modifier.size(20.dp)) }
            }
        }
    }
}

@Composable
private fun GoalDialog(current: Int?, onDismiss: () -> Unit, onSave: (Int?) -> Unit) {
    var text by remember { mutableStateOf(current?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.water_goal_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.water_goal_text), style = MaterialTheme.typography.bodyMedium)
                NutrixTextField(text, { text = it.filter(Char::isDigit).take(5) }, stringResource(R.string.water_goal_label), keyboardType = KeyboardType.Number, suffix = stringResource(R.string.unit_ml))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.toIntOrNull()) }, enabled = text.toIntOrNull()?.let { it in 100..10000 } == true) { Text(stringResource(R.string.action_save)) } },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text(stringResource(R.string.water_goal_remove)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun QuickVolumesDialog(current: List<Int>, onDismiss: () -> Unit, onSave: (List<Int>) -> Unit) {
    val fields = remember { (current + List(6) { 0 }).take(6).map { mutableStateOf(if (it > 0) it.toString() else "") } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.water_quick_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.water_quick_edit_text), style = MaterialTheme.typography.bodySmall)
                fields.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { f ->
                            NutrixTextField(f.value, { f.value = it.filter(Char::isDigit).take(4) }, stringResource(R.string.water_volume), keyboardType = KeyboardType.Number, suffix = stringResource(R.string.unit_ml), modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(fields.mapNotNull { it.value.toIntOrNull() }.filter { it > 0 }) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun TemplateDialog(template: DrinkTemplateEntity, onDismiss: () -> Unit, onSave: (DrinkTemplateEntity) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(template.name) }
    var type by remember { mutableStateOf(template.drinkType) }
    var volume by remember { mutableStateOf(template.volumeMl.toString()) }
    val valid = name.isNotBlank() && volume.toIntOrNull()?.let { it in 1..5000 } == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.water_template_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NutrixTextField(name, { name = it }, stringResource(R.string.water_template_name), placeholder = stringResource(R.string.water_template_name_hint))
                ChoiceChips(DrinkType.entries, type, { stringResource(it.labelRes) }, { t -> if (t != null) type = t }, allowDeselect = false, accent = LocalCategoryColors.current.water)
                NutrixTextField(volume, { volume = it.filter(Char::isDigit).take(4) }, stringResource(R.string.water_volume), keyboardType = KeyboardType.Number, suffix = stringResource(R.string.unit_ml))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(template.copy(name = name.trim(), drinkType = type, volumeMl = volume.toInt())) }, enabled = valid) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
