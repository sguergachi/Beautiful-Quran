package com.beautifulquran.ui.theme

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.beautifulquran.ui.entrance.CoverFrameGeometry
import com.beautifulquran.ui.theme.ornament.BorderSpec
import com.beautifulquran.ui.theme.ornament.FieldSpec
import com.beautifulquran.ui.theme.ornament.RosetteSpec
import com.beautifulquran.ui.theme.ornament.StrokeWeight
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Renderers for the generated cover ornament (ui/theme/ornament/
 * OrnamentGenerator.kt). Same material language as Ornament.kt — embossed
 * relief first, shifting gold leaf on the face — but the geometry arrives
 * from the generator instead of being hard-coded, and every stroke inks
 * itself in as the build progress advances, so the cover is drawn before
 * the reader's eyes rather than appearing stamped.
 *
 * All geometry is cached per size (drawWithCache); build and sheen values
 * are read only inside the draw phase, so the ceremony animates without
 * recomposing anything.
 */

/** A generated stroke ready to draw: full path, its measure for partial
 *  inking, and a scratch path reused each frame while the stroke grows. */
private class StrokeRender(
    val full: Path,
    val width: Float,
    val birth: Float,
    val span: Float,
) {
    val measure = PathMeasure().also { it.setPath(full, false) }
    val length = measure.length
    val partial = Path()
}

private class DotRender(val center: Offset, val radius: Float, val birth: Float)

/** Rosette geometry scaled to a pixel diameter, centred on (0, 0). */
private class RosettePaths(spec: RosetteSpec, val diameter: Float, ruleWidth: Float, hairWidth: Float) {
    val strokes: List<StrokeRender> = spec.strokes.map { s ->
        val path = Path()
        s.points.forEachIndexed { i, p ->
            val x = ((p.x - 0.5) * diameter).toFloat()
            val y = ((p.y - 0.5) * diameter).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (s.closed) path.close()
        val width = if (s.weight == StrokeWeight.Rule) ruleWidth else hairWidth
        StrokeRender(path, width, s.birth.toFloat(), s.span.toFloat())
    }
    val dots: List<DotRender> = spec.dots.map {
        DotRender(
            Offset(((it.x - 0.5) * diameter).toFloat(), ((it.y - 0.5) * diameter).toFloat()),
            (it.radius * diameter).toFloat(),
            it.birth.toFloat(),
        )
    }
}

/** Draws [paths] at [center]; strokes grow along their length with the
 *  build [progress], each inside its own [birth, birth+span] window. */
private fun DrawScope.drawRosette(
    paths: RosettePaths,
    center: Offset,
    gold: androidx.compose.ui.graphics.Brush,
    embossDark: Color,
    embossLight: Color,
    progress: Float,
) {
    translate(center.x, center.y) {
        for (s in paths.strokes) {
            val f = ((progress - s.birth) / s.span).coerceIn(0f, 1f)
            if (f <= 0f) continue
            val path = if (f >= 1f) {
                s.full
            } else {
                s.partial.reset()
                s.measure.getSegment(0f, s.length * f, s.partial, true)
                s.partial
            }
            val stroke = Stroke(width = s.width, cap = StrokeCap.Round, join = StrokeJoin.Round)
            translate(0.8f, 0.8f) { drawPath(path, embossDark, style = stroke) }
            translate(-0.8f, -0.8f) { drawPath(path, embossLight, style = stroke) }
            drawPath(path, gold, style = stroke)
        }
        for (d in paths.dots) {
            val f = ((progress - d.birth) / 0.06f).coerceIn(0f, 1f)
            if (f <= 0f) continue
            drawCircle(gold, radius = d.radius * f, center = d.center)
        }
    }
}

/**
 * A rosette at the ceremonial centre of the cover. The cover passes the
 * fixed crescent-and-Allah mark; [build] 0..1 inks it stroke by stroke
 * and [sheen] tilts the leaf, both read only at draw time.
 */
@Composable
fun GeneratedMedallion(
    spec: RosetteSpec,
    size: Dp,
    brightGold: Color,
    deepGold: Color,
    embossDark: Color,
    embossLight: Color,
    sheen: State<Float>,
    build: State<Float>,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier
            .size(size)
            .drawWithCache {
                val d = min(this.size.width, this.size.height)
                val paths = RosettePaths(
                    spec,
                    d,
                    ruleWidth = (d * 0.011f).coerceAtLeast(1f),
                    hairWidth = (d * 0.005f).coerceAtLeast(1f),
                )
                onDrawBehind {
                    val gold = goldBrush(brightGold, deepGold, sheen.value.coerceIn(0f, 1f))
                    drawRosette(
                        paths,
                        Offset(this.size.width / 2f, this.size.height / 2f),
                        gold,
                        embossDark,
                        embossLight,
                        build.value,
                    )
                }
            },
    )
}

/**
 * A chapter's own rosette — the surah-header ornament, grown from
 * [chapterOrnamentSeed] so every one of the 114 chapters carries a
 * distinct star composition, its length in verses the dominant term. Part
 * of the page's fixed typography rather than a ceremony, so it is static:
 * complete on first composition, no build wash. Same stroke-weight recipe
 * as [GeneratedCornerSeals] (1dp uniform) — both render at a similar,
 * small physical size.
 */
@Composable
fun GeneratedChapterRosette(
    spec: RosetteSpec,
    size: Dp,
    brightGold: Color,
    deepGold: Color,
    embossDark: Color,
    embossLight: Color,
    sheen: State<Float>,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier
            .size(size)
            .drawWithCache {
                val d = min(this.size.width, this.size.height)
                val paths = RosettePaths(
                    spec,
                    d,
                    ruleWidth = 1.dp.toPx(),
                    hairWidth = 1.dp.toPx(),
                )
                onDrawBehind {
                    val gold = goldBrush(brightGold, deepGold, sheen.value.coerceIn(0f, 1f))
                    drawRosette(
                        paths,
                        Offset(this.size.width / 2f, this.size.height / 2f),
                        gold,
                        embossDark,
                        embossLight,
                        1f,
                    )
                }
            },
    )
}

/** Centre of the border band's cross-section, from the frame geometry. */
private fun bandCenter(geometry: CoverFrameGeometry): Float =
    (geometry.outerInsetPx + geometry.innerInsetPx) / 2f

/**
 * Diameter of the corner seal's normalised (0..1) drawing box, sized so the
 * seal's outermost feature lands exactly on [CoverFrameGeometry.starRadiusPx].
 *
 * The seal's bezel tips sit at [RosetteSpec.tipRadius] — 0.58–0.66 of the box,
 * not 0.5 — so drawing it at `starRadiusPx * 2` overhangs the intended radius
 * by ~30% and breaks the outer gilt rule. Both the seals and the band's
 * channel mouth read this, so they stay one piece of geometry.
 */
internal fun sealBoxPx(geometry: CoverFrameGeometry, seal: RosetteSpec): Float {
    val extent = max(0.5f, seal.tipRadius.toFloat())
    return geometry.starRadiusPx / extent
}

private const val INV_SQRT2 = 0.70710678f

/**
 * Screen corner radius that produced [outerCornerPx], undoing the concentric
 * inset. Floored at the inset, which is exactly what a collapsed (square)
 * frame corner means.
 */
private fun screenRadius(geometry: CoverFrameGeometry, outerCornerPx: Float): Float =
    outerCornerPx + geometry.outerInsetPx

/**
 * Distance from both screen edges to a corner seal's centre, for a corner
 * whose display radius is [screenRadiusPx].
 *
 * The two gilt rules are concentric *rounded* rects, so at a rounded corner
 * the band turns: the point where the two straight centrelines would cross
 * — the miter — sits outside the band's own arc, by 0.41 × its distance to
 * the arc's centre. Seating the seal there pushed it out through the outer
 * rule on any phone with a generous corner radius. The seal instead rides
 * the band's centreline arc at 45°, which puts its disc exactly inscribed
 * in the band's corner annulus: tangent to the outer rule and tangent to
 * the inner one, the same fit it has along the straight runs.
 *
 * When the arc has collapsed — a square display, or a radius the inset has
 * used up — this returns the miter point, so the two cases meet smoothly.
 */
internal fun sealCenterPx(geometry: CoverFrameGeometry, screenRadiusPx: Float): Float {
    val c = bandCenter(geometry)
    val arc = screenRadiusPx - c
    return if (arc > 0f) screenRadiusPx - arc * INV_SQRT2 else c
}

/**
 * The four corner seals — small stars of the medallion's family, the hubs
 * the border band's channels taper onto. Part of the tooled binding, so
 * they are complete from the first frame: the board arrives already bound,
 * only the illumination (monogram, field) inks in. Fills the cover;
 * positions come from [geometry].
 */
@Composable
fun GeneratedCornerSeals(
    spec: RosetteSpec,
    geometry: CoverFrameGeometry,
    brightGold: Color,
    deepGold: Color,
    embossDark: Color,
    embossLight: Color,
    sheen: State<Float>,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier
            .fillMaxSize()
            .drawWithCache {
                val d = sealBoxPx(geometry, spec)
                val paths = RosettePaths(
                    spec,
                    d,
                    ruleWidth = 1.dp.toPx(),
                    hairWidth = 1.dp.toPx(),
                )
                // Each corner rides its own arc: displays may report four
                // different radii, and a seal seated for the wrong one
                // leaves the band.
                val oc = geometry.outerCorners
                val tl = sealCenterPx(geometry, screenRadius(geometry, oc.topLeft))
                val tr = sealCenterPx(geometry, screenRadius(geometry, oc.topRight))
                val bl = sealCenterPx(geometry, screenRadius(geometry, oc.bottomLeft))
                val br = sealCenterPx(geometry, screenRadius(geometry, oc.bottomRight))
                val corners = listOf(
                    Offset(tl, tl),
                    Offset(size.width - tr, tr),
                    Offset(bl, size.height - bl),
                    Offset(size.width - br, size.height - br),
                )
                onDrawBehind {
                    val gold = goldBrush(brightGold, deepGold, sheen.value.coerceIn(0f, 1f))
                    for (corner in corners) {
                        drawRosette(paths, corner, gold, embossDark, embossLight, 1f)
                    }
                }
            },
    )
}

/**
 * The generated border frieze: the band pattern runs along the straight
 * part of all four sides, between the two gilt rules, as a railed channel
 * whose mouth tapers open where the band leaves the corner — the seal is
 * seated on that corner's arc, so border and corner ornament read as one
 * continuous piece of tooling rather than a stamp over a strip. Each run
 * fits a whole number of periods (the period stretches a little to fit),
 * so the pattern arrives at every corner at the same phase. The band is
 * the binding's tooling, not illumination — it is complete from the first
 * frame, never animated. [seal] supplies the petal-tip radius.
 */
@Composable
fun GeneratedBorderBand(
    spec: BorderSpec,
    seal: RosetteSpec,
    geometry: CoverFrameGeometry,
    brightGold: Color,
    deepGold: Color,
    embossDark: Color,
    embossLight: Color,
    sheen: State<Float>,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier
            .fillMaxSize()
            .drawWithCache {
                val bandH = ((geometry.innerInsetPx - geometry.outerInsetPx) * 0.72f)
                    .coerceIn(10.dp.toPx(), 20.dp.toPx())
                val c = bandCenter(geometry)
                val w = size.width
                val h = size.height
                // The frieze only runs along the straight part of the band:
                // past the corner arc (where the seal is seated) and past
                // the seal's own reach, whichever is further out. The
                // channel mouth then tapers into the run from there. All
                // four runs share one start so the pattern stays in phase,
                // so the widest corner sets it.
                val arcEnd = screenRadius(geometry, geometry.outerCorners.max)
                val sealU = sealCenterPx(geometry, arcEnd) +
                    sealBoxPx(geometry, seal) * seal.tipRadius.toFloat()
                val taper = bandH * 0.8f
                val bandStart = max(sealU, arcEnd) + taper
                val tipU = bandStart - taper
                val periodPx = (spec.period * bandH).toFloat()

                // (u along the side, v across the band) → screen, per side;
                // v mirrored on the far sides so the pattern faces inward.
                val sides = listOf<Pair<Float, (Float, Float) -> Offset>>(
                    w to { u, v -> Offset(u, c + (v - 0.5f) * bandH) },
                    w to { u, v -> Offset(u, h - c - (v - 0.5f) * bandH) },
                    h to { u, v -> Offset(c + (v - 0.5f) * bandH, u) },
                    h to { u, v -> Offset(w - c - (v - 0.5f) * bandH, u) },
                )

                // Pattern strokes stay hairline; rails and channel mouths
                // carry rule weight so the band reads as a tooled channel.
                val hairBand = Path()
                val ruleBand = Path()
                val dotCenters = ArrayList<Pair<Offset, Float>>()
                for ((sideLen, place) in sides) {
                    // Whole periods between the two seals; the period
                    // stretches to fit so both ends land on the same phase.
                    val run = sideLen - 2f * bandStart
                    if (run < periodPx / 2f) continue
                    val tiles = max(1, (run / periodPx).roundToInt())
                    val stretch = (run / tiles) / spec.period.toFloat()
                    for (k in 0 until tiles) {
                        val u0 = bandStart + k * (run / tiles)
                        for (s in spec.strokes) {
                            val path = if (s.weight == StrokeWeight.Rule) ruleBand else hairBand
                            s.points.forEachIndexed { i, p ->
                                val pt = place(u0 + (p.x * stretch).toFloat(), p.y.toFloat())
                                if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                            }
                            if (s.closed) path.close()
                        }
                        for (d in spec.dots) {
                            dotCenters.add(
                                place(u0 + (d.x * stretch).toFloat(), d.y.toFloat()) to
                                    (d.radius * bandH).toFloat(),
                            )
                        }
                    }
                    // Channel mouths: both rails converge onto the petal tip.
                    for ((mouth, point) in listOf(
                        bandStart to tipU,
                        sideLen - bandStart to sideLen - tipU,
                    )) {
                        val tipAt = place(point, 0.5f)
                        for (v in floatArrayOf(0f, 1f)) {
                            val railEnd = place(mouth, v)
                            ruleBand.moveTo(railEnd.x, railEnd.y)
                            ruleBand.lineTo(tipAt.x, tipAt.y)
                        }
                    }
                }
                val hairStroke = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val ruleStroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

                onDrawBehind {
                    val gold = goldBrush(brightGold, deepGold, sheen.value.coerceIn(0f, 1f))
                    for ((band, stroke) in listOf(hairBand to hairStroke, ruleBand to ruleStroke)) {
                        translate(0.6f, 0.6f) { drawPath(band, embossDark, style = stroke, alpha = 0.7f) }
                        translate(-0.6f, -0.6f) { drawPath(band, embossLight, style = stroke, alpha = 0.7f) }
                        drawPath(band, gold, style = stroke)
                    }
                    for ((center, r) in dotCenters) drawCircle(gold, radius = r, center = center)
                }
            },
    )
}

/**
 * A generated Hankin field tiled at whisper ink — the cover's leather
 * tooling when [build] is given (washes in over the first half of the
 * build, re-drawing only, geometry built once per size), or a chapter's
 * static surah-header backdrop when [build] is null (fully inked from the
 * first frame, fixed typography rather than a ceremony).
 */
fun Modifier.generatedFieldWeave(
    field: FieldSpec,
    ink: Color,
    embossLight: Color,
    build: State<Float>? = null,
): Modifier = clipToBounds().drawWithCache {
    // Tile one cell past every edge so the weave reads continuous at the
    // bounds, then clip so neighbouring sections never paint over each other
    // (the Lab's stacked previews depend on this).
    val pxPerUnit = (field.cellWidthDp.toFloat().dp.toPx()) / field.cellW.toFloat()
    val cellW = (field.cellW * pxPerUnit).toFloat()
    val cellH = (field.cellH * pxPerUnit).toFloat()
    val weave = Path()
    var y = -cellH
    while (y < size.height + cellH) {
        var x = -cellW
        while (x < size.width + cellW) {
            for (s in field.strokes) {
                s.points.forEachIndexed { i, p ->
                    val px = x + (p.x * pxPerUnit).toFloat()
                    val py = y + (p.y * pxPerUnit).toFloat()
                    if (i == 0) weave.moveTo(px, py) else weave.lineTo(px, py)
                }
                if (s.closed) weave.close()
            }
            x += cellW
        }
        y += cellH
    }
    val stroke = Stroke(width = 1.dp.toPx())
    onDrawBehind {
        val a = if (build == null) 1f else (build.value / 0.55f).coerceIn(0f, 1f)
        if (a <= 0f) return@onDrawBehind
        translate(-0.6f, -0.6f) {
            drawPath(weave, embossLight.copy(alpha = embossLight.alpha * a), style = stroke)
        }
        drawPath(weave, ink.copy(alpha = ink.alpha * a), style = stroke)
    }
}
