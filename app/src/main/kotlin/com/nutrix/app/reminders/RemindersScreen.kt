package com.nutrix.app.reminders

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.nutrix.app.core.time.AppLocale
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.core.ui.components.InfoBanner
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixScreen
import com.nutrix.app.core.ui.components.NutrixTimePickerDialog
import com.nutrix.app.core.ui.components.ScreenList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import javax.inject.Inject

@HiltViewModel
class RemindersViewModel @Inject constructor(
    private val settings: SettingsRepository,
    val scheduler: ReminderScheduler,
) : ViewModel() {
    val state = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun update(config: ReminderConfig) {
        viewModelScope.launch {
            settings.updateReminder(config)
            scheduler.schedule(config.type, settings.current())
        }
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
    val settings = s ?: return

    NutrixScreen(title = stringResource(R.string.reminders_title), onBack = onBack) { padding ->
        ScreenList(padding) {
            item { InfoBanner(stringResource(R.string.reminders_intro), Icons.Rounded.Info) }
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
                        settings = settings,
                        scheduler = viewModel.scheduler,
                        onChange = { c ->
                            if (c.enabled && !permissionGranted && Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            viewModel.update(c)
                        },
                        onEditTime = { edit = it },
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
    settings: UserSettings,
    scheduler: ReminderScheduler,
    onChange: (ReminderConfig) -> Unit,
    onEditTime: (TimeEdit) -> Unit,
) {
    val title = stringResource(
        when (config.type) {
            ReminderType.MEAL -> R.string.reminder_meal
            ReminderType.WATER -> R.string.reminder_water
            ReminderType.WEIGHT -> R.string.reminder_weight
            ReminderType.WELLBEING -> R.string.reminder_wellbeing
            ReminderType.MISSED_ENTRIES -> R.string.reminder_missed
        },
    )
    NutrixCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(config.enabled, { onChange(config.copy(enabled = it)) })
        }
        if (config.type == ReminderType.MISSED_ENTRIES) {
            Text(stringResource(R.string.reminder_missed_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(config.enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                val next = scheduler.nextFor(config.type, settings)
                Text(
                    next?.let { stringResource(R.string.reminder_next, "${Formatters.dayMonthShort(it.toLocalDate())}, ${Formatters.time(it.toLocalTime())}") }
                        ?: stringResource(R.string.reminder_none_planned),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
