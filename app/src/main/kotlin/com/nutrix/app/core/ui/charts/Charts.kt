package com.nutrix.app.core.ui.charts

import android.text.SpannableStringBuilder
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nutrix.app.R
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.domain.ChartPoint
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.point
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.component.shapeComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.common.insets
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.ColumnCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.core.common.shader.ShaderProvider
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import com.patrykandpatrick.vico.core.cartesian.Zoom
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.floor

private fun dateLabel(x: Double): String = Formatters.dayMonthShort(LocalDate.ofEpochDay(x.toLong()))

@Composable
private fun rememberMarker(valueText: (Double) -> String): DefaultCartesianMarker {
    val label = rememberTextComponent(
        color = MaterialTheme.colorScheme.inverseOnSurface,
        padding = insets(8.dp, 4.dp),
        background = rememberShapeComponent(fill(MaterialTheme.colorScheme.inverseSurface), CorneredShape.rounded(allPercent = 25)),
    )
    val formatter = remember(valueText) {
        DefaultCartesianMarker.ValueFormatter { _, targets ->
            val target = targets.firstOrNull()
            val y = when (target) {
                is LineCartesianLayerMarkerTarget -> target.points.firstOrNull()?.entry?.y
                is ColumnCartesianLayerMarkerTarget -> target.columns.firstOrNull()?.entry?.y
                else -> null
            }
            SpannableStringBuilder().apply {
                if (target != null) append(dateLabel(target.x)).append(": ")
                if (y != null) append(valueText(y))
            }
        }
    }
    val guideline = rememberLineComponent(fill(MaterialTheme.colorScheme.outline), 1.dp)
    return rememberDefaultCartesianMarker(
        label = label,
        valueFormatter = formatter,
        indicator = { c -> shapeComponent(fill(c), CorneredShape.Pill) },
        guideline = guideline,
    )
}

/**
 * Interactive line chart over days (tap/drag to read values, pinch to zoom).
 * [minY]/[maxY] pin the visible range; otherwise it is derived from the data with padding.
 */
@Composable
fun DailyLineChart(
    points: List<ChartPoint>,
    color: Color,
    valueText: (Double) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    minY: Double? = null,
    maxY: Double? = null,
    zeroBased: Boolean = false,
) {
    if (points.isEmpty()) {
        NoChartData(modifier, height)
        return
    }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(points) {
        producer.runTransaction {
            lineSeries { series(points.map { it.epochDay }, points.map { it.value }) }
        }
    }
    val values = points.map { it.value }
    val range = (values.max() - values.min()).let { if (it < 1e-6) 1.0 else it }
    val lower = minY ?: if (zeroBased) 0.0 else floor(values.min() - range * 0.15)
    val upper = maxY ?: ceil(values.max() + range * 0.15)
    ProvideVicoTheme(rememberM3VicoTheme(lineColor = MaterialTheme.colorScheme.outlineVariant, textColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
        val line = LineCartesianLayer.rememberLine(
            fill = remember(color) { LineCartesianLayer.LineFill.single(fill(color)) },
            areaFill = remember(color) {
                LineCartesianLayer.AreaFill.single(
                    fill(ShaderProvider.verticalGradient(intArrayOf(color.copy(alpha = 0.18f).toArgbCompat(), Color.Transparent.toArgbCompat()))),
                )
            },
            pointProvider = LineCartesianLayer.PointProvider.single(
                LineCartesianLayer.point(rememberShapeComponent(fill(color), CorneredShape.Pill), 6.dp),
            ),
        )
        val yFormatter = remember(valueText) { CartesianValueFormatter { _, v, _ -> valueText(v) } }
        val xFormatter = remember { CartesianValueFormatter { _, v, _ -> dateLabel(v) } }
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(line),
                    rangeProvider = remember(lower, upper) { CartesianLayerRangeProvider.fixed(minY = lower, maxY = upper) },
                ),
                startAxis = VerticalAxis.rememberStart(valueFormatter = yFormatter, label = rememberAxisLabelComponent()),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = xFormatter,
                    itemPlacer = remember(points.size) { HorizontalAxis.ItemPlacer.aligned(spacing = { spacingFor(points) }) },
                ),
                marker = rememberMarker(valueText),
            ),
            modelProducer = producer,
            modifier = modifier
                .fillMaxWidth()
                .height(height),
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
        )
    }
}

/** Interactive daily column chart. */
@Composable
fun DailyColumnChart(
    points: List<ChartPoint>,
    color: Color,
    valueText: (Double) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
) {
    if (points.isEmpty() || points.all { it.value == 0.0 }) {
        NoChartData(modifier, height)
        return
    }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(points) {
        producer.runTransaction {
            columnSeries { series(points.map { it.epochDay }, points.map { it.value }) }
        }
    }
    ProvideVicoTheme(rememberM3VicoTheme(lineColor = MaterialTheme.colorScheme.outlineVariant, textColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
        val column = rememberLineComponent(fill(color), thickness = if (points.size > 45) 4.dp else if (points.size > 20) 7.dp else 12.dp, shape = CorneredShape.rounded(allPercent = 40))
        val yFormatter = remember(valueText) { CartesianValueFormatter { _, v, _ -> valueText(v) } }
        val xFormatter = remember { CartesianValueFormatter { _, v, _ -> dateLabel(v) } }
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberColumnCartesianLayer(
                    columnProvider = ColumnCartesianLayer.ColumnProvider.series(column),
                    rangeProvider = remember { CartesianLayerRangeProvider.fixed(minY = 0.0) },
                ),
                startAxis = VerticalAxis.rememberStart(valueFormatter = yFormatter),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = xFormatter,
                    itemPlacer = remember(points.size) { HorizontalAxis.ItemPlacer.aligned(spacing = { spacingFor(points) }) },
                ),
                marker = rememberMarker(valueText),
            ),
            modelProducer = producer,
            modifier = modifier
                .fillMaxWidth()
                .height(height),
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
        )
    }
}

private fun spacingFor(points: List<ChartPoint>): Int {
    val span = (points.last().epochDay - points.first().epochDay).toInt().coerceAtLeast(1)
    return when {
        span <= 8 -> 1
        span <= 16 -> 3
        span <= 35 -> 7
        span <= 100 -> 21
        else -> 45
    }
}

@Composable
fun NoChartData(modifier: Modifier = Modifier, height: Dp = 160.dp, text: String = stringResource(R.string.chart_no_data)) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .let { m -> m },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)
