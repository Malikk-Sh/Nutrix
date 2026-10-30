package com.nutrix.app.feature.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrix.app.R
import com.nutrix.app.core.model.WeightUnit
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.core.ui.charts.DailyColumnChart
import com.nutrix.app.core.ui.charts.DailyLineChart
import com.nutrix.app.core.ui.components.InfoDisclosure
import com.nutrix.app.core.ui.components.SegmentedChoices
import com.nutrix.app.core.ui.components.IconBadge
import com.nutrix.app.core.ui.icons.*
import com.nutrix.app.core.ui.components.InfoBanner
import com.nutrix.app.core.ui.components.NutrixCard
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.testTag
import com.nutrix.app.core.ui.icons.ChevronRight
import com.nutrix.app.core.ui.icons.Insights
import com.nutrix.app.core.ui.components.CardHeader
import com.nutrix.app.core.ui.components.NutrixScreen
import com.nutrix.app.core.ui.components.ScreenList
import com.nutrix.app.core.ui.components.SectionHeader
import com.nutrix.app.core.ui.theme.LocalCategoryColors
import com.nutrix.app.core.util.Units
import com.nutrix.app.domain.ChartPoint
import com.nutrix.app.domain.Comparison
import com.nutrix.app.domain.CorrelationResult
import com.nutrix.app.domain.Metric
import com.nutrix.app.domain.Strength
import com.nutrix.app.domain.insights.RecordRef
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.max

@Composable
fun AnalyticsScreen(contentPadding: PaddingValues, onOpenInsights: () -> Unit = {}, onOpenRecord: (RecordRef) -> Unit = {},
    viewModel: AnalyticsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    var customDialog by rememberSaveable { mutableStateOf(false) }
    NutrixScreen(title = stringResource(R.string.analytics_title), onBack = null) { padding ->
        ScreenList(PaddingValues(top = padding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding())) {
            item(key = "insights") {
                NutrixCard(onClick = onOpenInsights, modifier = Modifier.testTag("open-insights")) {
                    CardHeader(Icons.Rounded.Insights, stringResource(R.string.insights_title), MaterialTheme.colorScheme.primary) {
                        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.insights_analytics_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "periods") {
                val days = listOf(7, 14, 30, 90, 0)
                SegmentedChoices(days, (period as? AnalyticsPeriod.LastDays)?.days ?: 0,
                    label = { d -> stringResource(when (d) { 7 -> R.string.period_7; 14 -> R.string.period_14; 30 -> R.string.period_30; 90 -> R.string.period_90; else -> R.string.alignment_custom_period }) },
                    onSelect = { d -> if (d == 0) customDialog = true else viewModel.setPeriod(AnalyticsPeriod.LastDays(d)) })
            }
            if (state.loading) {
                item { CircularProgressIndicator() }
                return@ScreenList
            }
            item(key = "tracked") {
                Column {
                    Text(stringResource(R.string.analytics_tracked_days, state.trackedDays, state.totalDays), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    InfoDisclosure(stringResource(R.string.alignment_analytics_note), stringResource(R.string.analytics_untracked_note), Icons.Rounded.Info)
                }
            }
            item(key = "charts-h") { SectionHeader(stringResource(R.string.analytics_section_charts)) }
            item(key = "intake") { IntakeCard(state.intake, state.plans, onOpenRecord, viewModel::setConfirmed) }
            (listOf(Metric.WEIGHT, Metric.FLUID, Metric.SLEEP_DURATION) + Metric.entries.filterNot { it in listOf(Metric.WEIGHT, Metric.FLUID, Metric.SLEEP_DURATION) }).forEach { metric ->
                item(key = "m-$metric") { MetricCard(metric, state.series[metric]?.points.orEmpty(), state.unit) }
            }
            item(key = "cmp-h") { SectionHeader(stringResource(R.string.analytics_section_compare)) }
            item(key = "cmp-note") { InfoBanner(stringResource(R.string.compare_disclaimer), Icons.Rounded.Info, tint = LocalCategoryColors.current.symptom) }
            state.correlations.forEach { c -> item(key = "c-${c.comparison}") { CorrelationCard(c, state.unit) } }
        }
    }
    if (customDialog) {
        val pickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { customDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val s = pickerState.selectedStartDateMillis
                        val e = pickerState.selectedEndDateMillis ?: s
                        if (s != null && e != null) {
                            val from = Instant.ofEpochMilli(s).atZone(ZoneOffset.UTC).toLocalDate()
                            val to = Instant.ofEpochMilli(e).atZone(ZoneOffset.UTC).toLocalDate()
                            viewModel.setPeriod(AnalyticsPeriod.Custom(minOf(from, to), maxOf(from, to)))
                        }
                        customDialog = false
                    },
                    enabled = pickerState.selectedStartDateMillis != null,
                ) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { customDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            DateRangePicker(state = pickerState, modifier = Modifier.height(480.dp))
        }
    }
}

@Composable
private fun MetricCard(metric: Metric, points: List<ChartPoint>, unit: WeightUnit) {
    val colors = LocalCategoryColors.current
    val h = stringResource(R.string.unit_hour_short)
    val m = stringResource(R.string.unit_minute_short)
    val kg = stringResource(if (unit == WeightUnit.KG) R.string.unit_kg else R.string.unit_lb)
    val ml = stringResource(R.string.unit_ml)
    val bpm = stringResource(R.string.unit_bpm)
    val (title, color) = when (metric) {
        Metric.WEIGHT -> stringResource(R.string.metric_weight) to colors.weight
        Metric.MEALS -> stringResource(R.string.metric_meals) to colors.meal
        Metric.FLUID -> stringResource(R.string.metric_fluid) to colors.water
        Metric.APPETITE -> stringResource(R.string.metric_appetite) to colors.meal
        Metric.EARLY_SATIETY -> stringResource(R.string.metric_early_satiety) to colors.symptom
        Metric.NAUSEA -> stringResource(R.string.metric_nausea) to colors.symptom
        Metric.SLEEP_DURATION -> stringResource(R.string.metric_sleep) to colors.sleep
        Metric.BEDTIME -> stringResource(R.string.metric_bedtime) to colors.sleep
        Metric.STEPS -> stringResource(R.string.metric_steps) to colors.activity
        Metric.HEART_RATE -> stringResource(R.string.metric_heart) to colors.heart
    }
    val display = if (metric == Metric.WEIGHT) points.map { it.copy(value = Units.toDisplay(it.value, unit)) } else points
    val format: (Double) -> String = when (metric) {
        Metric.WEIGHT -> { v -> "${Formatters.decimal(v)} $kg" }
        Metric.MEALS, Metric.EARLY_SATIETY, Metric.NAUSEA -> { v -> Formatters.decimal(v, if (v % 1.0 == 0.0) 0 else 1) }
        Metric.FLUID -> { v -> "${Formatters.integer(v.toLong())} $ml" }
        Metric.APPETITE -> { v -> "${Formatters.decimal(v)}/5" }
        Metric.SLEEP_DURATION -> { v -> Formatters.duration((v * 60).toLong(), h, m) }
        Metric.BEDTIME -> { v -> Formatters.time(TimeUtils.eveningMinutesToTime(v.toInt())) }
        Metric.STEPS -> { v -> Formatters.integer(v.toLong()) }
        Metric.HEART_RATE -> { v -> "${v.toInt()} $bpm" }
    }
    NutrixCard {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBadge(when (metric) { Metric.WEIGHT -> Icons.Rounded.MonitorWeight; Metric.FLUID -> Icons.Rounded.WaterDrop;
                Metric.SLEEP_DURATION, Metric.BEDTIME -> Icons.Rounded.Bedtime; Metric.STEPS -> Icons.Rounded.DirectionsRun;
                Metric.HEART_RATE -> Icons.Rounded.Favorite; else -> Icons.Rounded.Restaurant }, color, size = 28.dp)
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        if (display.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(format(display.map { it.value }.average()), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.alignment_metric_average) + " · " + stringResource(R.string.analytics_days, display.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        when (metric) {
            Metric.MEALS, Metric.FLUID, Metric.EARLY_SATIETY, Metric.NAUSEA, Metric.STEPS ->
                DailyColumnChart(display, color, format, height = 160.dp)
            Metric.APPETITE -> DailyLineChart(display, color, format, height = 170.dp, minY = 0.0, maxY = 5.0)
            Metric.SLEEP_DURATION -> DailyLineChart(display, color, format, height = 170.dp, zeroBased = true)
            else -> DailyLineChart(display, color, format, height = 170.dp)
        }
    }
}

@Composable
private fun CorrelationCard(c: CorrelationResult, unit: WeightUnit) {
    val colors = LocalCategoryColors.current
    val (title, xLabel, yLabel) = when (c.comparison) {
        Comparison.APPETITE_VS_SLEEP -> Triple(R.string.compare_appetite_sleep, R.string.compare_axis_sleep, R.string.compare_axis_appetite)
        Comparison.APPETITE_VS_WAKE -> Triple(R.string.compare_appetite_wake, R.string.compare_axis_wake, R.string.compare_axis_appetite)
        Comparison.MEALS_VS_SYMPTOMS -> Triple(R.string.compare_meals_symptoms, R.string.compare_axis_meals, R.string.compare_axis_symptoms)
        Comparison.FLUID_VS_DIZZINESS -> Triple(R.string.compare_fluid_dizziness, R.string.compare_axis_fluid, R.string.compare_axis_dizziness)
        Comparison.WEIGHT_VS_MEALS -> Triple(R.string.compare_weight_meals, R.string.compare_axis_meals, R.string.compare_axis_weight)
    }
    val pairs = if (c.comparison == Comparison.WEIGHT_VS_MEALS) c.pairs.map { it.first to Units.toDisplay(it.second, unit) } else c.pairs
    NutrixCard {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        if (!c.sufficient) {
            Text(
                stringResource(R.string.compare_insufficient, c.minPairs, c.pairs.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val r = c.r!!
            Text(strengthText(c.strength, r), style = MaterialTheme.typography.titleMedium, color = colors.weight)
            Text(stringResource(R.string.compare_r, Formatters.decimal(r, 2), c.pairs.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            ScatterPlot(pairs, colors.weight, stringResource(xLabel), stringResource(yLabel))
        }
    }
}

@Composable
private fun strengthText(s: Strength, r: Double): String = stringResource(
    when (s) {
        Strength.NONE -> R.string.strength_none
        Strength.WEAK -> if (r > 0) R.string.strength_weak_pos else R.string.strength_weak_neg
        Strength.MODERATE -> if (r > 0) R.string.strength_moderate_pos else R.string.strength_moderate_neg
        Strength.STRONG -> if (r > 0) R.string.strength_strong_pos else R.string.strength_strong_neg
    },
)

/** Simple interactive scatter plot: tap near a point to see its values. */
@Composable
private fun ScatterPlot(pairs: List<Pair<Double, Double>>, color: Color, xLabel: String, yLabel: String) {
    var selected by remember(pairs) { mutableIntStateOf(-1) }
    val axis = MaterialTheme.colorScheme.outlineVariant
    val minX = pairs.minOf { it.first }
    val maxX = pairs.maxOf { it.first }
    val minY = pairs.minOf { it.second }
    val maxY = pairs.maxOf { it.second }
    val spanX = max(maxX - minX, 1e-6)
    val spanY = max(maxY - minY, 1e-6)
    Column {
        Text(
            if (selected in pairs.indices) "$xLabel: ${Formatters.decimal(pairs[selected].first)} · $yLabel: ${Formatters.decimal(pairs[selected].second)}"
            else "↑ $yLabel   → $xLabel",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .pointerInput(pairs) {
                    detectTapGestures { pos ->
                        val pad = 12.dp.toPx()
                        selected = pairs.indices.minByOrNull { i ->
                            val px = pad + ((pairs[i].first - minX) / spanX * (size.width - 2 * pad)).toFloat()
                            val py = size.height - pad - ((pairs[i].second - minY) / spanY * (size.height - 2 * pad)).toFloat()
                            (Offset(px, py) - pos).getDistance()
                        } ?: -1
                    }
                },
        ) {
            val pad = 12.dp.toPx()
            drawLine(axis, Offset(pad, size.height - pad), Offset(size.width - pad, size.height - pad), 1.dp.toPx())
            drawLine(axis, Offset(pad, pad), Offset(pad, size.height - pad), 1.dp.toPx())
            pairs.forEachIndexed { i, (x, y) ->
                val px = pad + ((x - minX) / spanX * (size.width - 2 * pad)).toFloat()
                val py = size.height - pad - ((y - minY) / spanY * (size.height - 2 * pad)).toFloat()
                drawCircle(color.copy(alpha = if (i == selected) 1f else 0.6f), radius = if (i == selected) 7.dp.toPx() else 4.5.dp.toPx(), center = Offset(px, py))
            }
        }
    }
}
