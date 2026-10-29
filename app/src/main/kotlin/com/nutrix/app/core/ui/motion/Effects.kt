package com.nutrix.app.core.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.shape.CircleShape
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Short labels such as "+250 мл" that rise and fade after a quick action. */
@Stable
class FloatingDeltaState {
    internal data class Delta(val id: Long, val text: String)
    internal val items = mutableStateListOf<Delta>()
    private var next = 0L

    fun emit(text: String) {
        if (items.size >= 4) items.removeAt(0)
        items += Delta(next++, text)
    }
}

/** Draws [state]'s labels over the content; the labels are decorative and hidden from accessibility. */
@Composable
fun BoxScope.FloatingDeltas(state: FloatingDeltaState, color: Color, modifier: Modifier = Modifier) {
    val rise = with(LocalDensity.current) { 44.dp.toPx() }
    val reduced = rememberReducedMotion()
    state.items.forEach { delta ->
        androidx.compose.runtime.key(delta.id) {
            val progress = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                if (!reduced) progress.animateTo(1f, tween(900, easing = NutrixMotion.EmphasizedDecelerate))
                state.items.remove(delta)
            }
            Text(
                delta.text,
                style = MaterialTheme.typography.titleMedium,
                color = color,
                modifier = modifier
                    .align(Alignment.Center)
                    .clearAndSetSemantics { }
                    .graphicsLayer {
                        val p = progress.value
                        translationY = -rise * p
                        alpha = if (p < 0.15f) p / 0.15f else 1f - (p - 0.15f) / 0.85f
                        val s = 0.85f + 0.25f * minOf(1f, p * 4f)
                        scaleX = s
                        scaleY = s
                    },
            )
        }
    }
}

private class Particle(val angle: Float, val speed: Float, val size: Float, val spin: Float, val color: Color, val round: Boolean)

/**
 * One-shot confetti burst from the centre of the box. A new burst plays whenever [trigger]
 * increases; zero means "nothing yet". Skipped when animations are turned off.
 */
@Composable
fun CelebrationBurst(trigger: Int, colors: List<Color>, modifier: Modifier = Modifier, particleCount: Int = 26) {
    if (trigger <= 0 || rememberReducedMotion()) return
    val progress = remember(trigger) { Animatable(0f) }
    val particles = remember(trigger, colors) {
        val random = Random(trigger)
        List(particleCount) {
            Particle(
                angle = (random.nextFloat() * 2f * PI).toFloat(),
                speed = 0.45f + random.nextFloat() * 0.55f,
                size = 4f + random.nextFloat() * 5f,
                spin = random.nextFloat() * 720f - 360f,
                color = colors[it % colors.size],
                round = random.nextBoolean(),
            )
        }
    }
    LaunchedEffect(trigger) { progress.animateTo(1f, tween(1100, easing = LinearEasing)) }
    Canvas(modifier.fillMaxSize().clearAndSetSemantics { }) {
        val p = progress.value
        if (p >= 1f) return@Canvas
        val reach = minOf(size.width, size.height * 2f) * 0.55f
        val gravity = size.height * 0.9f
        val eased = NutrixMotion.EmphasizedDecelerate.transform(p)
        particles.forEach { particle ->
            val distance = reach * particle.speed * eased
            val x = center.x + cos(particle.angle) * distance
            val y = center.y + sin(particle.angle) * distance * 0.7f + gravity * p * p * 0.5f
            val alpha = (1f - p).coerceIn(0f, 1f)
            val d = particle.size.dp.toPx()
            rotate(particle.spin * p, Offset(x, y)) {
                if (particle.round) drawCircle(particle.color.copy(alpha = alpha), d / 2, Offset(x, y))
                else drawRoundRect(particle.color.copy(alpha = alpha), Offset(x - d / 2, y - d / 4), Size(d, d / 2), CornerRadius(d / 6))
            }
        }
    }
}

/**
 * Celebrates only the user's own action: [arm] is called for each addition, and a later change of
 * the observed value fires a burst if it crossed the goal. Loading or syncing data never celebrates.
 */
@Stable
class GoalCelebrationState {
    var bursts by mutableIntStateOf(0)
        private set
    private var pending = 0
    private var last: Int? = null

    fun arm() {
        pending++
    }

    /** Returns true when this change completed the goal. */
    fun onValue(value: Int, goal: Int?): Boolean {
        val before = last
        last = value
        if (before == null || value == before) return false
        val own = pending > 0
        if (own) pending--
        if (!own || goal == null || goal <= 0 || before >= goal || value < goal) return false
        bursts++
        return true
    }
}

@Composable
fun rememberGoalCelebration(value: Int, goal: Int?, onReached: () -> Unit = {}): GoalCelebrationState {
    val state = remember { GoalCelebrationState() }
    val callback by rememberUpdatedState(onReached)
    LaunchedEffect(value, goal) { if (state.onValue(value, goal)) callback() }
    return state
}

/**
 * A round "glass" filled to [fraction] with a gently moving water surface.
 * The level eases to new values; the wave stops when animations are turned off.
 */
@Composable
fun LiquidFillIndicator(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val level by animateFloatAsState(fraction.coerceIn(0f, 1f), NutrixMotion.gentle(), label = "liquidLevel")
    val reduced = rememberReducedMotion()
    val phase: State<Float> = if (reduced) remember { androidx.compose.runtime.mutableFloatStateOf(0f) } else {
        rememberInfiniteTransition(label = "wave").animateFloat(
            0f, (2 * PI).toFloat(),
            infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
            label = "wavePhase",
        )
    }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(track)
            if (level > 0f) {
                val amplitude = this.size.height * 0.045f * (if (level >= 1f) 0.4f else 1f)
                val surface = this.size.height * (1f - level)
                val p = phase.value
                fun wave(shift: Float) = Path().apply {
                    moveTo(0f, this@Canvas.size.height)
                    val steps = 24
                    for (i in 0..steps) {
                        val x = this@Canvas.size.width * i / steps
                        val y = surface + amplitude * sin(x / this@Canvas.size.width * 2f * PI.toFloat() + p + shift)
                        lineTo(x, y)
                    }
                    lineTo(this@Canvas.size.width, this@Canvas.size.height)
                    close()
                }
                drawPath(wave(PI.toFloat()), color.copy(alpha = 0.35f))
                drawPath(wave(0f), Brush.verticalGradient(listOf(color.copy(alpha = 0.85f), color), startY = surface, endY = this.size.height))
            }
            drawCircle(color.copy(alpha = 0.35f), style = Stroke(1.5.dp.toPx()))
        }
        content()
    }
}

/** Horizontal shake for rejected input (wrong PIN). A new shake starts whenever [trigger] changes. */
@Composable
fun Modifier.shakeOnChange(trigger: Int): Modifier {
    val offset = remember { Animatable(0f) }
    val distance = with(LocalDensity.current) { 10.dp.toPx() }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        offset.animateTo(0f, keyframes {
            durationMillis = 420
            -distance at 60
            distance at 130
            -distance * 0.7f at 200
            distance * 0.5f at 270
            -distance * 0.25f at 340
        })
    }
    return graphicsLayer { translationX = offset.value }
}

/** Slow vertical drift for illustrative icons (empty states). */
@Composable
fun Modifier.gentleFloat(distance: Dp = 4.dp): Modifier {
    if (rememberReducedMotion()) return this
    val px = with(LocalDensity.current) { distance.toPx() }
    val t by rememberInfiniteTransition(label = "float").animateFloat(
        -1f, 1f, infiniteRepeatable(tween(1800, easing = NutrixMotion.Emphasized), RepeatMode.Reverse), label = "floatOffset",
    )
    return graphicsLayer { translationY = t * px }
}

/** A light band that sweeps over the content, e.g. a photo being analysed. Static tint without motion. */
@Composable
fun Modifier.scanSweep(color: Color): Modifier {
    val reduced = rememberReducedMotion()
    val sweep: State<Float> = if (reduced) remember { androidx.compose.runtime.mutableFloatStateOf(0.5f) } else {
        rememberInfiniteTransition(label = "scan").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1400, easing = NutrixMotion.Emphasized), RepeatMode.Reverse), label = "scanPosition",
        )
    }
    return drawWithContent {
        drawContent()
        drawRect(color.copy(alpha = 0.12f))
        val y = size.height * sweep.value
        val band = size.height * 0.35f
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, color.copy(alpha = 0.45f), Color.Transparent), startY = y - band, endY = y + band))
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
    }
}
