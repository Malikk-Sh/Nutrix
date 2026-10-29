package com.nutrix.app.reminders

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nutrix.app.R
import com.nutrix.app.core.datastore.ReminderConfig
import com.nutrix.app.core.datastore.ReminderMode
import com.nutrix.app.core.datastore.ReminderType
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.datastore.UserSettings
import com.nutrix.app.core.datastore.supportsAdaptive
import com.nutrix.app.core.time.AppLocale
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.core.ui.components.InfoDisclosure
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import com.nutrix.app.core.ui.components.IconBadge
import com.nutrix.app.core.ui.icons.*
import com.nutrix.app.core.ui.theme.LocalCategoryColors
import com.nutrix.app.core.ui.components.InfoBanner
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixScreen
import com.nutrix.app.core.ui.components.NutrixTimePickerDialog
import com.nutrix.app.core.ui.components.ScreenList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import javax.inject.Inject

data class RemindersState(val settings: UserSettings, val routine: Routine, val ignores: Map<ReminderType, Int>)

@HiltViewModel
class RemindersViewModel @Inject constructor(
    private val settings: SettingsRepository,
    val scheduler: ReminderScheduler,
    private val feedback: ReminderFeedback,
    private val afterMeal: AfterMealCheckIn,
    routines: RoutineRepository,
) : ViewModel() {
    val state = combine(settings.settings, routines.routine, settings.reminderIgnores, ::RemindersState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun update(config: ReminderConfig) {
        viewModelScope.launch {
            val previous = settings.current().reminders.firstOrNull { it.type == config.type }
            // Switching a reminder (or its adaptation) back on starts without the old ignored streak.
            if (previous != null && (config.enabled && !previous.enabled || config.adaptive && !previous.adaptive)) {
                settings.resetReminderIgnores(config.type)
            }
            settings.updateReminder(config)
            scheduler.schedule(config.type, settings.current())
            if (config.type == ReminderType.AFTER_MEAL && config.enabled) afterMeal.planLatest()
        }
    }

    /** Leaves the quieter mode a reminder entered after being ignored several times in a row. */
    fun restore(type: ReminderType) {
        viewModelScope.launch { feedback.engaged(type) }
    }

    fun setQuiet(enabled: Boolean, start: Int, end: Int) {
        viewModelScope.launch {
            settings.setQuietHours(enabled, start, end)
            scheduler.rescheduleAll()
        }
    }

    fun setSnooze(minutes: Int) {
        viewModelScope.launch { settings.setSnoozeMinutes(minutes) }
    }
}

private sealed interface TimeEdit {
    data class Fixed(val type: ReminderType, val index: Int, val initial: Int) : TimeEdit
    data class WindowStart(val type: ReminderType) : TimeEdit
    data class WindowEnd(val type: ReminderType) : TimeEdit
    data object QuietStart : TimeEdit
    data object QuietEnd : TimeEdit
}

@Composable
fun RemindersScreen(onBack: () -> Unit, viewModel: RemindersViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionGranted = it }
    var edit by remember { mutableStateOf<TimeEdit?>(null) }
    val state = s ?: return
    val settings = state.settings

    NutrixScreen(title = stringResource(R.string.reminders_title), onBack = onBack) { padding ->
        ScreenList(padding) {
            item { InfoDisclosure(stringResource(R.string.alignment_reminders_details), stringResource(R.string.reminders_intro), Icons.Rounded.Info) }
            if (!permissionGranted) {
                item {
                    NutrixCard {
                        Text(stringResource(R.string.reminders_permission))
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                            Text(stringResource(R.string.reminders_permission_grant))
                        }
                    }
                }
            }
            settings.reminders.forEach { config ->
                item(key = config.type.name) {
                    ReminderCard(
                        config = config,
                        state = state,
                        scheduler = viewModel.scheduler,
                        onChange = { c ->
                            if (c.enabled && !permissionGranted && Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            viewModel.update(c)
                        },
                        onEditTime = { edit = it },
                        onRestore = { viewModel.restore(config.type) },
                    )
                }
            }
            item(key = "quiet") {
                NutrixCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.reminders_quiet), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.reminders_quiet_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(settings.quietHoursEnabled, { viewModel.setQuiet(it, settings.quietStartMinutes, settings.quietEndMinutes) })
                    }
                    AnimatedVisibility(settings.quietHoursEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = { edit = TimeEdit.QuietStart }, label = { Text(Formatters.time(TimeUtils.timeOfMinutes(settings.quietStartMinutes))) })
                            Text("—", modifier = Modifier.align(Alignment.CenterVertically))
                            AssistChip(onClick = { edit = TimeEdit.QuietEnd }, label = { Text(Formatters.time(TimeUtils.timeOfMinutes(settings.quietEndMinutes))) })
                        }
                    }
                }
            }
            item(key = "snooze") {
                NutrixCard {
                    Text(stringResource(R.string.reminders_snooze), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(10, 15, 30, 60, 120).forEach { m ->
                            FilterChip(settings.snoozeMinutes == m, { viewModel.setSnooze(m) }, label = { Text(stringResource(R.string.reminders_snooze_value, m)) })
                        }
                    }
                }
            }
        }
    }

    edit?.let { e ->
        val initial = when (e) {
            is TimeEdit.Fixed -> e.initial
            is TimeEdit.WindowStart -> settings.reminders.first { it.type == e.type }.windowStartMinutes
            is TimeEdit.WindowEnd -> settings.reminders.first { it.type == e.type }.windowEndMinutes
            TimeEdit.QuietStart -> settings.quietStartMinutes
            TimeEdit.QuietEnd -> settings.quietEndMinutes
        }
        NutrixTimePickerDialog(
            initial = TimeUtils.timeOfMinutes(initial),
            onDismiss = { edit = null },
            onConfirm = { t ->
                val m = TimeUtils.minutesOfDay(t)
                when (e) {
                    is TimeEdit.Fixed -> {
                        val c = settings.reminders.first { it.type == e.type }
                        val times = c.times.toMutableList()
                        if (e.index in times.indices) times[e.index] = m else times += m
                        viewModel.update(c.copy(times = times.distinct().sorted()))
                    }
                    is TimeEdit.WindowStart -> settings.reminders.first { it.type == e.type }.let { viewModel.update(it.copy(windowStartMinutes = m)) }
                    is TimeEdit.WindowEnd -> settings.reminders.first { it.type == e.type }.let { viewModel.update(it.copy(windowEndMinutes = m)) }
                    TimeEdit.QuietStart -> viewModel.setQuiet(true, m, settings.quietEndMinutes)
                    TimeEdit.QuietEnd -> viewModel.setQuiet(true, settings.quietStartMinutes, m)
                }
                edit = null
            },
        )
    }
}

@Composable
private fun ReminderCard(
    config: ReminderConfig,
    state: RemindersState,
    scheduler: ReminderScheduler,
    onChange: (ReminderConfig) -> Unit,
    onEditTime: (TimeEdit) -> Unit,
    onRestore: () -> Unit,
) {
    val settings = state.settings
    val routine = state.routine
    val ignores = state.ignores[config.type] ?: 0
    val adaptive = config.adaptive && config.type.supportsAdaptive
    // Learned meal and weighing times replace the user's own; water keeps its checkpoints and compares the pace.
    val followsRoutine = adaptive && AdaptivePlan.learned(config.type, routine)
    val replacesTimes = followsRoutine && config.type != ReminderType.WATER
    val quieted = config.enabled && ignores >= AdaptivePlan.QUIET_AFTER_IGNORES && (adaptive || config.type == ReminderType.AFTER_MEAL)
    val hourShort = stringResource(R.string.unit_hour_short)
    val minuteShort = stringResource(R.string.unit_minute_short)
    fun time(m: Int) = Formatters.time(TimeUtils.timeOfMinutes(m))
    fun every(m: Int) = Formatters.duration(m.toLong(), hourShort, minuteShort)
    val title = stringResource(
        when (config.type) {
            ReminderType.MEAL -> R.string.reminder_meal
            ReminderType.WATER -> R.string.reminder_water
            ReminderType.WEIGHT -> R.string.reminder_weight
            ReminderType.WELLBEING -> R.string.reminder_wellbeing
            ReminderType.MISSED_ENTRIES -> R.string.reminder_missed
            ReminderType.AFTER_MEAL -> R.string.reminder_after_meal
        },
    )
    val summary = when {
        !config.enabled -> stringResource(R.string.alignment_disabled)
        config.type == ReminderType.AFTER_MEAL -> stringResource(R.string.reminder_after_meal_summary, every(config.intervalMinutes))
        replacesTimes -> AdaptivePlan.timesFor(config, routine, ignores, LocalDate.now()).joinToString(" · ") { time(it) } +
            " · " + stringResource(R.string.reminder_adaptive_on)
        config.mode == ReminderMode.FIXED_TIMES -> config.times.sorted().joinToString(" · ") { time(it) }
        else -> stringResource(R.string.reminder_every, every(config.intervalMinutes)) +
            if (followsRoutine) " · " + stringResource(R.string.reminder_adaptive_pace) else ""
    }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    NutrixCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val colors = LocalCategoryColors.current
            val (icon, tint) = when (config.type) {
                ReminderType.MEAL -> Icons.Rounded.Restaurant to colors.meal
                ReminderType.WATER -> Icons.Rounded.WaterDrop to colors.water
                ReminderType.WEIGHT -> Icons.Rounded.MonitorWeight to colors.weight
                ReminderType.WELLBEING -> Icons.Rounded.Healing to colors.symptom
                ReminderType.MISSED_ENTRIES -> Icons.Rounded.StickyNote2 to colors.note
                ReminderType.AFTER_MEAL -> Icons.Rounded.EmojiFoodBeverage to colors.symptom
            }
            IconBadge(icon, tint, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("reminder-summary-${config.type.name}"))
            }
            IconButton(onClick = { showEditor = true }) { Icon(Icons.Rounded.Tune, stringResource(R.string.alignment_configure) + ": " + title) }
            Switch(config.enabled, { onChange(config.copy(enabled = it)) })
        }
        if (config.type == ReminderType.MISSED_ENTRIES) {
            Text(stringResource(R.string.reminder_missed_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (config.type == ReminderType.AFTER_MEAL) {
            Text(stringResource(R.string.reminder_after_meal_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(quieted) {
            Column(Modifier.testTag("reminder-quieted-${config.type.name}")) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(if (config.type == ReminderType.AFTER_MEAL) R.string.reminder_after_meal_quieted else R.string.reminder_quieted),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onRestore) { Text(stringResource(R.string.reminder_quieted_reset)) }
            }
        }
    }
    if (showEditor) androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showEditor = false },
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { showEditor = false }) { Icon(Icons.Rounded.Close, stringResource(R.string.action_close)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.alignment_configure), modifier = Modifier.weight(1f))
                Switch(config.enabled, { onChange(config.copy(enabled = it)) })
            }
            if (config.enabled && config.type == ReminderType.AFTER_MEAL) {
                Text(stringResource(R.string.reminder_after_meal_delay), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(60, 90, 120, 180).forEach { m ->
                        FilterChip(config.intervalMinutes == m, { onChange(config.copy(intervalMinutes = m)) }, label = { Text(every(m)) })
                    }
                }
                Text(stringResource(R.string.reminder_after_meal_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (config.enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (config.type.supportsAdaptive) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.reminder_adaptive), modifier = Modifier.weight(1f))
                        Switch(config.adaptive, { onChange(config.copy(adaptive = it)) }, modifier = Modifier.testTag("reminder-adaptive-${config.type.name}"))
                    }
                    if (adaptive) RoutineSummary(config.type, routine, followsRoutine)
                }
                if (!replacesTimes) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(config.mode == ReminderMode.FIXED_TIMES, { onChange(config.copy(mode = ReminderMode.FIXED_TIMES, times = config.times.ifEmpty { listOf(12 * 60) })) }, label = { Text(stringResource(R.string.reminder_mode_fixed)) })
                    FilterChip(config.mode == ReminderMode.INTERVAL, { onChange(config.copy(mode = ReminderMode.INTERVAL)) }, label = { Text(stringResource(R.string.reminder_mode_interval)) })
                }
                if (config.mode == ReminderMode.FIXED_TIMES) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        config.times.sorted().forEachIndexed { i, m ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AssistChip(onClick = { onEditTime(TimeEdit.Fixed(config.type, i, m)) }, label = { Text(Formatters.time(TimeUtils.timeOfMinutes(m))) })
                                if (config.times.size > 1) {
                                    IconButton(onClick = { onChange(config.copy(times = config.times - m)) }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Rounded.Close, stringResource(R.string.action_delete), Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                        AssistChip(onClick = { onEditTime(TimeEdit.Fixed(config.type, -1, 12 * 60)) }, label = { Text(stringResource(R.string.reminder_add_time)) }, leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)) })
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(60, 90, 120, 180, 240).forEach { m ->
                            FilterChip(
                                config.intervalMinutes == m,
                                { onChange(config.copy(intervalMinutes = m)) },
                                label = { Text(stringResource(R.string.reminder_every, Formatters.duration(m.toLong(), stringResource(R.string.unit_hour_short), stringResource(R.string.unit_minute_short)))) },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { onEditTime(TimeEdit.WindowStart(config.type)) }, label = { Text(Formatters.time(TimeUtils.timeOfMinutes(config.windowStartMinutes))) })
                        Text("—")
                        AssistChip(onClick = { onEditTime(TimeEdit.WindowEnd(config.type)) }, label = { Text(Formatters.time(TimeUtils.timeOfMinutes(config.windowEndMinutes))) })
                    }
                }
                }
                Text(stringResource(R.string.reminder_days), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { d ->
                        FilterChip(
                            selected = d.value in config.days,
                            onClick = {
                                val days = if (d.value in config.days) config.days - d.value else config.days + d.value
                                onChange(config.copy(days = days))
                            },
                            label = { Text(d.getDisplayName(TextStyle.SHORT, AppLocale.locale)) },
                        )
                    }
                }
                val next = scheduler.nextFor(config.type, settings, routine, ignores)
                Text(
                    next?.let { stringResource(R.string.reminder_next, "${Formatters.dayMonthShort(it.toLocalDate())}, ${Formatters.time(it.toLocalTime())}") }
                        ?: stringResource(R.string.reminder_none_planned),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            }
            Button(onClick = { showEditor = false }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.action_done)) }
        }
    }
}

/** What the reminder learned about the user's routine, or that it is still learning. */
@Composable
private fun RoutineSummary(type: ReminderType, routine: Routine, learned: Boolean) {
    fun times(slots: List<MealSlot>) = slots.joinToString(", ") { Formatters.time(TimeUtils.timeOfMinutes(it.minute)) }
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.testTag("reminder-routine-${type.name}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(
                when (type) {
                    ReminderType.MEAL -> R.string.reminder_adaptive_hint_meal
                    ReminderType.WATER -> R.string.reminder_adaptive_hint_water
                    else -> R.string.reminder_adaptive_hint_weight
                },
            ),
            style = style, color = color,
        )
        if (!learned) {
            Text(stringResource(R.string.reminder_learning), style = style)
            return@Column
        }
        when (type) {
            ReminderType.MEAL -> {
                Text(stringResource(R.string.reminder_learned_meal, times(routine.weekdayMeals)), style = style)
                if (routine.weekendMeals.map { it.minute } != routine.weekdayMeals.map { it.minute }) {
                    Text(stringResource(R.string.reminder_learned_meal_weekend, times(routine.weekendMeals)), style = style)
                }
            }
            ReminderType.WEIGHT -> routine.weighMinute?.let {
                Text(stringResource(R.string.reminder_learned_weight, Formatters.time(TimeUtils.timeOfMinutes(it))), style = style)
            }
            else -> routine.fluidMedianMl?.let { Text(stringResource(R.string.reminder_learned_water, it), style = style) }
        }
        Text(stringResource(R.string.reminder_learned_basis), style = style, color = color)
    }
}

