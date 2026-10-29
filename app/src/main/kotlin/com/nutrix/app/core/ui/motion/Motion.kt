package com.nutrix.app.core.ui.motion

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Shared durations and curves so screens move with one rhythm (Material 3 motion scale). */
object NutrixMotion {
    const val SHORT = 150
    const val MEDIUM = 300
    const val LONG = 450
    const val ENTRANCE = 420
    const val STAGGER = 45

    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> bouncy(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
    fun <T> gentle(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
}

/**
 * True when the user turned animations off ("Remove animations" in accessibility settings).
 * Decorative motion (waves, particles, floating icons) is skipped then; state changes stay instant.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/** Slight shrink while pressed gives cards and tiles a tactile response; released with a soft spring. */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource, pressedScale: Float = 0.97f): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        animationSpec = if (pressed) tween(NutrixMotion.SHORT, easing = NutrixMotion.Emphasized) else NutrixMotion.bouncy(),
        label = "pressScale",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Plays a cascade once when a screen is first shown: each item fades in and rises with a small delay.
 * Progress survives returning from a child screen, so lists do not replay when the user navigates back.
 */
@Stable
class EntranceState internal constructor(private val progress: Animatable<Float, *>?, private val totalMillis: Int) {
    /** Visible fraction of item [index] in 0..1, read in the draw phase only. */
    fun fraction(index: Int): Float {
        val p = progress ?: return 1f
        val start = index.coerceAtMost(MAX_STAGGERED) * NutrixMotion.STAGGER
        val local = ((p.value * totalMillis - start) / NutrixMotion.ENTRANCE).coerceIn(0f, 1f)
        return NutrixMotion.EmphasizedDecelerate.transform(local)
    }

    companion object {
        const val MAX_STAGGERED = 10
    }
}

/**
 * [ready] delays the cascade until data is loaded, so cards do not flash their empty state first.
 * A short timeout keeps content visible even if loading never reports completion.
 */
@Composable
fun rememberEntranceState(ready: Boolean = true): EntranceState {
    val reduced = rememberReducedMotion()
    var played by rememberSaveable { mutableStateOf(false) }
    val totalMillis = EntranceState.MAX_STAGGERED * NutrixMotion.STAGGER + NutrixMotion.ENTRANCE
    val progress = remember { if (played || reduced) null else Animatable(0f) }
    if (progress != null) {
        var start by remember { mutableStateOf(false) }
        LaunchedEffect(ready) {
            if (!ready) delay(400)
            start = true
        }
        LaunchedEffect(start) {
            if (start) {
                progress.animateTo(1f, tween(totalMillis, easing = LinearEasing))
                played = true
            }
        }
    }
    return remember(progress) { EntranceState(progress, totalMillis) }
}

/** Applies [EntranceState] to one item; cheap because values are read inside the layer block. */
@Composable
fun Modifier.entrance(state: EntranceState, index: Int): Modifier {
    val rise = with(LocalDensity.current) { 18.dp.toPx() }
    return graphicsLayer {
        val f = state.fraction(index)
        alpha = f
        translationY = (1f - f) * rise
    }
}

/** Integer that counts towards its new value instead of jumping (e.g. water after "+250"). */
@Composable
fun animatedInt(target: Int, durationMillis: Int = 650): Int {
    val value by animateIntAsState(target, tween(durationMillis, easing = NutrixMotion.Emphasized), label = "count")
    return value
}
