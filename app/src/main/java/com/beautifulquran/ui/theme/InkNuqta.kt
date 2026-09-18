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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
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
 * Selection is one continuous ink action on a single clock
 * ([NuqtaParams.spreadMs]): the pen touches, a translucent wet edge races out,
 * then denser pigment follows it by capillary reach into the corners and
 * settles softly. Letting go lifts the ink as a whole — it never runs the
 * spread backwards.
 *
 * Every knob lives in [NuqtaParams]. Callers take the shipped values through
 * [LocalNuqtaParams], which only Settings → Developer's nuqta lab overrides.
 */
@Composable
fun InkNuqta(
    selected: Boolean,
    modifier: Modifier = Modifier,
    params: NuqtaParams = LocalNuqtaParams.current,
) {
    val spread = remember { Animatable(if (selected) 1f else 0f) }
    val presence = remember { Animatable(if (selected) 1f else 0f) }
    val liftEasing = remember(params.lift) { params.lift.easing() }
    val layers = remember(params.wet, params.body, params.pool) {
        listOf(params.wet, params.body, params.pool).map { it to it.curve.easing() }
    }
    LaunchedEffect(selected) {
        if (selected) {
            // Re-chosen mid-lift: start a fresh drop rather than reviving
            // the half-dried one.
            if (presence.value < 1f) spread.snapTo(0f)
            presence.snapTo(1f)
            spread.animateTo(1f, tween(params.spreadMs, easing = LinearEasing))
        } else {
            presence.animateTo(0f, tween(params.liftMs, easing = liftEasing))
        }
    }
    val ink = MaterialTheme.colorScheme.primary
    val restingInk = MaterialTheme.colorScheme.outline

    Canvas(modifier.size(params.sizeDp.dp)) {
        val outline = inkNuqtaPath(size.minDimension, params.bow)
        val centre = Offset(size.width * 0.5f, size.height * 0.5f)
        val full = size.minDimension * 0.7f
        val t = spread.value
        val alpha = presence.value

        if (alpha > 0f && t > 0f) {
            clipPath(outline) {
                layers.forEachIndexed { index, (layer, easing) ->
                    val reach = nuqtaReach(t, layer, easing)
                    if (reach <= 0f) return@forEachIndexed
                    // The wet edge (first) fades in with its reach, then dries
                    // back by [NuqtaParams.wetDryBack] as the pool settles.
                    val layerAlpha = if (index == 0) {
                        layer.alpha * (1f - params.wetDryBack * t) * reach
                    } else {
                        layer.alpha
                    }
                    drawCircle(
                        color = ink.copy(alpha = alpha * layerAlpha),
                        radius = full * layer.radius * reach,
                        center = centre + Offset(layer.dxDp.dp.toPx(), layer.dyDp.dp.toPx()),
                    )
                }
            }
        }

        drawPath(
            path = outline,
            color = lerp(
                restingInk.copy(alpha = params.restingOutlineAlpha),
                ink.copy(alpha = params.selectedOutlineAlpha),
                alpha,
            ),
            style = Stroke(width = params.strokeDp.dp.toPx(), join = StrokeJoin.Round),
        )
    }
}

/** A CSS-style cubic-bezier easing, kept as plain numbers so the lab can tune it. */
@Immutable
data class NuqtaCurve(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    fun easing(): Easing = CubicBezierEasing(x1, y1, x2, y2)
}

/** One pigment layer of the nuqta's spread. */
@Immutable
data class NuqtaLayerParams(
    /** Share of the shared clock at which this layer starts to move. */
    val start: Float,
    /** Share of the shared clock at which this layer has fully arrived. */
    val end: Float,
    val curve: NuqtaCurve,
    /** Final radius as a share of 0.7 × the mark's size; the outline clips it. */
    val radius: Float,
    val alpha: Float,
    /** Where the layer pools, off centre — a real drop is never concentric. */
    val dxDp: Float = 0f,
    val dyDp: Float = 0f,
)

/** Every knob of the nuqta, from its cut to its ink. */
@Immutable
data class NuqtaParams(
    val sizeDp: Float = 20f,
    /** How far the four sides bow out from straight; 1 is the shipped cut. */
    val bow: Float = 1f,
    val strokeDp: Float = 1.15f,
    val restingOutlineAlpha: Float = 0.46f,
    val selectedOutlineAlpha: Float = 0.82f,
    /** One full ink action, touch to settle. */
    val spreadMs: Int = 640,
    /** Lifting the ink off a choice that was let go. */
    val liftMs: Int = 220,
    /** The lift gathers pace as it goes, like pigment drying off the paper. */
    val lift: NuqtaCurve = NuqtaCurve(0.4f, 0f, 1f, 1f),
    /** Share of the wet edge's alpha that has dried away by the settle. */
    val wetDryBack: Float = 0.33f,
    /** Quick wetting: the edge leaps out on touch and barely decelerates. */
    val wet: NuqtaLayerParams = NuqtaLayerParams(
        start = 0f, end = 0.55f, curve = NuqtaCurve(0.1f, 0.75f, 0.3f, 1f),
        radius = 1.12f, alpha = 0.24f,
    ),
    /** The body wicks outward more slowly, still reaching past its start. */
    val body: NuqtaLayerParams = NuqtaLayerParams(
        start = 0.08f, end = 0.82f, curve = NuqtaCurve(0.25f, 0.6f, 0.3f, 1f),
        radius = 0.98f, alpha = 0.55f, dxDp = 1.1f, dyDp = -0.7f,
    ),
    /** The dense pool creeps into the corners and settles with no bounce. */
    val pool: NuqtaLayerParams = NuqtaLayerParams(
        start = 0.18f, end = 1f, curve = NuqtaCurve(0.3f, 0.35f, 0.15f, 1f),
        radius = 0.84f, alpha = 0.96f, dxDp = -0.6f, dyDp = 0.8f,
    ),
)

/** The shipped nuqta. Bump [SHIPPED_NUQTA_REVISION] whenever it changes. */
val ShippedNuqtaParams = NuqtaParams()

/** Forces the developer nuqta lab to reseed when the shipped values change. */
const val SHIPPED_NUQTA_REVISION = 1

/** The nuqta every [InkNuqta] draws unless told otherwise. */
val LocalNuqtaParams = staticCompositionLocalOf { ShippedNuqtaParams }

/** How far [layer] has spread, 0..1, at shared clock [t]. */
internal fun nuqtaReach(
    t: Float,
    layer: NuqtaLayerParams,
    easing: Easing = layer.curve.easing(),
): Float {
    val span = (layer.end - layer.start).coerceAtLeast(1e-3f)
    val local = ((t - layer.start) / span).coerceIn(0f, 1f)
    return easing.transform(local)
}

/**
 * The lightly bowed rhombus left by one full-width touch of an Arabic qalam.
 * [bow] scales each side's bulge away from the straight chord between corners.
 */
internal fun inkNuqtaPath(size: Float, bow: Float = 1f): Path = Path().apply {
    moveTo(size * NuqtaCorners[0].first, size * NuqtaCorners[0].second)
    for (i in NuqtaCorners.indices) {
        val a = NuqtaCorners[i]
        val b = NuqtaCorners[(i + 1) % NuqtaCorners.size]
        val midX = (a.first + b.first) / 2f
        val midY = (a.second + b.second) / 2f
        val cx = midX + (NuqtaBulges[i].first - midX) * bow
        val cy = midY + (NuqtaBulges[i].second - midY) * bow
        quadraticTo(size * cx, size * cy, size * b.first, size * b.second)
    }
    close()
}

private val NuqtaCorners = listOf(0.49f to 0.09f, 0.91f to 0.43f, 0.53f to 0.91f, 0.09f to 0.58f)
private val NuqtaBulges = listOf(0.69f to 0.16f, 0.86f to 0.66f, 0.31f to 0.84f, 0.14f to 0.33f)
