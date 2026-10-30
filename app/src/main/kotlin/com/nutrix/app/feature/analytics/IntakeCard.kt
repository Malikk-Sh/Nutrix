package com.nutrix.app.feature.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.nutrix.app.R
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.ui.components.CardHeader
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.Pill
import com.nutrix.app.core.ui.components.SegmentedChoices
import com.nutrix.app.core.ui.icon
import com.nutrix.app.core.ui.icons.Restaurant
import com.nutrix.app.core.ui.labelRes
import com.nutrix.app.core.ui.theme.LocalCategoryColors
import com.nutrix.app.domain.DayIntake
import com.nutrix.app.domain.DayIntakes
import com.nutrix.app.domain.GramRange
import com.nutrix.app.domain.IntakePlans
import com.nutrix.app.database.entity.IntakePlanEntity
import com.nutrix.app.domain.DayState
import com.nutrix.app.domain.Nutrient
import com.nutrix.app.domain.intake
import com.nutrix.app.domain.insights.RecordRef
import com.nutrix.app.domain.insights.RecordType
import com.nutrix.app.domain.part
import com.nutrix.app.feature.food.dayTitle
import com.nutrix.app.feature.intake.DayConfirmRow
import com.nutrix.app.feature.intake.dayCoverageText
import com.nutrix.app.feature.intake.dayStateText
import com.nutrix.app.feature.intake.dayTotalText
import com.nutrix.app.feature.intake.nutrientName
import com.nutrix.app.feature.intake.nutrientText
import androidx.compose.material.icons.Icons
import java.time.LocalDate
import kotlin.math.log10
import kotlin.math.pow

/**
 * Recorded calories and macronutrients per day. Missing days stay gaps, partly estimated or unconfirmed
 * days are drawn apart and say "known X", today is still running, and averages use complete days only.
 */
@Composable
fun IntakeCard(days: List<DayIntake>, plans: List<IntakePlanEntity>, onOpenRecord: (RecordRef) -> Unit, onConfirm: (Long, Boolean) -> Unit) {
    val colors = LocalCategoryColors.current
    var nutrient by rememberSaveable { mutableStateOf(Nutrient.KCAL) }
    var selected by rememberSaveable(days.firstOrNull()?.day, days.size) { mutableStateOf<Long?>(null) }
    val average = DayIntakes.average(days, nutrient)
    val finished = days.count { !it.isToday }
    NutrixCard(Modifier.testTag("intake-card")) {
        CardHeader(Icons.Rounded.Restaurant, stringResource(R.string.intake_card_title), colors.meal)
        Spacer(Modifier.height(8.dp))
        SegmentedChoices(Nutrient.entries, nutrient, { nutrientName(it) }, { nutrient = it })
        Spacer(Modifier.height(10.dp))
        if (average.value != null) {
            Text(nutrientText(nutrient, average.value), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("intake-average"))
            Text(pluralStringResource(R.plurals.intake_average_days, average.days, average.days, finished),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(stringResource(R.string.intake_no_complete), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("intake-average"))
            Text(stringResource(R.string.intake_no_complete_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The guideline of the last day of the period, and how complete days compared with the guideline each had.
        val current = days.lastOrNull()?.let { IntakePlans.activeOn(it.day, plans) }?.let { IntakePlans.range(it, nutrient) }
        current?.let { r ->
            Text(stringResource(R.string.intake_plan_line, rangeText(nutrient, r)), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("intake-plan"))
        }
        IntakePlans.compare(days, plans, nutrient)?.let { c ->
            Text(stringResource(R.string.intake_plan_compare, c.within, c.below, c.above), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("intake-plan-compare"))
        }
        Spacer(Modifier.height(10.dp))
        val description = stringResource(R.string.intake_chart_description, nutrientName(nutrient), average.days)
        val ranges = days.map { d -> IntakePlans.activeOn(d.day, plans)?.let { IntakePlans.range(it, nutrient) } }
        IntakeBars(days, ranges, nutrient, colors.meal, selected, { day -> selected = if (selected == day) null else day },
            Modifier.semantics { contentDescription = description })
        Spacer(Modifier.height(6.dp))
        IntakeLegend(colors.meal, showPlan = ranges.any { it != null })
        Spacer(Modifier.height(6.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        val day = days.firstOrNull { it.day == selected }
        if (day == null) {
            Text(stringResource(R.string.intake_select_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            DayBreakdown(day, nutrient, onOpenRecord, { onConfirm(day.day, it) })
        }
    }
}

/** What one day is made of: every meal and caloric drink with its own value, and the switch that confirms the day. */
@Composable
private fun DayBreakdown(day: DayIntake, n: Nutrient, onOpenRecord: (RecordRef) -> Unit, onConfirm: (Boolean) -> Unit) {
    val colors = LocalCategoryColors.current
    val missing = stringResource(R.string.intake_value_missing)
    Column(Modifier.testTag("intake-day"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(dayTitle(LocalDate.ofEpochDay(day.day)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Pill(dayStateText(day.state(n)), if (day.usable(n)) colors.meal else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (day.records == 0) {
            Text(stringResource(R.string.intake_day_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        dayTotalText(day, n)?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        dayCoverageText(day, n)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (n == Nutrient.KCAL) day.kcalFromMacros?.let {
            Text(stringResource(R.string.intake_from_macros, Formatters.integer(Math.round(it))), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        day.meals.forEach { m ->
            val part = m.part(n)
            val value = when {
                part.complete && part.value != null -> nutrientText(n, part.value)
                part.value != null -> stringResource(R.string.intake_known, nutrientText(n, part.value))
                else -> missing
            }
            RecordLine(stringResource(m.mealType.labelRes), m.title ?: m.items.firstOrNull(), Formatters.time(m.time), value, !part.complete,
                Icons.Rounded.Restaurant, colors.meal) { onOpenRecord(RecordRef(RecordType.MEAL, m.id)) }
        }
        day.drinks.forEach { d ->
            val value = d.intake.of(n)
            RecordLine(stringResource(d.drinkType.labelRes), "${d.name ?: stringResource(d.drinkType.labelRes)} · ${d.volumeMl} ${stringResource(R.string.unit_ml)}", Formatters.time(d.time),
                value?.let { nutrientText(n, it) } ?: missing, value == null, d.drinkType.icon, colors.water) { onOpenRecord(RecordRef(RecordType.DRINK, d.id)) }
        }
        Spacer(Modifier.height(4.dp))
        DayConfirmRow(day.confirmed, onConfirm, tag = "intake-confirm")
    }
}

@Composable
private fun RecordLine(kind: String, title: String?, time: String, value: String, missing: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("$time · ${title?.takeIf(String::isNotBlank) ?: kind}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
        Text(value, style = MaterialTheme.typography.bodyMedium,
            color = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun rangeText(n: Nutrient, r: GramRange): String {
    val unit = stringResource(if (n == Nutrient.KCAL) R.string.unit_kcal else R.string.unit_g)
    return "${Formatters.integer(r.min.toLong())}–${Formatters.integer(r.max.toLong())} $unit"
}

@Composable
private fun IntakeLegend(color: Color, showPlan: Boolean) {
    val outline = color
    @Composable fun item(text: String, draw: DrawScope.() -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(12.dp)) { draw() }
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(stringResource(R.string.intake_legend_complete)) { bar(DayState.COMPLETE, color, outline, Offset.Zero, size) }
        item(stringResource(R.string.intake_legend_unconfirmed)) { bar(DayState.UNCONFIRMED, color, outline, Offset.Zero, size) }
        item(stringResource(R.string.intake_legend_partial)) { bar(DayState.PARTIAL, color, outline, Offset.Zero, size) }
        item(stringResource(R.string.intake_legend_today)) { bar(DayState.TODAY, color, outline, Offset.Zero, size) }
        if (showPlan) item(stringResource(R.string.intake_legend_plan)) { band(color, Offset.Zero, size) }
    }
}

/** One bar in its day's style: complete days solid, the rest visibly lighter or outlined. */
private fun DrawScope.bar(state: DayState, color: Color, outline: Color, topLeft: Offset, size: Size) {
    val radius = CornerRadius(minOf(size.width, 8.dp.toPx()) / 3)
    val stroke = 1.5.dp.toPx()
    val inset = Offset(stroke / 2, stroke / 2)
    val inner = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f))
    when (state) {
        DayState.COMPLETE -> drawRoundRect(color, topLeft, size, radius)
        DayState.UNCONFIRMED -> drawRoundRect(color.copy(alpha = 0.45f), topLeft, size, radius)
        DayState.PARTIAL -> {
            drawRoundRect(color.copy(alpha = 0.15f), topLeft, size, radius)
            drawRoundRect(outline, topLeft + inset, inner, radius, style = Stroke(stroke))
        }
        DayState.TODAY -> {
            drawRoundRect(color.copy(alpha = 0.15f), topLeft, size, radius)
            drawRoundRect(outline, topLeft + inset, inner, radius,
                style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
        }
        DayState.NO_RECORDS -> Unit
    }
}

/** The guideline range of a day, drawn behind its bar. */
private fun DrawScope.band(color: Color, topLeft: Offset, size: Size) {
    drawRect(color.copy(alpha = 0.14f), topLeft, size)
    drawLine(color.copy(alpha = 0.55f), topLeft, Offset(topLeft.x + size.width, topLeft.y), 1.dp.toPx())
    drawLine(color.copy(alpha = 0.55f), Offset(topLeft.x, topLeft.y + size.height), Offset(topLeft.x + size.width, topLeft.y + size.height), 1.dp.toPx())
}

/** A round upper bound for the axis: 1 850 becomes 2 000, 37 becomes 40. */
private fun niceMax(max: Double): Double {
    if (max <= 0) return 1.0
    val magnitude = 10.0.pow(kotlin.math.floor(log10(max)))
    val steps = listOf(1.0, 2.0, 2.5, 5.0, 10.0)
    return steps.first { it * magnitude >= max } * magnitude
}

@Composable
private fun IntakeBars(days: List<DayIntake>, ranges: List<GramRange?>, n: Nutrient, color: Color, selected: Long?, onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val highlight = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val max = niceMax(maxOf(days.maxOfOrNull { it.total(n).value ?: 0.0 } ?: 0.0, ranges.maxOfOrNull { it?.max?.toDouble() ?: 0.0 } ?: 0.0))
    val unit = stringResource(if (n == Nutrient.KCAL) R.string.unit_kcal else R.string.unit_g)
    val maxLabel = "${Formatters.integer(Math.round(max))} $unit"
    val half = Formatters.integer(Math.round(max / 2))
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .testTag("intake-bars")
                .pointerInput(days, n) {
                    detectTapGestures { pos ->
                        if (days.isEmpty()) return@detectTapGestures
                        val left = 44.dp.toPx()
                        val slot = (size.width - left) / days.size
                        val index = ((pos.x - left) / slot).toInt().coerceIn(0, days.lastIndex)
                        onSelect(days[index].day)
                    }
                },
        ) {
            if (days.isEmpty()) return@Canvas
            val left = 44.dp.toPx()
            val top = 14.dp.toPx()
            val chartHeight = size.height - top
            val slot = (size.width - left) / days.size
            val barWidth = (slot * 0.62f).coerceAtLeast(2.dp.toPx()).coerceAtMost(18.dp.toPx())
            listOf(0.0, 0.5, 1.0).forEach { f ->
                val y = top + chartHeight * (1 - f).toFloat()
                drawLine(grid, Offset(left, y), Offset(size.width, y), 1.dp.toPx())
            }
            drawText(measurer, maxLabel, Offset(0f, 0f), labelStyle)
            drawText(measurer, half, Offset(0f, top + chartHeight / 2 - 8.dp.toPx()), labelStyle)
            days.forEachIndexed { i, d ->
                val x = left + i * slot
                if (d.day == selected) drawRect(highlight, Offset(x, top), Size(slot, chartHeight))
                ranges[i]?.let { r ->
                    val yMax = size.height - (r.max / max * chartHeight).toFloat()
                    val yMin = size.height - (r.min / max * chartHeight).toFloat()
                    band(color, Offset(x, yMax), Size(slot, yMin - yMax))
                }
                val state = d.state(n)
                val cx = x + slot / 2
                if (state == DayState.NO_RECORDS) {
                    // A gap, not a zero: nothing is drawn above the axis.
                    drawCircle(grid, 2.dp.toPx(), Offset(cx, size.height - 3.dp.toPx()))
                    return@forEachIndexed
                }
                val value = d.total(n).value ?: 0.0
                val h = (value / max * chartHeight).toFloat().coerceAtLeast(3.dp.toPx())
                bar(state, color, color, Offset(cx - barWidth / 2, size.height - h), Size(barWidth, h))
            }
        }
        if (days.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(start = 44.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val labels = listOf(days.first(), days[days.size / 2], days.last()).distinct()
            labels.forEach { Text(Formatters.dayMonthShort(LocalDate.ofEpochDay(it.day)), style = MaterialTheme.typography.labelSmall, color = labelColor) }
        }
    }
}
