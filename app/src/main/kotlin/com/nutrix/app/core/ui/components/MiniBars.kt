package com.nutrix.app.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.LaunchedEffect
import com.nutrix.app.core.ui.motion.NutrixMotion
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Small interactive bar chart: tap a bar to see its label and value.
 * [target] draws a dashed guideline when set.
 */
@Composable
fun MiniBars(
    values: List<Double>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
    target: Double? = null,
    valueText: (Double) -> String,
) {
    if (values.isEmpty()) return
    var selected by remember(values) { mutableStateOf(values.lastIndex) }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val guide = MaterialTheme.colorScheme.onSurfaceVariant
    // Bars grow from the baseline one after another; the selected bar eases to full colour.
    val grow = remember(values) { Animatable(0f) }
    LaunchedEffect(values) { grow.animateTo(1f, tween(900, easing = LinearEasing)) }
    val highlight = remember { Animatable(values.lastIndex.toFloat()) }
    LaunchedEffect(selected) { highlight.animateTo(selected.toFloat(), tween(NutrixMotion.MEDIUM, easing = NutrixMotion.Emphasized)) }
    Column(modifier) {
        Text(
            "${labels.getOrElse(selected) { "" }}: ${valueText(values[selected])}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(values) {
                    detectTapGestures { pos ->
                        val slot = size.width.toFloat() / values.size
                        selected = (pos.x / slot).toInt().coerceIn(0, values.lastIndex)
                    }
                },
        ) {
            val max = maxOf(values.max(), target ?: 0.0).takeIf { it > 0 } ?: 1.0
            val slot = size.width / values.size
            val barW = slot * 0.62f
            val stagger = 0.45f / values.size
            values.forEachIndexed { i, v ->
                val x = i * slot + (slot - barW) / 2
                drawRoundRect(track, Offset(x, 0f), Size(barW, size.height), CornerRadius(barW / 3))
                val local = ((grow.value - i * stagger) / 0.55f).coerceIn(0f, 1f)
                val h = (v / max * size.height).toFloat() * NutrixMotion.EmphasizedDecelerate.transform(local)
                if (h > 0f) {
                    val emphasis = (1f - kotlin.math.abs(highlight.value - i)).coerceIn(0f, 1f)
                    drawRoundRect(
                        color.copy(alpha = 0.55f + 0.45f * emphasis),
                        Offset(x, size.height - h),
                        Size(barW, h),
                        CornerRadius(barW / 3),
                    )
                }
            }
            if (target != null && target > 0) {
                val y = size.height - (target / max * size.height).toFloat()
                drawLine(guide, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            val step = if (labels.size > 10) 3 else 1
            labels.forEachIndexed { i, label ->
                Text(
                    if (i % step == 0 || i == labels.lastIndex) label else "",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}
