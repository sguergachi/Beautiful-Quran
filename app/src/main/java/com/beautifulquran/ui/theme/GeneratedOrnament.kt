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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.beautifulquran.ui.entrance.CoverFrameGeometry
import com.beautifulquran.ui.theme.ornament.BorderSpec
import com.beautifulquran.ui.theme.ornament.FieldSpec
import com.beautifulquran.ui.theme.ornament.fittedFieldCellWidth
import com.beautifulquran.ui.theme.ornament.RosetteSpec
import com.beautifulquran.ui.theme.ornament.StrokeWeight
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

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
    emboss: Float = 0.8f,
) {
    translate(center.x, center.y) {
        for (s in paths.strokes) {
            val path = grownPath(s, progress) ?: continue
            val stroke = Stroke(width = s.width, cap = StrokeCap.Round, join = StrokeJoin.Round)
            translate(emboss, emboss) { drawPath(path, embossDark, style = stroke) }
            translate(-emboss, -emboss) { drawPath(path, embossLight, style = stroke) }
            drawPath(path, gold, style = stroke)
        }
        drawDots(paths, gold, progress)
    }
}

/** The relief half of [drawRosette] — two nudged copies, no gold. It does not
 *  depend on the sheen, so a finished rosette records it once. */
private fun DrawScope.drawRosetteRelief(
    paths: RosettePaths,
    center: Offset,
    embossDark: Color,
    embossLight: Color,
    emboss: Float,
) {
    translate(center.x, center.y) {
        for (s in paths.strokes) {
            val stroke = Stroke(width = s.width, cap = StrokeCap.Round, join = StrokeJoin.Round)
            translate(emboss, emboss) { drawPath(s.full, embossDark, style = stroke) }
            translate(-emboss, -emboss) { drawPath(s.full, embossLight, style = stroke) }
        }
    }
}

/** The leaf half of [drawRosette]: one pass of gold per stroke, which is all
 *  a tilting sheen has to redraw. */
private fun DrawScope.drawRosetteGold(
    paths: RosettePaths,
    center: Offset,
    gold: androidx.compose.ui.graphics.Brush,
) {
    translate(center.x, center.y) {
        for (s in paths.strokes) {
            drawPath(
                s.full,
                gold,
                style = Stroke(width = s.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        drawDots(paths, gold, 1f)
    }
}

private fun grownPath(s: StrokeRender, progress: Float): Path? {
    val f = ((progress - s.birth) / s.span).coerceIn(0f, 1f)
    if (f <= 0f) return null
    if (f >= 1f) return s.full
    s.partial.reset()
    s.measure.getSegment(0f, s.length * f, s.partial, true)
    return s.partial
}

private fun DrawScope.drawDots(
    paths: RosettePaths,
    gold: androidx.compose.ui.graphics.Brush,
    progress: Float,
) {
    for (d in paths.dots) {
        val f = ((progress - d.birth) / 0.06f).coerceIn(0f, 1f)
        if (f <= 0f) continue
        drawCircle(gold, radius = d.radius * f, center = d.center)
    }
}

/**
 * The generated medallion — this launch's own star composition at the
 * ceremonial centre of the cover. [build] 0..1 inks it in stroke by stroke;
 * [sheen] tilts the leaf, both read only at draw time.
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
    GildingDemand(sheen)
    Spacer(
        modifier
            .size(size)
            .gildingLayer()
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
 * small physical size. A miniature (the top bar's) passes a hairline
 * [stroke]: at 1dp the strokes of a star that small run into one gold disc,
 * so the line work needs to shrink with it, emboss offset included.
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
    stroke: Dp = 1.dp,
) {
    GildingDemand(sheen)
    // A finished rosette only moves its light. The relief (two nudged copies of
    // every stroke) does not, so it is recorded once into a layer and replayed;
    // each tilt step then redraws one pass of gold instead of three.
    val relief = rememberGraphicsLayer()
    Spacer(
        modifier
            .size(size)
            .gildingLayer()
            .drawWithCache {
                val d = min(this.size.width, this.size.height)
                val paths = RosettePaths(
                    spec,
                    d,
                    ruleWidth = stroke.toPx(),
                    hairWidth = stroke.toPx(),
                )
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                var recorded = false
                onDrawBehind {
                    if (!recorded) {
                        relief.record {
                            drawRosetteRelief(
                                paths,
                                center,
                                embossDark,
                                embossLight,
                                emboss = max(RELIEF_FLOOR_PX, 0.8f * (stroke / 1.dp)),
                            )
                        }
                        recorded = true
                    }
                    drawLayer(relief)
                    drawRosetteGold(
                        paths,
                        center,
                        goldBrush(brightGold, deepGold, sheen.value.coerceIn(0f, 1f)),
                    )
                }
            },
    )
}

/** The relief never thins below this (px): a hairline rosette still reads as pressed. */
private const val RELIEF_FLOOR_PX = 0.6f

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
 * One side's border run in a canonical frame: the band's centreline from the
 * seal at one end, round its corner arc, along the straight, and round the
 * far corner arc into the seal at the other end. [u] is arc length from the
 * first seal's centre; [point] places a cross-band offset (positive = inward)
 * on that line, so a pattern laid by (u, offset) bends round each corner
 * instead of stopping where the straight ends.
 *
 * The frame is the top edge: x along it, y in from it. The other three sides
 * reuse it through a flip or swap. [radius0]/[radius1] are the display's
 * corner radii at the two ends; a corner with no arc left (square display, or
 * a radius the inset has used up) collapses to the miter, exactly as
 * [sealCenterPx] does, so the two meet at u = 0.
 */
internal class BandRun(
    private val sideLen: Float,
    private val centre: Float,
    radius0: Float,
    radius1: Float,
) {
    private val r0 = max(radius0 - centre, 0f)
    private val r1 = max(radius1 - centre, 0f)
    private val x0 = centre + r0
    private val x1 = centre + r1
    private val arc0 = QUARTER_OF_QUARTER * r0
    private val straight = max(sideLen - x0 - x1, 0f)
    private val arc1 = QUARTER_OF_QUARTER * r1

    /** Seal centre to seal centre, along the centreline. */
    val length: Float = arc0 + straight + arc1

    fun point(u: Float, inward: Float): Offset {
        if (u < arc0 && r0 > 0f) {
            val phi = (arc0 - u) / r0
            val rho = max(r0 - inward, 0f)
            return Offset(x0 - rho * sin(phi), x0 - rho * cos(phi))
        }
        if (u > arc0 + straight && r1 > 0f) {
            val phi = (u - arc0 - straight) / r1
            val rho = max(r1 - inward, 0f)
            return Offset(sideLen - x1 + rho * sin(phi), x1 - rho * cos(phi))
        }
        return Offset(x0 + (u - arc0), centre + inward)
    }

    /** [pts] are (u, inward) pairs; the polyline is subdivided so it follows
     *  the arcs rather than cutting a chord across them. */
    fun trace(
        path: Path,
        pts: List<Offset>,
        closed: Boolean,
        toScreen: (Offset) -> Offset,
        maxStep: Float,
    ) {
        val count = if (closed) pts.size + 1 else pts.size
        var prev = pts[0]
        val start = toScreen(point(prev.x, prev.y))
        path.moveTo(start.x, start.y)
        for (i in 1 until count) {
            val next = pts[i % pts.size]
            val steps = max(1, ceil(hypot(next.x - prev.x, next.y - prev.y) / maxStep).toInt())
            for (k in 1..steps) {
                val t = k / steps.toFloat()
                val q = toScreen(point(prev.x + (next.x - prev.x) * t, prev.y + (next.y - prev.y) * t))
                path.lineTo(q.x, q.y)
            }
            prev = next
        }
        if (closed) path.close()
    }

    private companion object {
        const val QUARTER_OF_QUARTER = (PI / 4).toFloat()
    }
}

/**
 * The four corner seals — small stars of the medallion's family, the hubs
 * the border band's channels taper onto. Part of the tooled binding, so
 * they are complete from the first frame: the board arrives already bound,
 * only the illumination (medallion, field) inks in. Fills the cover;
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
    GildingDemand(sheen)
    Spacer(
        modifier
            .fillMaxSize()
            .gildingLayer()
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
 * The generated border frieze: the band pattern runs the whole way round,
 * between the two gilt rules, as a railed channel — along each straight and
 * round each corner arc until its mouth tapers onto the corner seal's petal
 * tip. The seal is seated on that arc, so border and corner ornament read as
 * one continuous piece of tooling rather than a stamp over a strip. Each run
 * fits a whole number of periods (the period stretches a little to fit),
 * so the pattern arrives at every seal at the same phase. The band is
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
    GildingDemand(sheen)
    Spacer(
        modifier
            .fillMaxSize()
            .gildingLayer()
            .drawWithCache {
                val bandH = ((geometry.innerInsetPx - geometry.outerInsetPx) * 0.72f)
                    .coerceIn(10.dp.toPx(), 20.dp.toPx())
                val c = bandCenter(geometry)
                val w = size.width
                val h = size.height
                // Each run starts at one corner seal and ends at the next,
                // carrying the pattern round the corner arc into the seal
                // (no gap, no abrupt end). The channel mouth tapers onto the
                // seal's petal tip, so the run owns only what lies between
                // the two tips; whole periods fit that span, the period
                // stretching a little, so every run meets its seals at the
                // same phase.
                val tipU = sealBoxPx(geometry, seal) * seal.tipRadius.toFloat() * 0.9f
                val taper = bandH * 0.8f
                val bandStart = tipU + taper
                val periodPx = (spec.period * bandH).toFloat()
                val maxStep = 2.5.dp.toPx()
                val oc = geometry.outerCorners
                val rTl = screenRadius(geometry, oc.topLeft)
                val rTr = screenRadius(geometry, oc.topRight)
                val rBl = screenRadius(geometry, oc.bottomLeft)
                val rBr = screenRadius(geometry, oc.bottomRight)

                // A run in the canonical top-edge frame → screen, per side;
                // inward stays inward on every side so the pattern faces in.
                class Side(val run: BandRun, val toScreen: (Offset) -> Offset)
                val sides = listOf(
                    Side(BandRun(w, c, rTl, rTr)) { Offset(it.x, it.y) },
                    Side(BandRun(w, c, rBl, rBr)) { Offset(it.x, h - it.y) },
                    Side(BandRun(h, c, rTl, rBl)) { Offset(it.y, it.x) },
                    Side(BandRun(h, c, rTr, rBr)) { Offset(w - it.y, it.x) },
                )

                // Pattern strokes stay hairline; rails and channel mouths
                // carry rule weight so the band reads as a tooled channel.
                val hairBand = Path()
                val ruleBand = Path()
                val dotCenters = ArrayList<Pair<Offset, Float>>()
                for (side in sides) {
                    val run = side.run
                    val span = run.length - 2f * bandStart
                    if (span < periodPx / 2f) continue
                    val tiles = max(1, (span / periodPx).roundToInt())
                    val stretch = (span / tiles) / spec.period.toFloat()
                    for (k in 0 until tiles) {
                        val u0 = bandStart + k * (span / tiles)
                        for (s in spec.strokes) {
                            val path = if (s.weight == StrokeWeight.Rule) ruleBand else hairBand
                            val pts = s.points.map {
                                Offset(u0 + (it.x * stretch).toFloat(), ((it.y.toFloat() - 0.5f) * bandH))
                            }
                            run.trace(path, pts, s.closed, side.toScreen, maxStep)
                        }
                        for (d in spec.dots) {
                            dotCenters.add(
                                side.toScreen(
                                    run.point(
                                        u0 + (d.x * stretch).toFloat(),
                                        (d.y.toFloat() - 0.5f) * bandH,
                                    ),
                                ) to (d.radius * bandH).toFloat(),
                            )
                        }
                    }
                    // Channel mouths: both rails converge onto the petal tip.
                    for ((mouth, point) in listOf(
                        bandStart to tipU,
                        run.length - bandStart to run.length - tipU,
                    )) {
                        for (v in floatArrayOf(-0.5f, 0.5f)) {
                            run.trace(
                                ruleBand,
                                listOf(Offset(mouth, v * bandH), Offset(point, 0f)),
                                false,
                                side.toScreen,
                                maxStep,
                            )
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
 * A generated geometric field with filigree tiled at whisper ink. Geometry
 * is built once per size; the cover washes this static tooling in with its
 * own layer alpha, while chapter backdrops arrive fully inked.
 */
fun Modifier.generatedFieldWeave(
    field: FieldSpec,
    ink: Color,
    embossLight: Color,
): Modifier = clipToBounds().drawWithCache {
    // Tile one cell past every edge so the weave reads continuous at the
    // bounds, then clip so neighbouring sections never paint over each other
    // (the Lab's stacked previews depend on this).
    val fittedWidth = fittedFieldCellWidth(size.width.toDp().value.toDouble(), field.cellWidthDp)
    val pxPerUnit = fittedWidth.toFloat().dp.toPx() / field.cellW.toFloat()
    val cellW = (field.cellW * pxPerUnit).toFloat()
    val cellH = (field.cellH * pxPerUnit).toFloat()
    val weaves = listOf(Path(), Path())
    var y = -cellH
    while (y < size.height + cellH) {
        var x = -cellW
        while (x < size.width + cellW) {
            for (s in field.strokes) {
                val weave = weaves[if (s.weight == StrokeWeight.Rule) 0 else 1]
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
    val styles = listOf(Stroke(width = 0.6.dp.toPx()), Stroke(width = 0.33.dp.toPx()))
    onDrawBehind {
        for (i in weaves.indices) {
            translate(-0.6f, -0.6f) {
                drawPath(weaves[i], embossLight, style = styles[i])
            }
            drawPath(weaves[i], ink, style = styles[i])
        }
    }
}

/**
 * A rosette drawn as line-work in one ink, with no gilt brush and no emboss —
 * the same star the cover carries, but written rather than tooled.
 *
 * Gilded rosettes are for the boards and the ceremonial centre. At the small
 * sizes a page uses them, the gold fill and its emboss close up into a solid
 * disc; struck in a single hairline ink they stay drawings, which is what a
 * mark on paper should be.
 */
@Composable
fun GeneratedInkRosette(
    spec: RosetteSpec,
    size: Dp,
    ink: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 0.8.dp,
) {
    Spacer(
        modifier
            .size(size)
            .drawWithCache {
                val d = min(this.size.width, this.size.height)
                val w = strokeWidth.toPx()
                val paths = RosettePaths(spec, d, ruleWidth = w, hairWidth = w)
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                onDrawBehind {
                    translate(center.x, center.y) {
                        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        for (s in paths.strokes) drawPath(s.full, ink, style = stroke)
                        for (dot in paths.dots) {
                            drawCircle(ink, radius = dot.radius, center = dot.center)
                        }
                    }
                }
            },
    )
}
