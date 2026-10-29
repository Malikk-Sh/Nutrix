package com.nutrix.app.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.drawscope.clipRect
import com.nutrix.app.core.ui.motion.NutrixMotion
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Minimal line chart for cards. Values are plotted evenly spaced; nulls break nothing. */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier, strokeWidth: Dp = 2.5.dp) {
    if (values.size < 2) return
    // The line draws itself from left to right when shown or when its data changes.
    val reveal = remember(values) { Animatable(0f) }
    LaunchedEffect(values) { reveal.animateTo(1f, tween(900, easing = NutrixMotion.EmphasizedDecelerate)) }
    Canvas(modifier) {
        val min = values.min()
        val max = values.max()
        val range = (max - min).takeIf { it > 1e-9 } ?: 1.0
        val stepX = size.width / (values.size - 1)
        val pad = strokeWidth.toPx()
        fun y(v: Double) = (size.height - pad) - ((v - min) / range * (size.height - 2 * pad)).toFloat()
        val line = Path()
        values.forEachIndexed { i, v -> if (i == 0) line.moveTo(0f, y(v)) else line.lineTo(i * stepX, y(v)) }
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        val progress = reveal.value
        clipRect(right = size.width * progress + pad * 2) {
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent)))
            drawPath(line, color, style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round))
        }
        val dot = ((progress - 0.85f) / 0.15f).coerceIn(0f, 1f)
        if (dot > 0f) {
            val end = Offset(size.width, y(values.last()))
            drawCircle(color.copy(alpha = 0.22f * dot), radius = strokeWidth.toPx() * 3.2f * dot, center = end)
            drawCircle(color, radius = strokeWidth.toPx() * 1.4f * dot, center = end)
        }
    }
}

/** Circular progress ring (fraction may exceed 1 — the ring is then full). */
@Composable
fun ProgressRing(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    stroke: Dp = 8.dp,
    content: @Composable () -> Unit = {},
) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), animationSpec = tween(900, easing = NutrixMotion.Emphasized), label = "ring")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = stroke.toPx()
            val arcSize = Size(this.size.width - s, this.size.height - s)
            val topLeft = Offset(s / 2, s / 2)
            drawArc(track, -90f, 360f, false, topLeft, arcSize, style = Stroke(s, cap = StrokeCap.Round))
            if (animated > 0f) {
                drawArc(color, -90f, 360f * animated, false, topLeft, arcSize, style = Stroke(s, cap = StrokeCap.Round))
            }
        }
        content()
    }
}

/** Thin horizontal progress bar. */
@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), animationSpec = tween(800, easing = NutrixMotion.Emphasized), label = "bar")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier) {
        val h = height.toPx()
        val y = size.height / 2
        drawLine(track, Offset(h / 2, y), Offset(size.width - h / 2, y), strokeWidth = h, cap = StrokeCap.Round)
        if (animated > 0f) {
            val end = h / 2 + (size.width - h) * animated
            drawLine(Brush.horizontalGradient(listOf(color.copy(alpha = 0.75f), color), startX = 0f, endX = end), Offset(h / 2, y), Offset(end, y), strokeWidth = h, cap = StrokeCap.Round)
        }
    }
}
