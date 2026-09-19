package com.beautifulquran.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Ink laid as [InkCoatCount] thin coats rather than one drop: a wide pale
 * under-coat, a body, and a small dense pool on top, each with its own fibre
 * pattern so their edges never coincide. Together they read as one drop; when
 * it dries the coats leave one after another — the pool first, the
 * under-coat last — so the reader watches the layers dry out of the paper.
 */
internal const val InkCoatCount = 3

/** How far each coat reaches, as a share of the drop's full reach: under-coat, body, pool. */
private val InkCoatReach = floatArrayOf(1f, 0.78f, 0.58f)

/** Each coat's opacity, chosen so the three stacked land near one full drop. */
private const val InkCoatAlpha = 0.54f

/** The share of a dry each coat takes to fade; they overlap by the rest. */
private const val InkCoatDrySpan = 0.46f

/** The nuqta params for coat [k]: its own fibres, reach and landing point. */
internal fun inkCoatParams(p: NuqtaParams, k: Int): NuqtaParams = p.copy(
    seed = p.seed + 11 * k,
    reach = p.reach * InkCoatReach[k],
    originDx = p.originDx + 0.5f * k,
    originDy = p.originDy - 0.4f * k,
)

/** A fibre pattern per coat, for [inkCoatParams]. */
internal fun inkCoatFingers(p: NuqtaParams): List<FloatArray> =
    List(InkCoatCount) { k -> nuqtaFingers(inkCoatParams(p, k).seed) }

/**
 * Coat [k]'s opacity at [dry] 0..1. The pool (the top coat) fades first and
 * the under-coat last, each easing out as the paper drinks it.
 */
internal fun inkCoatAlpha(k: Int, dry: Float): Float {
    val order = InkCoatCount - 1 - k
    val start = order * (1f - InkCoatDrySpan) / (InkCoatCount - 1)
    val x = ((dry - start) / InkCoatDrySpan).coerceIn(0f, 1f)
    return InkCoatAlpha * (1f - x * x * (3f - 2f * x))
}

/** Every coat of a nuqta that fills this draw scope; see [drawInkNuqta]. */
internal fun DrawScope.drawInkNuqtaCoats(
    t: Float,
    dry: Float,
    params: NuqtaParams,
    fingers: List<FloatArray>,
    ink: Color,
) {
    for (k in 0 until InkCoatCount) {
        val alpha = inkCoatAlpha(k, dry)
        if (alpha <= 0f) continue
        drawInkNuqta(
            t, 1f, inkCoatParams(params, k), fingers[k],
            ink.copy(alpha = ink.alpha * alpha), Color.Transparent,
        )
    }
}

/**
 * The under-coat reach at which even the pool covers everything within
 * [distance] of where a wash lands, so a settled wash is one even field.
 */
internal fun inkWashReachToCover(distance: Float): Float = distance / InkCoatReach.last() * 1.02f

/**
 * Every coat of an unbounded wash landing at [origin] whose under-coat
 * reaches [full] px — for filling a surface rather than a nuqta. Clip it to
 * the surface.
 */
internal fun DrawScope.drawInkWashCoats(
    t: Float,
    dry: Float,
    params: NuqtaParams,
    fingers: List<FloatArray>,
    ink: Color,
    origin: Offset,
    full: Float,
) {
    if (t <= 0f) return
    for (k in 0 until InkCoatCount) {
        val alpha = inkCoatAlpha(k, dry)
        if (alpha <= 0f) continue
        val coat = inkCoatParams(params, k)
        val reach = full * InkCoatReach[k]
        val radii = nuqtaDropRadii(t, coat, fingers[k])
        val edge = radii.max()
        val stops = nuqtaInkStops(t, coat, edge)
        drawPath(
            path = nuqtaDropPath(origin, reach, radii),
            brush = Brush.radialGradient(
                *Array(NuqtaInkStopCount) { i ->
                    stops[i * 2] to ink.copy(alpha = (ink.alpha * alpha * stops[i * 2 + 1]).coerceIn(0f, 1f))
                },
                center = origin,
                radius = (reach * edge).coerceAtLeast(0.5f),
            ),
        )
    }
}
