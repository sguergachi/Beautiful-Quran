package com.beautifulquran.ui.theme.ornament

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The cover's centre mark: a crescent (the one correct internal/external
 * pair — two offset open arcs) and a geometric الله in its opening.
 *
 * There is no enclosing circle. A hoop around the mark reads as a badge,
 * not as tooled leather, and a third concentric ring fights the crescent's
 * own construction. The moon is the two arcs; the word sits in the opening.
 *
 * Fixed geometry, not a generated shamsa. Chapter headers keep the seeded
 * rosette; this mark is the book's own.
 */
val CoverMonogram: RosetteSpec = coverMonogram()

internal fun coverMonogram(): RosetteSpec {
    val strokes = ArrayList<OrnamentStroke>(8)
    // Slight leftward offset on the outer arc: the moon is thicker on the
    // left, open on the right. Same pair — not a third hoop around them.
    strokes.add(
        arc(
            cx = 0.47,
            cy = 0.50,
            r = 0.355,
            start = 0.58,
            end = 5.70,
            segments = 64,
            weight = StrokeWeight.Hairline,
        ),
    )
    strokes.add(
        arc(
            cx = 0.505,
            cy = 0.50,
            r = 0.248,
            start = 0.78,
            end = 5.50,
            segments = 52,
            weight = StrokeWeight.Hairline,
        ),
    )
    // Geometric الله in the opening. Alif, ha (the chevron), the two lams,
    // dagger alif — the construction from the cover study, without a ring.
    strokes.add(polyline(listOf(pt(0.620, 0.318), pt(0.620, 0.668)), StrokeWeight.Rule))
    strokes.add(
        polyline(
            listOf(pt(0.620, 0.430), pt(0.768, 0.498), pt(0.620, 0.668)),
            StrokeWeight.Rule,
        ),
    )
    strokes.add(polyline(listOf(pt(0.572, 0.668), pt(0.668, 0.668)), StrokeWeight.Rule))
    strokes.add(polyline(listOf(pt(0.498, 0.348), pt(0.620, 0.448)), StrokeWeight.Rule))
    strokes.add(polyline(listOf(pt(0.468, 0.548), pt(0.620, 0.668)), StrokeWeight.Rule))
    strokes.add(polyline(listOf(pt(0.538, 0.308), pt(0.612, 0.378)), StrokeWeight.Hairline))
    return RosetteSpec(fold = 1, strokes = assignBirths(strokes), dots = emptyList())
}

private fun pt(x: Double, y: Double) = OrnamentPoint(x, y)

private fun polyline(points: List<OrnamentPoint>, weight: StrokeWeight) =
    OrnamentStroke(points, closed = false, weight = weight, birth = 0.0, span = 1.0)

private fun arc(
    cx: Double,
    cy: Double,
    r: Double,
    start: Double,
    end: Double,
    segments: Int,
    weight: StrokeWeight,
): OrnamentStroke {
    val pts = ArrayList<OrnamentPoint>(segments + 1)
    for (i in 0..segments) {
        val a = start + (end - start) * i / segments
        pts.add(OrnamentPoint(cx + r * cos(a), cy + r * sin(a)))
    }
    return OrnamentStroke(pts, closed = false, weight = weight, birth = 0.0, span = 1.0)
}

private fun assignBirths(strokes: List<OrnamentStroke>): List<OrnamentStroke> {
    val n = strokes.size
    return strokes.mapIndexed { i, s ->
        val birth = 0.06 + 0.68 * i / n
        OrnamentStroke(s.points, s.closed, s.weight, birth, minOf(0.28, 0.97 - birth))
    }
}

/** Sweep of an open arc, in radians. A full ring is 2π. */
internal fun arcSweep(stroke: OrnamentStroke): Double {
    if (stroke.points.size < 3) return 0.0
    val c = arcCenter(stroke) ?: return 0.0
    val a0 = kotlin.math.atan2(stroke.points.first().y - c.y, stroke.points.first().x - c.x)
    val a1 = kotlin.math.atan2(stroke.points.last().y - c.y, stroke.points.last().x - c.x)
    var d = a1 - a0
    if (d < 0.0) d += 2.0 * PI
    return d
}

internal fun arcCenter(stroke: OrnamentStroke): OrnamentPoint? {
    if (stroke.points.size < 3) return null
    val a = stroke.points.first()
    val b = stroke.points[stroke.points.size / 2]
    val c = stroke.points.last()
    val d = 2.0 * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y))
    if (kotlin.math.abs(d) < 1e-9) return null
    val a2 = a.x * a.x + a.y * a.y
    val b2 = b.x * b.x + b.y * b.y
    val c2 = c.x * c.x + c.y * c.y
    val ux = (a2 * (b.y - c.y) + b2 * (c.y - a.y) + c2 * (a.y - b.y)) / d
    val uy = (a2 * (c.x - b.x) + b2 * (a.x - c.x) + c2 * (b.x - a.x)) / d
    return OrnamentPoint(ux, uy)
}
