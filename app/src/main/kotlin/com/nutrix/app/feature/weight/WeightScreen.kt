package com.nutrix.app.feature.weight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Info
import com.nutrix.app.core.ui.icons.MonitorWeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.core.model.WeightUnit
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.ui.charts.DailyLineChart
import com.nutrix.app.core.ui.components.ConfirmDialog
import com.nutrix.app.core.ui.components.DateTimeRow
import com.nutrix.app.core.ui.components.EmptyState
import com.nutrix.app.core.ui.components.InfoBanner
import com.nutrix.app.core.ui.components.LabeledValue
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixScreen
import com.nutrix.app.core.ui.components.NutrixTextField
import com.nutrix.app.core.ui.components.Pill
import com.nutrix.app.core.ui.components.ScreenList
import com.nutrix.app.core.ui.components.SectionHeader
import com.nutrix.app.core.ui.labelRes
import com.nutrix.app.core.ui.sourceAppName
import com.nutrix.app.core.ui.theme.LocalCategoryColors
import com.nutrix.app.core.ui.theme.MetricTextStyle
import com.nutrix.app.core.util.Units
import com.nutrix.app.database.entity.WeightEntity

@Composable
fun WeightScreen(openAdd: Boolean, onBack: () -> Unit, viewModel: WeightViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val duplicate by viewModel.duplicate.collectAsStateWithLifecycle()
    val colors = LocalCategoryColors.current
    val unitLabel = stringResource(if (state.unit == WeightUnit.KG) R.string.unit_kg else R.string.unit_lb)
    var goalDialog by rememberSaveable { mutableStateOf(false) }
    var openedAdd by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openAdd, state.loaded) {
        if (openAdd && state.loaded && !openedAdd) {
            openedAdd = true
            viewModel.startAdd()
        }
    }
    fun fmt(kg: Double) = Units.formatWeight(kg, state.unit, unitLabel)
    fun fmtDelta(kg: Double) = "${Formatters.signedDecimal(Units.toDisplay(kg, state.unit))} $unitLabel"

    NutrixScreen(
        title = stringResource(R.string.weight_title),
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = viewModel::startAdd, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(stringResource(R.string.weight_add)) })
        },
    ) { padding ->
        ScreenList(padding) {
            val stats = state.stats
            if (state.loaded && stats.latest == null) {
                item { EmptyState(Icons.Rounded.MonitorWeight, stringResource(R.string.weight_empty), stringResource(R.string.weight_empty_text)) }
            }
            stats.latest?.let { latest ->
                item(key = "summary") {
                    NutrixCard {
                        Text(stringResource(R.string.weight_latest), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(fmt(latest.weightKg), style = MetricTextStyle)
                        Text(
                            Formatters.dateTime(latest.epochMillis, latest.zoneId),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(14.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            LabeledValue(stringResource(R.string.weight_week_change), stats.weekChangeKg?.let(::fmtDelta) ?: "—")
                            LabeledValue(stringResource(R.string.weight_month_change), stats.monthChangeKg?.let(::fmtDelta) ?: "—")
                            LabeledValue(stringResource(R.string.weight_avg7), stats.avg7Kg?.let(::fmt) ?: "—")
                            LabeledValue(stringResource(R.string.weight_min), stats.minKg?.let(::fmt) ?: "—")
                            LabeledValue(stringResource(R.string.weight_max), stats.maxKg?.let(::fmt) ?: "—")
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                val target = state.targetKg
                                if (target != null) {
                                    LabeledValue(stringResource(R.string.weight_goal), fmt(target))
                                    stats.toGoalKg?.let { diff ->
                                        Text(
                                            if (kotlin.math.abs(diff) < 0.05) stringResource(R.string.weight_goal_reached)
                                            else "${stringResource(R.string.weight_to_goal)}: ${Formatters.decimal(kotlin.math.abs(Units.toDisplay(diff, state.unit)))} $unitLabel",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.weight,
                                        )
                                    }
                                }
                            }
                            TextButton(onClick = { goalDialog = true }) {
                                Text(stringResource(if (state.targetKg == null) R.string.weight_goal_set else R.string.action_change))
                            }
                        }
                    }
                }
                item(key = "chart") {
                    NutrixCard {
                        Text(stringResource(R.string.weight_chart), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WeightPeriod.entries.forEach { p ->
                                FilterChip(
                                    selected = state.period == p,
                                    onClick = { viewModel.setPeriod(p) },
                                    label = {
                                        Text(
                                            stringResource(
                                                when (p) {
                                                    WeightPeriod.D30 -> R.string.weight_period_30
                                                    WeightPeriod.D90 -> R.string.weight_period_90
                                                    WeightPeriod.Y1 -> R.string.weight_period_365
                                                    WeightPeriod.ALL -> R.string.weight_period_all
                                                },
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        DailyLineChart(
                            points = state.chart,
                            color = colors.weight,
                            valueText = { "${Formatters.decimal(it)} $unitLabel" },
                        )
                    }
                }
                item(key = "note") { InfoBanner(stringResource(R.string.weight_fluctuation_note), Icons.Rounded.Info, tint = colors.weight) }
                item(key = "history-h") { SectionHeader(stringResource(R.string.weight_history)) }
                items(state.all.take(300), key = { it.id }) { w ->
                    WeightRow(w, fmt(w.weightKg), onClick = { viewModel.startEdit(w) })
                }
            }
        }
    }

    form?.let { f ->
        WeightSheet(
            form = f,
            unitLabel = unitLabel,
            valid = viewModel.parsedKg() != null,
            onChange = viewModel::updateForm,
            onSave = { viewModel.save() },
            onDelete = if (f.id != 0L) ({ state.all.firstOrNull { it.id == f.id }?.let(viewModel::delete) }) else null,
            onDismiss = viewModel::closeForm,
        )
    }

    duplicate?.let { dup ->
        ConfirmDialog(
            title = stringResource(R.string.weight_duplicate_title),
            text = stringResource(R.string.weight_duplicate_text, Formatters.time(dup.time), fmt(dup.weightKg)),
            confirmLabel = stringResource(R.string.weight_duplicate_save),
            onConfirm = { viewModel.save(force = true) },
            onDismiss = viewModel::closeForm,
        )
    }

    if (goalDialog) {
        var text by remember { mutableStateOf(state.targetKg?.let { Formatters.decimal(Units.toDisplay(it, state.unit)) }.orEmpty()) }
        val parsed = Units.parseDecimal(text)?.let { Units.toKg(it, state.unit) }?.takeIf { Units.isPlausibleWeightKg(it) }
        AlertDialog(
            onDismissRequest = { goalDialog = false },
            title = { Text(stringResource(R.string.weight_goal_dialog)) },
            text = { NutrixTextField(text, { text = it }, stringResource(R.string.weight_goal), keyboardType = KeyboardType.Decimal, suffix = unitLabel) },
            confirmButton = { TextButton(onClick = { viewModel.setTarget(parsed); goalDialog = false }, enabled = parsed != null) { Text(stringResource(R.string.action_save)) } },
            dismissButton = {
                Row {
                    if (state.targetKg != null) TextButton(onClick = { viewModel.setTarget(null); goalDialog = false }) { Text(stringResource(R.string.weight_goal_remove)) }
                    TextButton(onClick = { goalDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }
}

@Composable
private fun WeightRow(w: WeightEntity, value: String, onClick: () -> Unit) {
    val colors = LocalCategoryColors.current
    NutrixCard(onClick = if (w.source == DataSource.MANUAL) onClick else null, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(value, style = MaterialTheme.typography.titleMedium)
                Text(
                    Formatters.dateTime(w.epochMillis, w.zoneId) + (w.note?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            val sourceText = if (w.source == DataSource.MANUAL) stringResource(R.string.source_manual)
            else sourceAppName(w.sourceApp)?.let { app -> if (w.source == DataSource.HEALTH_CONNECT && app != "Health Connect") stringResource(R.string.source_via_hc, app) else app }
                ?: stringResource(w.source.labelRes)
            Pill(sourceText, if (w.source == DataSource.MANUAL) colors.weight else colors.activity)
        }
    }
}

@Composable
private fun WeightSheet(
    form: WeightForm,
    unitLabel: String,
    valid: Boolean,
    onChange: ((WeightForm) -> WeightForm) -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(if (form.id == 0L) R.string.weight_add else R.string.weight_edit), style = MaterialTheme.typography.titleLarge)
            NutrixTextField(
                value = form.valueText,
                onValueChange = { v -> onChange { it.copy(valueText = v) } },
                label = stringResource(R.string.weight_value),
                keyboardType = KeyboardType.Decimal,
                suffix = unitLabel,
                isError = form.valueText.isNotBlank() && !valid,
                supportingText = if (form.valueText.isNotBlank() && !valid) stringResource(R.string.weight_invalid) else null,
            )
            DateTimeRow(form.date, form.time, { d -> onChange { it.copy(date = d) } }, { t -> onChange { it.copy(time = t) } })
            NutrixTextField(form.note, { v -> onChange { it.copy(note = v) } }, stringResource(R.string.label_note))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onDelete != null) {
                    OutlinedButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.action_delete)) }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onSave, enabled = valid) { Text(stringResource(R.string.action_save)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_title),
            text = stringResource(R.string.confirm_delete_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}
