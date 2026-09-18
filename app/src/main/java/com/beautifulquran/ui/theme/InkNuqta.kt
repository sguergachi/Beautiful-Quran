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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The single-choice mark: a qalam-cut Arabic dot that one drop of ink soaks
 * into. The faint outline keeps the unselected choice legible without
 * becoming a Material radio ring.
 *
 * Selection is **one** drop, never stacked rings: the pen touches slightly off
 * centre, the drop's front runs out quickly and creeps to a stop, and it runs
 * further along some fibres than others, so its edge is ragged until it meets
 * the outline. Pigment arrives pale and deepens as it soaks in behind the
 * front, and the front itself is a feathered wet fringe, not a hard rim.
 * Letting go lifts the ink as a whole — it never runs the spread backwards.
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
    val fingers = remember(params.seed) { nuqtaFingers(params.seed) }
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
        val outlineStroke = Stroke(width = params.strokeDp.dp.toPx(), join = StrokeJoin.Round)
        val resting = restingInk.copy(alpha = params.restingOutlineAlpha)
        val t = spread.value
        val lift = presence.value

        if (lift <= 0f || t <= 0f) {
            drawPath(outline, resting, style = outlineStroke)
            return@Canvas
        }
        val origin = Offset(
            size.width * 0.5f + params.originDx.dp.toPx(),
            size.height * 0.5f + params.originDy.dp.toPx(),
        )
        val full = size.minDimension * 0.7f * params.reach
        val radii = nuqtaDropRadii(t, params, fingers)
        val edge = radii.max()
        val drop = nuqtaDropPath(origin, full, radii)
        val stops = nuqtaInkStops(t, params, edge)
        // One density field paints both the ink and the outline it stains, so
        // the outline is only ever as dark as the ink beside it.
        fun inkField(strength: Float) = Brush.radialGradient(
            *Array(NuqtaInkStopCount) { k ->
                stops[k * 2] to ink.copy(alpha = (lift * stops[k * 2 + 1] * strength).coerceIn(0f, 1f))
            },
            center = origin,
            radius = (full * edge).coerceAtLeast(0.5f),
        )
        clipPath(outline) {
            drawPath(path = drop, brush = inkField(1f))
        }
        // The outline takes the ink only where the drop has reached it; the
        // rest keeps its resting hairline.
        clipPath(drop, ClipOp.Difference) {
            drawPath(outline, resting, style = outlineStroke)
        }
        clipPath(drop) {
            drawPath(outline, resting.copy(alpha = resting.alpha * (1f - lift)), style = outlineStroke)
            drawPath(
                outline,
                inkField(params.selectedOutlineAlpha / params.inkAlpha.coerceAtLeast(0.05f)),
                style = outlineStroke,
            )
        }
    }
}

/** A CSS-style cubic-bezier easing, kept as plain numbers so the lab can tune it. */
@Immutable
data class NuqtaCurve(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    fun easing(): Easing = CubicBezierEasing(x1, y1, x2, y2)
}

/** Every knob of the nuqta, from its cut to its ink. */
@Immutable
data class NuqtaParams(
    // ---- the cut
    val sizeDp: Float = 23f,
    /** How far the four sides bow out from straight; 1 is the shipped cut. */
    val bow: Float = 1f,
    val strokeDp: Float = 1.15f,
    val restingOutlineAlpha: Float = 0.63f,
    val selectedOutlineAlpha: Float = 1f,
    // ---- the clock
    /** One drop, touch to settle. */
    val spreadMs: Int = 405,
    /**
     * How hard the front decelerates: `1 − (1 − t)^k`. Higher runs out faster
     * and creeps longer, like capillary spread through paper.
     */
    val spreadSharpness: Float = 1.6f,
    /** Lifting the ink off a choice that was let go. */
    val liftMs: Int = 220,
    /** The lift gathers pace as it goes, like pigment drying off the paper. */
    val lift: NuqtaCurve = NuqtaCurve(0.4f, 0f, 1f, 1f),
    // ---- the drop
    /** Where the pen lands, off centre — a real drop is never concentric. */
    val originDx: Float = -0.6f,
    val originDy: Float = 0.5f,
    /** Final radius as a share of 0.7 × the mark's size; the outline clips it. */
    val reach: Float = 0.76f,
    /**
     * How unevenly the drop runs along the fibres: fast directions lead and
     * slow ones lag, so the edge is ragged mid-spread yet still meets the
     * outline together.
     */
    val fingers: Float = 0.42f,
    /** Fine raggedness that stays in the edge while it moves. */
    val grain: Float = 0.06f,
    /** Which fibre pattern this paper has. */
    val seed: Int = 7,
    // ---- the ink
    /** How pale the pigment is at the running front. */
    val wetAlpha: Float = 0.28f,
    /** How dense it is once soaked in. */
    val inkAlpha: Float = 0.9f,
    /**
     * How far behind the front the dense core soaks outward, as a share of
     * the clock. The drop darkens from where it landed, never all at once.
     */
    val soakDelay: Float = 0.22f,
    /** Share of the drop's radius that is a feathered wet fringe. */
    val feather: Float = 0.3f,
    /** How much of the ink is left at the very tip of the fringe. */
    val fringeAlpha: Float = 0.2f,
)

/** The shipped nuqta. Bump [SHIPPED_NUQTA_REVISION] whenever it changes. */
val ShippedNuqtaParams = NuqtaParams()

/** Forces the developer nuqta lab to reseed when the shipped values change. */
const val SHIPPED_NUQTA_REVISION = 4

/** The nuqta every [InkNuqta] draws unless told otherwise. */
val LocalNuqtaParams = staticCompositionLocalOf { ShippedNuqtaParams }

/** Directions the drop's edge is sampled in. */
internal const val NuqtaDropSamples = 40

/** How far the front has run, 0..1, at clock [t]: `1 − (1 − t)^sharpness`. */
internal fun nuqtaFront(t: Float, sharpness: Float): Float =
    1f - (1f - t.coerceIn(0f, 1f)).pow(sharpness)

/** The wet fringe's share of the drop's radius at clock [t]; it dries as the drop settles. */
internal fun nuqtaFeather(t: Float, p: NuqtaParams): Float =
    (p.feather * (1f - 0.85f * nuqtaFront(t, p.spreadSharpness))).coerceIn(0f, 1f)

/** Gradient stops in [nuqtaInkStops]: landing point, core, fringe, tip. */
internal const val NuqtaInkStopCount = 4

/**
 * Ink density across the drop at clock [t], as [NuqtaInkStopCount]
 * (offset, alpha) pairs from where it landed out to its edge. A dense core
 * soaks outward [NuqtaParams.soakDelay] behind a pale running front, whose tip
 * is a feathered wet fringe; the front darkens too as the drop settles, so the
 * nuqta ends solid. [edge] is the drop's farthest reach, as a share of full.
 */
internal fun nuqtaInkStops(t: Float, p: NuqtaParams, edge: Float): FloatArray {
    fun smooth(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    val landed = (t / 0.05f).coerceIn(0f, 1f) // no pop on the first frame
    val lagT = ((t - p.soakDelay) / (1f - p.soakDelay).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
    val feather = nuqtaFeather(t, p)
    val core = (nuqtaFront(lagT, p.spreadSharpness) / edge.coerceAtLeast(1e-3f))
        .coerceIn(0f, 1f - feather)
    val front = p.wetAlpha + (p.inkAlpha - p.wetAlpha) * smooth(lagT)
    // Where it landed deepens first, reaching full strength just as the core
    // starts to soak outward — so density only ever falls off with distance.
    val centre = p.wetAlpha + (p.inkAlpha - p.wetAlpha) * smooth(t / p.soakDelay.coerceAtLeast(1e-3f))
    return floatArrayOf(
        0f, landed * centre,
        core, landed * centre,
        1f - feather, landed * front,
        1f, landed * front * p.fringeAlpha,
    )
}

/**
 * A fixed fibre pattern for [seed]: per direction, a coarse run-speed bias
 * in −1..1 (first half) and a fine grain in −1..1 (second half).
 */
internal fun nuqtaFingers(seed: Int): FloatArray {
    fun phase(k: Int): Float {
        var h = seed * 374761393 + k * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535f * 2f * PI.toFloat()
    }
    val out = FloatArray(NuqtaDropSamples * 2)
    for (layer in 0..1) {
        val harmonics = if (layer == 0) 2..5 else 9..13
        val values = FloatArray(NuqtaDropSamples) { i ->
            val a = i * 2f * PI.toFloat() / NuqtaDropSamples
            harmonics.sumOf { k -> (sin(k * a + phase(k + layer * 31)) / k).toDouble() }.toFloat()
        }
        val peak = values.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-6f)
        for (i in values.indices) out[layer * NuqtaDropSamples + i] = values[i] / peak
    }
    return out
}

/** The drop's edge at clock [t], per direction, as a share of its full radius. */
internal fun nuqtaDropRadii(t: Float, p: NuqtaParams, fingers: FloatArray): FloatArray =
    FloatArray(NuqtaDropSamples) { i ->
        // Fast fibres lead and slow ones lag by a bounded share of the drop,
        // and every direction arrives at the settle together.
        val f = nuqtaFront(t, p.spreadSharpness)
        val front = f * (1f + p.fingers.coerceIn(0f, 1f) * fingers[i] * (1f - f))
        // Grain lives in the moving edge and calms as the drop settles.
        val grain = 1f + p.grain * fingers[NuqtaDropSamples + i] * (1f - front)
        front * grain
    }

/** A smooth closed curve through the drop's edge samples. */
private fun nuqtaDropPath(origin: Offset, full: Float, radii: FloatArray): Path {
    val n = radii.size
    val xs = FloatArray(n)
    val ys = FloatArray(n)
    for (i in 0 until n) {
        val a = i * 2f * PI.toFloat() / n
        xs[i] = origin.x + cos(a) * radii[i] * full
        ys[i] = origin.y + sin(a) * radii[i] * full
    }
    return Path().apply {
        moveTo((xs[n - 1] + xs[0]) / 2f, (ys[n - 1] + ys[0]) / 2f)
        for (i in 0 until n) {
            val j = (i + 1) % n
            quadraticTo(xs[i], ys[i], (xs[i] + xs[j]) / 2f, (ys[i] + ys[j]) / 2f)
        }
        close()
    }
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

/** The nuqta's corners, as shares of its size. */
internal val NuqtaCorners = listOf(0.49f to 0.09f, 0.91f to 0.43f, 0.53f to 0.91f, 0.09f to 0.58f)
private val NuqtaBulges = listOf(0.69f to 0.16f, 0.86f to 0.66f, 0.31f to 0.84f, 0.14f to 0.33f)
