package com.beautifulquran.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * The single-choice mark: a qalam-cut Arabic dot whose selected ink blooms
 * until it meets the nuqta. The faint outline keeps the unselected choice
 * legible without becoming a Material radio ring.
 *
 * Selection is one continuous ink action on a single clock ([NuqtaSpreadMs]):
 * the pen touches, a translucent wet edge races out, then denser pigment
 * follows it by capillary reach into the corners and settles softly. Letting
 * go lifts the ink as a whole — it never runs the spread backwards.
 */
@Composable
fun InkNuqta(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val spread = remember { Animatable(if (selected) 1f else 0f) }
    val presence = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected) {
        if (selected) {
            // Re-chosen mid-lift: start a fresh drop rather than reviving
            // the half-dried one.
            if (presence.value < 1f) spread.snapTo(0f)
            presence.snapTo(1f)
            spread.animateTo(1f, tween(NuqtaSpreadMs, easing = LinearEasing))
        } else {
            presence.animateTo(0f, tween(NuqtaLiftMs, easing = NuqtaLiftEasing))
        }
    }
    val ink = MaterialTheme.colorScheme.primary
    val restingInk = MaterialTheme.colorScheme.outline

    Canvas(modifier.size(20.dp)) {
        val outline = inkNuqtaPath(size.minDimension)
        val centre = Offset(size.width * 0.5f, size.height * 0.5f)
        val full = size.minDimension * 0.7f
        val t = spread.value
        val alpha = presence.value

        if (alpha > 0f && t > 0f) {
            clipPath(outline) {
                // A translucent wet edge arrives before the pooled pigment,
                // then dries back a little as the pigment settles under it.
                val wet = nuqtaReach(t, NuqtaLayer.WetEdge)
                drawCircle(
                    color = ink.copy(alpha = alpha * (0.24f - 0.08f * t) * wet),
                    radius = full * 1.12f * wet,
                    center = centre,
                )
                drawCircle(
                    color = ink.copy(alpha = alpha * 0.55f),
                    radius = full * 0.98f * nuqtaReach(t, NuqtaLayer.Body),
                    center = centre + Offset(1.1.dp.toPx(), -0.7.dp.toPx()),
                )
                drawCircle(
                    color = ink.copy(alpha = alpha * 0.96f),
                    radius = full * 0.84f * nuqtaReach(t, NuqtaLayer.Pool),
                    center = centre + Offset(-0.6.dp.toPx(), 0.8.dp.toPx()),
                )
            }
        }

        drawPath(
            path = outline,
            color = lerp(restingInk.copy(alpha = 0.46f), ink.copy(alpha = 0.82f), alpha),
            style = Stroke(width = 1.15.dp.toPx(), join = StrokeJoin.Round),
        )
    }
}

/** One full ink action, touch to settle. */
internal const val NuqtaSpreadMs = 640

/** Lifting the ink off a choice that was let go. */
internal const val NuqtaLiftMs = 220

/** The lift gathers pace as it goes, like pigment drying off the paper. */
private val NuqtaLiftEasing: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

/** The three pigment layers of one nuqta, from the wet edge in. */
internal enum class NuqtaLayer(
    /** Share of the shared clock at which this layer starts to move. */
    val start: Float,
    /** Share of the shared clock at which this layer has fully arrived. */
    val end: Float,
    val easing: Easing,
) {
    /** Quick wetting: the edge leaps out on touch and barely decelerates. */
    WetEdge(0f, 0.55f, CubicBezierEasing(0.1f, 0.75f, 0.3f, 1f)),

    /** The body wicks outward more slowly, still reaching past its start. */
    Body(0.08f, 0.82f, CubicBezierEasing(0.25f, 0.6f, 0.3f, 1f)),

    /** The dense pool creeps into the corners and settles with no bounce. */
    Pool(0.18f, 1f, CubicBezierEasing(0.3f, 0.35f, 0.15f, 1f)),
}

/** How far [layer] has spread, 0..1, at shared clock [t]. */
internal fun nuqtaReach(t: Float, layer: NuqtaLayer): Float {
    val local = ((t - layer.start) / (layer.end - layer.start)).coerceIn(0f, 1f)
    return layer.easing.transform(local)
}

/** The lightly bowed rhombus left by one full-width touch of an Arabic qalam. */
internal fun inkNuqtaPath(size: Float): Path = Path().apply {
    moveTo(size * 0.49f, size * 0.09f)
    quadraticTo(size * 0.69f, size * 0.16f, size * 0.91f, size * 0.43f)
    quadraticTo(size * 0.86f, size * 0.66f, size * 0.53f, size * 0.91f)
    quadraticTo(size * 0.31f, size * 0.84f, size * 0.09f, size * 0.58f)
    quadraticTo(size * 0.14f, size * 0.33f, size * 0.49f, size * 0.09f)
    close()
}
