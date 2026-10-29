package com.nutrix.app.feature.activity

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import com.nutrix.app.domain.SleepTotals
import com.nutrix.app.core.ui.icons.Bedtime
import androidx.compose.material.icons.rounded.Delete
import com.nutrix.app.core.ui.icons.DirectionsRun
import androidx.compose.material.icons.rounded.Favorite
import com.nutrix.app.core.ui.icons.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.nutrix.app.R
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.time.RecordTime
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.core.ui.components.ConfirmDialog
import com.nutrix.app.core.ui.components.DateTimeRow
import com.nutrix.app.core.ui.components.EmptyState
import com.nutrix.app.core.ui.components.IconBadge
import com.nutrix.app.core.ui.components.InfoDisclosure
import com.nutrix.app.core.ui.components.InfoBanner
import com.nutrix.app.core.ui.components.LabeledValue
import com.nutrix.app.core.ui.components.MiniBars
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixBottomAction
import com.nutrix.app.core.ui.components.NutrixScreen
import com.nutrix.app.core.ui.components.NutrixTextField
import com.nutrix.app.core.ui.components.Pill
import com.nutrix.app.core.ui.components.ScreenList
import com.nutrix.app.core.ui.components.SectionHeader
import com.nutrix.app.core.ui.labelRes
import com.nutrix.app.core.ui.sourceAppName
import com.nutrix.app.core.ui.theme.LocalCategoryColors
import com.nutrix.app.data.repository.HealthRepository
import com.nutrix.app.data.repository.UndoManager
import com.nutrix.app.data.repository.UserMessages
import com.nutrix.app.database.entity.ExerciseEntity
import com.nutrix.app.database.entity.SleepEntity
import com.nutrix.app.domain.DailyActivity
import com.nutrix.app.integration.sync.HealthSyncCoordinator
import com.nutrix.app.navigation.SleepEditorRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

data class SleepActivityState(
    val sleeps: List<SleepEntity> = emptyList(),
    val activity: List<DailyActivity> = emptyList(),
    val exercises: List<ExerciseEntity> = emptyList(),
    val anySourceEnabled: Boolean = false,
    val sleepMinutes: Map<Long, Int> = emptyMap(),
)

@HiltViewModel
class SleepActivityViewModel @Inject constructor(
    health: HealthRepository,
    settings: SettingsRepository,
    private val sync: HealthSyncCoordinator,
) : ViewModel() {
    private val today = TimeUtils.todayEpochDay()
    val state: StateFlow<SleepActivityState> = combine(
        health.observeRecentSleep(30),
        health.observeActivityRange(today - 13, today),
        health.observeExercises(today - 13, today),
        settings.settings,
        health.observeSleepRange(today - 13, today),
    ) { sleeps, activity, exercises, s, chartSleep ->
        SleepActivityState(sleeps, activity, exercises.sortedByDescending { it.startMillis }, s.healthConnectEnabled || s.samsungHealthEnabled,
            chartSleep.groupBy { it.localDate }.mapValues { SleepTotals.minutes(it.value) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SleepActivityState())

    val syncing = sync.running
    val lastSync = sync.lastSuccessfulSync.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun syncNow() {
        viewModelScope.launch { sync.syncAll(manual = true) }
    }
}

@Composable
fun SleepActivityScreen(
    onBack: () -> Unit,
    onAddSleep: () -> Unit,
    onOpenSleep: (Long) -> Unit,
    onOpenIntegrations: () -> Unit,
    viewModel: SleepActivityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val lastSync by viewModel.lastSync.collectAsStateWithLifecycle()
    val colors = LocalCategoryColors.current
    val h = stringResource(R.string.unit_hour_short)
    val m = stringResource(R.string.unit_minute_short)
    NutrixScreen(
        title = stringResource(R.string.activity_title),
        onBack = onBack,
        bottomBar = { NutrixBottomAction(stringResource(R.string.sleep_add), onAddSleep) },
        actions = {
            if (state.anySourceEnabled) {
                if (syncing) CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp))
                else IconButton(onClick = viewModel::syncNow) { Icon(Icons.Rounded.Sync, stringResource(R.string.action_sync)) }
            }
        },
    ) { padding ->
        ScreenList(padding) {
            if (!state.anySourceEnabled) {
                item {
                    NutrixCard {
                        InfoDisclosure(stringResource(R.string.activity_connect), stringResource(R.string.activity_no_source), Icons.Rounded.Info)
                        TextButton(onClick = onOpenIntegrations) { Text(stringResource(R.string.activity_connect)) }
                    }
                }
            } else {
                item {
                    Text(
                        lastSync?.let { stringResource(R.string.activity_last_sync, Formatters.dateTime(it, ZoneId.systemDefault().id)) } ?: stringResource(R.string.activity_never_synced),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item(key = "sleep-h") { SectionHeader(stringResource(R.string.sleep_title), actionLabel = stringResource(R.string.sleep_add), onAction = onAddSleep) }
            if (state.sleeps.isEmpty()) {
                item { EmptyState(Icons.Rounded.Bedtime, stringResource(R.string.sleep_empty), stringResource(R.string.sleep_empty_text)) }
            } else {
                item(key = "sleep-chart") {
                    NutrixCard {
                        val last14 = (13 downTo 0).map { back ->
                            val day = TimeUtils.todayEpochDay() - back
                            day to (state.sleepMinutes[day] ?: 0) / 60.0
                        }
                        val day = state.sleeps.maxOf { it.localDate }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IconBadge(Icons.Rounded.Bedtime, colors.sleep, size = 44.dp)
                            Column {
                                Text(stringResource(R.string.alignment_sleep_summary), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(Formatters.duration((state.sleepMinutes[day] ?: SleepTotals.minutes(state.sleeps.filter { it.localDate == day })).toLong(), h, m), style = MaterialTheme.typography.headlineMedium)
                                Text(Formatters.dayMonthShort(LocalDate.ofEpochDay(day)), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        MiniBars(
                            height = 64.dp,
                            values = last14.map { it.second },
                            labels = last14.map { Formatters.dayMonthShort(LocalDate.ofEpochDay(it.first)) },
                            color = colors.sleep,
                            valueText = { Formatters.duration((it * 60).toLong(), h, m) },
                        )
                    }
                }
            }

            if (state.anySourceEnabled) {
                item(key = "steps") {
                    NutrixCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Rounded.DirectionsRun, colors.activity)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.steps_title), style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(10.dp))
                        val days = (13 downTo 0).map { back ->
                            val day = TimeUtils.todayEpochDay() - back
                            day to (state.activity.firstOrNull { it.localDate == day }?.steps ?: 0L)
                        }
                        MiniBars(
                            values = days.map { it.second.toDouble() },
                            labels = days.map { Formatters.dayMonthShort(LocalDate.ofEpochDay(it.first)) },
                            color = colors.activity,
                            valueText = { Formatters.integer(it.toLong()) },
                        )
                        state.activity.lastOrNull { it.localDate == TimeUtils.todayEpochDay() }?.let { today ->
                            today.stepsSource?.let {
                                Text(
                                    "${stringResource(R.string.label_source)}: ${stringResource(it.labelRes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                today.activeKcal?.let { LabeledValue(stringResource(R.string.energy_active), "${Formatters.integer(it.toLong())} ${stringResource(R.string.unit_kcal)}") }
                                today.totalKcal?.let { LabeledValue(stringResource(R.string.energy_total), "${Formatters.integer(it.toLong())} ${stringResource(R.string.unit_kcal)}") }
                            }
                        }
                    }
                }
                item(key = "heart") {
                    val today = state.activity.firstOrNull { it.localDate == TimeUtils.todayEpochDay() }
                    NutrixCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Rounded.Favorite, colors.heart)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.heart_today), style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(10.dp))
                        if (today?.hrAvg == null && today?.hrLatestBpm == null) {
                            Text(stringResource(R.string.label_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            val bpm = stringResource(R.string.unit_bpm)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                today.hrLatestBpm?.let { LabeledValue(stringResource(R.string.heart_latest), "$it $bpm") }
                                today.hrMin?.let { LabeledValue(stringResource(R.string.heart_min), "$it") }
                                today.hrAvg?.let { LabeledValue(stringResource(R.string.heart_avg), "$it") }
                                today.hrMax?.let { LabeledValue(stringResource(R.string.heart_max), "$it") }
                            }
                            today.hrSource?.let {
                                Text(
                                    "${stringResource(R.string.label_source)}: ${stringResource(it.labelRes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item(key = "ex-h") { SectionHeader(stringResource(R.string.exercise_title)) }
                if (state.exercises.isEmpty()) {
                    item { Text(stringResource(R.string.exercise_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
                }
                items(state.exercises, key = { "ex-${it.id}" }) { ex -> ExerciseRow(ex) }
            }
            if (state.sleeps.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.alignment_sleep_history)) }
                items(state.sleeps.take(14), key = { "sleep-${it.id}" }) { s -> SleepRow(s, onClick = { onOpenSleep(s.id) }) }
            }

        }
    }
}

@Composable
fun sourceLabel(source: DataSource, app: String?): String {
    val appName = sourceAppName(app)
    return when {
        source == DataSource.HEALTH_CONNECT && appName != null && appName != "Health Connect" -> stringResource(R.string.source_via_hc, appName)
        else -> stringResource(source.labelRes)
    }
}

@Composable
fun SleepRow(s: SleepEntity, onClick: () -> Unit) {
    var showSource by remember { mutableStateOf(false) }
    val colors = LocalCategoryColors.current
    val h = stringResource(R.string.unit_hour_short)
    val m = stringResource(R.string.unit_minute_short)
    NutrixCard(onClick = { if (s.source == DataSource.MANUAL) onClick() else showSource = true }, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Bedtime, colors.sleep, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(Formatters.duration(s.durationMinutes.toLong(), h, m), style = MaterialTheme.typography.titleSmall)
                Text(
                    "${Formatters.dayMonthShort(LocalDate.ofEpochDay(s.localDate))} · ${Formatters.time(s.startMillis, s.zoneId)} – ${Formatters.time(s.endMillis, s.zoneId)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Pill(sourceLabel(s.source, s.sourceApp), if (s.source == DataSource.MANUAL) colors.sleep else colors.activity)
        }
    }
    if (showSource) androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showSource = false },
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(Formatters.duration(s.durationMinutes.toLong(), h, m), style = MaterialTheme.typography.headlineMedium)
            Text(Formatters.dateTime(s.startMillis, s.zoneId) + " — " + Formatters.dateTime(s.endMillis, s.zoneId), style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Rounded.Bedtime, colors.sleep, size = 44.dp)
                Column {
                    Text(stringResource(R.string.label_source), style = MaterialTheme.typography.labelMedium)
                    Text(sourceLabel(s.source, s.sourceApp), style = MaterialTheme.typography.titleMedium)
                }
            }
            Text(stringResource(R.string.imported_readonly), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { showSource = false }) { Text(stringResource(R.string.action_close)) }
        }
    }

}

@Composable
private fun ExerciseRow(e: ExerciseEntity) {
    val colors = LocalCategoryColors.current
    val h = stringResource(R.string.unit_hour_short)
    val m = stringResource(R.string.unit_minute_short)
    NutrixCard(contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.DirectionsRun, colors.activity, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title ?: com.nutrix.app.feature.calendar.exerciseName(e.exerciseType), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Formatters.dayMonthShort(LocalDate.ofEpochDay(e.localDate))} · ${Formatters.time(e.startMillis, e.zoneId)} · " +
                        Formatters.duration((e.endMillis - e.startMillis) / 60_000L, h, m) +
                        (e.kcal?.let { " · ${it.toInt()} ${stringResource(R.string.unit_kcal)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Pill(sourceLabel(e.source, e.sourceApp), colors.activity)
        }
    }
}

// ---------- Manual sleep editor ----------

data class SleepForm(
    val id: Long = 0,
    val startDate: LocalDate = LocalDate.now().minusDays(1),
    val startTime: LocalTime = LocalTime.of(23, 0),
    val endDate: LocalDate = LocalDate.now(),
    val endTime: LocalTime = LocalTime.of(7, 0),
    val note: String = "",
) {
    fun startMillis(zone: ZoneId) = RecordTime.of(startDate, startTime, zone).epochMillis
    fun endMillis(zone: ZoneId) = RecordTime.of(endDate, endTime, zone).epochMillis
    val valid: Boolean
        get() {
            val zone = ZoneId.systemDefault()
            val d = endMillis(zone) - startMillis(zone)
            return d in 1..(24 * 3_600_000L)
        }
}

@HiltViewModel
class SleepEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val health: HealthRepository,
    private val undo: UndoManager,
    private val messages: UserMessages,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<SleepEditorRoute>()
    private val _form = MutableStateFlow(SleepForm())
    val form: StateFlow<SleepForm> = _form.asStateFlow()
    val closed = MutableStateFlow(false)
    val isEdit = route.sleepId != 0L
    val loaded = MutableStateFlow(!isEdit)
    val saving = MutableStateFlow(false)
    val error = MutableStateFlow(false)

    init {
        if (isEdit) viewModelScope.launch {
            val s = health.getSleep(route.sleepId)?.takeIf { it.source == DataSource.MANUAL }
                ?: run { closed.value = true; return@launch }
            val start = RecordTime(s.startMillis, s.zoneId)
            val end = RecordTime(s.endMillis, s.zoneId)
            _form.value = SleepForm(s.id, start.localDate, start.localTime, end.localDate, end.localTime, s.note.orEmpty())
            loaded.value = true
        }
    }

    fun update(transform: (SleepForm) -> SleepForm) {
        if (loaded.value && !saving.value && !closed.value) _form.update(transform)
    }

    fun save() {
        val f = _form.value
        if (!f.valid || !loaded.value || saving.value || closed.value) return
        saving.value = true
        error.value = false
        val zone = ZoneId.systemDefault()
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    health.saveManualSleep(f.id, f.startMillis(zone), f.endMillis(zone), zone, f.note)
                    messages.show(R.string.sleep_saved)
                    closed.value = true
                } catch (_: Exception) {
                    saving.value = false
                    error.value = true
                }
            }
        }
    }

    fun delete() {
        if (!loaded.value || saving.value || closed.value) return
        saving.value = true
        viewModelScope.launch {
            try {
                val removed = health.deleteSleep(_form.value.id) ?: kotlin.error("Sleep no longer exists")
                undo.offer(undo = { health.restoreSleep(removed) })
                closed.value = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { saving.value = false; error.value = true }
        }
    }
}

@Composable
fun SleepEditorScreen(onClose: () -> Unit, viewModel: SleepEditorViewModel = hiltViewModel()) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val closed by viewModel.closed.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler(enabled = saving) { }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val colors = LocalCategoryColors.current
    LaunchedEffect(closed) { if (closed) onClose() }
    NutrixScreen(
        title = stringResource(if (viewModel.isEdit) R.string.sleep_edit_title else R.string.sleep_add_title),
        onBack = { if (!saving) onClose() },
        actions = { if (viewModel.isEdit) IconButton(enabled = loaded && !saving, onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete)) } },
        bottomBar = {
            NutrixBottomAction(stringResource(if (saving) R.string.action_saving else R.string.action_save), viewModel::save,
                enabled = loaded && form.valid, loading = saving)
        },
    ) { padding ->
        ScreenList(padding, bottomExtra = 24.dp) {
            if (error) item { Text(stringResource(R.string.sleep_save_error), color = MaterialTheme.colorScheme.error) }
            item {
                NutrixCard {
                    Text(stringResource(R.string.sleep_fell_asleep), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    DateTimeRow(form.startDate, form.startTime, { d -> viewModel.update { it.copy(startDate = d) } }, { t -> viewModel.update { it.copy(startTime = t) } })
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.sleep_woke_up), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    DateTimeRow(form.endDate, form.endTime, { d -> viewModel.update { it.copy(endDate = d) } }, { t -> viewModel.update { it.copy(endTime = t) } })
                    if (form.valid) {
                        val zone = ZoneId.systemDefault()
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "${stringResource(R.string.sleep_duration)}: " + Formatters.duration(
                                (form.endMillis(zone) - form.startMillis(zone)) / 60_000L,
                                stringResource(R.string.unit_hour_short),
                                stringResource(R.string.unit_minute_short),
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.sleep,
                        )
                    } else {
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.sleep_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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

