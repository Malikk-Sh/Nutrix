package com.nutrix.app.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import com.nutrix.app.domain.SleepTotals
import com.nutrix.app.core.ui.icons.Bedtime
import com.nutrix.app.core.ui.icons.ChevronRight
import com.nutrix.app.core.ui.icons.DirectionsRun
import com.nutrix.app.core.ui.icons.Healing
import com.nutrix.app.core.ui.icons.MonitorWeight
import com.nutrix.app.core.ui.icons.PhotoCamera
import com.nutrix.app.core.ui.icons.Restaurant
import androidx.compose.material.icons.rounded.Search
import com.nutrix.app.core.ui.icons.Today
import com.nutrix.app.core.ui.icons.WaterDrop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrix.app.R
import com.nutrix.app.core.model.WeightUnit
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.ui.components.IconBadge
import com.nutrix.app.core.ui.components.CardHeader
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.PhotoThumb
import com.nutrix.app.core.ui.components.Pill
import com.nutrix.app.core.ui.components.ProgressBar
import com.nutrix.app.core.ui.components.SectionHeader
import com.nutrix.app.core.ui.components.Sparkline
import com.nutrix.app.core.ui.labelRes
import com.nutrix.app.core.ui.theme.LocalCategoryColors
import com.nutrix.app.core.ui.theme.MetricTextStyle
import com.nutrix.app.core.ui.theme.NutrixTokens
import com.nutrix.app.core.util.Units
import com.nutrix.app.feature.activity.sourceLabel
import com.nutrix.app.feature.calendar.TimelineRow
import com.nutrix.app.feature.calendar.TimelineTarget
import com.nutrix.app.feature.calendar.target
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Navigation callbacks used by the dashboard. */
data class DashboardActions(
    val onSearch: () -> Unit,
    val onFoodDiary: () -> Unit,
    val onAddFood: (camera: Boolean) -> Unit,
    val onWater: () -> Unit,
    val onAddDrink: () -> Unit,
    val onWeight: (add: Boolean) -> Unit,
    val onSleepActivity: () -> Unit,
    val onIntegrations: () -> Unit,
    val onSymptoms: (add: Boolean) -> Unit,
    val onTimeline: (TimelineTarget) -> Unit,
    val onOpenToday: () -> Unit,
)

@Composable
fun DashboardScreen(contentPadding: PaddingValues, actions: DashboardActions, viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = LocalCategoryColors.current
    val haptic = LocalHapticFeedback.current
    val unit = state.settings.weightUnit
    val unitLabel = stringResource(if (unit == WeightUnit.KG) R.string.unit_kg else R.string.unit_lb)
    val h = stringResource(R.string.unit_hour_short)
    val m = stringResource(R.string.unit_minute_short)
    LaunchedEffect(Unit) { viewModel.refreshHealth() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compactMetrics = maxWidth >= 360.dp && maxWidth < 600.dp && LocalDensity.current.fontScale <= 1.2f
    LazyVerticalStaggeredGrid(
        columns = if (compactMetrics) StaggeredGridCells.Fixed(2) else StaggeredGridCells.Adaptive(minSize = 340.dp),
        modifier = Modifier
            .widthIn(max = 1100.dp)
            .align(Alignment.TopCenter)
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(
            start = NutrixTokens.ScreenPadding,
            end = NutrixTokens.ScreenPadding,
            top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 96.dp,
        ),
        verticalItemSpacing = NutrixTokens.CardSpacing,
        horizontalArrangement = Arrangement.spacedBy(NutrixTokens.CardSpacing),
    ) {
        item(key = "header", span = StaggeredGridItemSpan.FullLine) {
            Header(state, onSearch = actions.onSearch)
        }

        item(key = "food", span = if (compactMetrics) StaggeredGridItemSpan.FullLine else StaggeredGridItemSpan.SingleLane) {
            NutrixCard(onClick = actions.onFoodDiary) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconBadge(Icons.Rounded.Restaurant, colors.meal, size = 30.dp)
                            Text(stringResource(R.string.card_food), style = MaterialTheme.typography.titleSmall)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(pluralStringResource(R.plurals.meals_count, state.food.count, state.food.count), style = MaterialTheme.typography.titleLarge)
                        val last = state.food.last
                        Text(if (last != null && last.meal.localDate == state.epochDay) stringResource(R.string.card_food_last, Formatters.time(last.meal.time))
                            else stringResource(R.string.card_food_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.food.photos.firstOrNull()?.let { PhotoThumb(it, size = 88.dp, shape = androidx.compose.foundation.shape.CircleShape) }
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { actions.onAddFood(true) }, shape = MaterialTheme.shapes.small) {
                        Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.card_food_photo))
                    }
                    Button(onClick = { actions.onAddFood(false) }, shape = MaterialTheme.shapes.small) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.card_food_add))
                    }
                }
            }
        }

        item(key = "water", span = if (compactMetrics) StaggeredGridItemSpan.FullLine else StaggeredGridItemSpan.SingleLane) {
            NutrixCard(onClick = actions.onWater) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconBadge(Icons.Rounded.WaterDrop, colors.water, size = 36.dp)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.card_water), style = MaterialTheme.typography.titleSmall)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(Formatters.integer(state.water.totalMl.toLong()), style = MetricTextStyle.copy(fontSize = 26.sp, lineHeight = 32.sp))
                            Text(state.water.goalMl?.let { " / ${Formatters.integer(it.toLong())} ${stringResource(R.string.unit_ml)}" } ?: " ${stringResource(R.string.unit_ml)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
                        }
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.water.goalMl?.let { goal ->
                    Spacer(Modifier.height(8.dp))
                    ProgressBar(state.water.totalMl / goal.toFloat(), colors.water, Modifier.fillMaxWidth().height(10.dp))
                }
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.water.quickVolumes.forEach { v ->
                        FilledTonalButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.addWater(v)
                        }, shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.water.copy(alpha = 0.12f), contentColor = colors.water),
                            contentPadding = PaddingValues(horizontal = 10.dp)) { Text("+$v", style = MaterialTheme.typography.labelMedium) }
                    }
                    FilledTonalIconButton(onClick = actions.onAddDrink) { Icon(Icons.Rounded.Add, stringResource(R.string.card_water_custom)) }
                }
            }
        }

        item(key = "weight") {
            NutrixCard(modifier = if (compactMetrics) Modifier.heightIn(min = 148.dp) else Modifier, onClick = { actions.onWeight(false) }) {
                CardHeader(Icons.Rounded.MonitorWeight, stringResource(R.string.card_weight), colors.weight) {
                    FilledTonalIconButton(onClick = { actions.onWeight(true) }) { Icon(Icons.Rounded.Add, stringResource(R.string.weight_add)) }
                }
                Spacer(Modifier.height(6.dp))
                val latest = state.weight.latest
                if (latest == null) {
                    Text(stringResource(R.string.card_weight_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    if (compactMetrics) {
                        Text(Units.formatWeight(latest.weightKg, unit, unitLabel), style = MetricTextStyle.copy(fontSize = 26.sp, lineHeight = 32.sp))
                        state.weight.weekChangeKg?.let {
                            Text(stringResource(R.string.card_weight_week, Formatters.signedDecimal(Units.toDisplay(it, unit)) + " " + unitLabel),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(8.dp))
                        Sparkline(state.weight.spark, colors.weight, Modifier.fillMaxWidth().height(28.dp))
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(Units.formatWeight(latest.weightKg, unit, unitLabel), style = MetricTextStyle)
                                state.weight.weekChangeKg?.let {
                                    Text(stringResource(R.string.card_weight_week, Formatters.signedDecimal(Units.toDisplay(it, unit)) + " " + unitLabel),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Sparkline(state.weight.spark, colors.weight, Modifier.width(110.dp).height(48.dp))
                        }
                    }
                }
            }
        }

        item(key = "sleep") {
            NutrixCard(modifier = if (compactMetrics) Modifier.heightIn(min = 148.dp) else Modifier, onClick = actions.onSleepActivity) {
                CardHeader(Icons.Rounded.Bedtime, stringResource(R.string.card_sleep), colors.sleep) {
                    Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                val sleep = state.sleep
                if (sleep == null) {
                    Text(stringResource(R.string.card_sleep_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(Formatters.duration(SleepTotals.minutes(state.sleepSessions).toLong(), h, m), style = if (compactMetrics) MetricTextStyle.copy(fontSize = 22.sp, lineHeight = 29.sp) else MetricTextStyle)
                    Text(Formatters.dayMonthShort(LocalDate.ofEpochDay(sleep.localDate)), style = MaterialTheme.typography.bodySmall)
                    state.sleepSessions.sortedBy { it.startMillis }.let { if (compactMetrics) it.take(1) else it }.forEach { session ->
                        Text(
                            stringResource(R.string.card_sleep_times, Formatters.time(session.startMillis, session.zoneId), Formatters.time(session.endMillis, session.zoneId)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Pill(sourceLabel(session.source, session.sourceApp), colors.sleep)
                    }
                    if (compactMetrics && state.sleepSessions.size > 1) Text(stringResource(R.string.sleep_session_count, state.sleepSessions.size),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item(key = "activity", span = if (compactMetrics) StaggeredGridItemSpan.FullLine else StaggeredGridItemSpan.SingleLane) {
            NutrixCard(onClick = if (state.activity.enabled) actions.onSleepActivity else actions.onIntegrations) {
                CardHeader(Icons.Rounded.DirectionsRun, stringResource(R.string.card_activity), colors.activity) {
                    Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                val a = state.activity
                when {
                    !a.enabled -> Text(stringResource(R.string.card_activity_connect), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    a.today == null -> Text(stringResource(R.string.card_activity_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> {
                        Text(stringResource(R.string.card_activity_steps, Formatters.integer(a.today.steps ?: 0)), style = MaterialTheme.typography.titleLarge)
                        val hr = a.today.hrLatestBpm ?: a.today.hrAvg
                        if (hr != null) Text(stringResource(R.string.card_activity_hr, hr), style = MaterialTheme.typography.bodyMedium, color = colors.heart)
                    }
                }
                if (a.enabled) {
                    Text(
                        a.lastSync?.let { stringResource(R.string.activity_last_sync, Formatters.dateTime(it, ZoneId.systemDefault().id)) } ?: stringResource(R.string.activity_never_synced),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item(key = "wellbeing", span = if (compactMetrics) StaggeredGridItemSpan.FullLine else StaggeredGridItemSpan.SingleLane) {
            NutrixCard(onClick = { actions.onSymptoms(false) }) {
                CardHeader(Icons.Rounded.Healing, stringResource(R.string.card_wellbeing), colors.symptom) {
                    FilledTonalIconButton(onClick = { actions.onSymptoms(true) }) { Icon(Icons.Rounded.Add, stringResource(R.string.card_wellbeing_add)) }
                }
                Spacer(Modifier.height(6.dp))
                if (state.symptoms.isEmpty()) {
                    Text(stringResource(R.string.card_wellbeing_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    state.symptoms.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(
                                s.customName ?: stringResource(s.type.labelRes),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${Formatters.dayMonthShort(s.time.localDate)}, ${Formatters.time(s.time)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item(key = "timeline-h", span = StaggeredGridItemSpan.FullLine) {
            SectionHeader(stringResource(R.string.timeline_today), actionLabel = stringResource(R.string.calendar_open_day), onAction = actions.onOpenToday)
        }
        if (state.loaded && state.timeline.isEmpty()) {
            item(key = "timeline-empty", span = StaggeredGridItemSpan.FullLine) {
                Text(stringResource(R.string.timeline_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            item(key = "timeline", span = StaggeredGridItemSpan.FullLine) {
                Column {
                    state.timeline.forEachIndexed { i, item ->
                        TimelineRow(item, unit, isLast = i == state.timeline.lastIndex, onClick = { actions.onTimeline(item.target()) })
                    }
                }
            }
        }
    }
}
}

@Composable
private fun Header(state: DashboardState, onSearch: () -> Unit) {
    val hour = LocalTime.now().hour
    val greeting = stringResource(
        when (hour) {
            in 5..11 -> R.string.greeting_morning
            in 12..17 -> R.string.greeting_day
            in 18..22 -> R.string.greeting_evening
            else -> R.string.greeting_night
        },
    )
    val name = state.settings.userName.trim()
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                if (name.isNotEmpty()) stringResource(R.string.greeting_name, greeting, name) else greeting,
                style = MaterialTheme.typography.titleLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Today, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    Formatters.weekdayDayMonth(LocalDate.ofEpochDay(state.epochDay)).replaceFirstChar { it.titlecase() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

        }
        IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, stringResource(R.string.action_search)) }
    }
}
