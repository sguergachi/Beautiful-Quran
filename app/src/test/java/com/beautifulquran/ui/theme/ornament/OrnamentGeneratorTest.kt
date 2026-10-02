package com.beautifulquran.ui.theme.ornament

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class OrnamentGeneratorTest {
    @Test
    fun `all chapters have distinct coarse field and medallion shapes with safe geometry`() {
        fun shape(strokes: List<OrnamentStroke>) = strokes.map { s ->
            s.closed to s.points.map { p -> kotlin.math.round(p.x * 16).toInt() to kotlin.math.round(p.y * 16).toInt() }
        }
        fun crosses(a: OrnamentPoint, b: OrnamentPoint, c: OrnamentPoint, d: OrnamentPoint): Boolean {
            val dx = b.x - a.x; val dy = b.y - a.y; val ex = d.x - c.x; val ey = d.y - c.y
            val den = dx * ey - dy * ex
            if (abs(den) < 1e-10) return false
            val t = ((c.x - a.x) * ey - (c.y - a.y) * ex) / den
            val u = ((c.x - a.x) * dy - (c.y - a.y) * dx) / den
            return t > 1e-6 && t < 1 - 1e-6 && u > 1e-6 && u < 1 - 1e-6
        }
        val verseCounts = listOf(7, 286, 200, 176, 120, 165, 206, 75, 129, 109, 123, 111, 43, 52, 99, 128, 111, 110, 98, 135, 112, 78, 118, 64, 77, 227, 93, 88, 69, 60, 34, 30, 73, 54, 45, 83, 182, 88, 75, 85, 54, 53, 89, 59, 37, 35, 38, 29, 18, 45, 60, 49, 62, 55, 78, 96, 29, 22, 24, 13, 14, 11, 11, 18, 12, 12, 30, 52, 52, 44, 28, 28, 20, 56, 40, 31, 50, 40, 46, 42, 29, 19, 36, 25, 22, 17, 19, 26, 30, 20, 15, 21, 11, 8, 8, 19, 5, 8, 8, 11, 11, 8, 3, 9, 5, 4, 7, 3, 6, 3, 5, 4, 5, 6)
        for (sample in listOf(3, 11, 286, 0)) {
            val fields = mutableSetOf<Any>(); val rosettes = mutableSetOf<Any>()
            for (chapter in 1..114) {
                val o = generateChapterOrnament(chapterOrnamentSeed(chapter, if (sample == 0) verseCounts[chapter - 1] else sample)); val f = o.field
                fields.add(shape(f.strokes)); rosettes.add(shape(o.rosette.strokes))
                for (s in f.strokes + o.rosette.strokes) {
                    assertFalse(s.closed && s.points.size == 3)
                    if (s.closed && s.points.size == 5) {
                        val a = atan2(s.points[0].y - 0.5, s.points[0].x - 0.5)
                        val b = atan2(s.points[1].y - 0.5, s.points[1].x - 0.5)
                        val delta = ((b - a) % (2 * PI) + 2 * PI) % (2 * PI)
                        assertTrue(abs(delta - 4 * PI / 5) > 0.05)
                        assertTrue(abs(delta - 6 * PI / 5) > 0.05)
                    }
                }
                val points = f.strokes.flatMap { it.points }
                assertTrue(points.all { it.x in -1e-9..1 + 1e-9 && it.y in -1e-9..1 + 1e-9 })
                fun key(x: Double, y: Double) = kotlin.math.round(x * 1e9).toLong() to kotlin.math.round(y * 1e9).toLong()
                val balanced = points.map { key(it.x, it.y) }.toSet()
                assertTrue(points.all { key(1 - it.y, it.x) in balanced && key(1 - it.x, it.y) in balanced })
                val frames = f.strokes.take(2).map { it.points }
                var clearance = Double.POSITIVE_INFINITY
                for (s in f.strokes.drop(2)) {
                    for (p in s.points) for (frame in frames) for (j in frame.indices) {
                        val a = frame[j]; val b = frame[(j + 1) % frame.size]
                        val dx = b.x - a.x; val dy = b.y - a.y
                        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
                        clearance = minOf(clearance, hypot(p.x - a.x - t * dx, p.y - a.y - t * dy))
                    }
                }
                assertTrue("chapter $chapter: frame clearance", clearance > 0.02)
                val edges = f.strokes.flatMap { s -> (if (s.closed) s.points + s.points.first() else s.points).zipWithNext() }
                var crossing = false
                for (i in edges.indices) for (j in i + 1 until edges.size) {
                    val (a, b) = edges[i]; val (c, d) = edges[j]
                    if (maxOf(a.x, b.x) < minOf(c.x, d.x) || maxOf(c.x, d.x) < minOf(a.x, b.x) ||
                        maxOf(a.y, b.y) < minOf(c.y, d.y) || maxOf(c.y, d.y) < minOf(a.y, b.y)) continue
                    if (crosses(a, b, c, d)) crossing = true
                }
                assertFalse("chapter $chapter: accidental crossing", crossing)
                val inkLength = f.strokes.sumOf { s ->
                    (if (s.closed) s.points + s.points.first() else s.points).zipWithNext().sumOf { (p, q) -> hypot(p.x - q.x, p.y - q.y) } *
                        if (s.weight == StrokeWeight.Rule) 1.0 else 0.55
                }
                assertTrue(inkLength / f.cellWidthDp <= 11.5 / 48 + 1e-9)
                assertTrue(f.cellWidthDp <= 58)
            }
            assertEquals(114, fields.size)
            assertEquals(114, rosettes.size)
        }
    }

    @Test
    fun `chapter known answers match web across the new layouts`() {
        val chapters = listOf(1 to 7, 4 to 176, 7 to 206, 112 to 4, 114 to 6)
        val widths = listOf(49.27532235905528, 52.470998737961054, 54.28618001751602, 55.88055209070444, 53.45646559819579)
        val frames = listOf(16, 12, 4, 12, 8); val strokes = listOf(31, 19, 20, 20, 27)
        val folds = listOf(8, 8, 10, 16, 8); val rosettes = listOf(8, 13, 7, 22, 6)
        for ((i, chapter) in chapters.withIndex()) {
            val o = generateChapterOrnament(chapterOrnamentSeed(chapter.first, chapter.second))
            assertEquals(widths[i], o.field.cellWidthDp, 1e-9)
            assertEquals(frames[i], o.field.strokes.first().points.size)
            assertEquals(strokes[i], o.field.strokes.size)
            assertEquals(folds[i], o.rosette.fold)
            assertEquals(rosettes[i], o.rosette.strokes.size)
        }
    }

    @Test
    fun `fits complete even repeats on narrow phone and wide fields`() {
        for ((width, count) in listOf(20 to 2, 320 to 6, 390 to 8, 450 to 10, 768 to 16)) {
            assertEquals(width.toDouble() / count, fittedFieldCellWidth(width.toDouble(), 50.0), 1e-9)
        }
        assertEquals(50.0, fittedFieldCellWidth(0.0, 50.0), 0.0)
    }


    /**
     * mulberry32 known-answer values (computed with the reference JS
     * implementation). The web port asserts the same values — this is the
     * cross-platform contract that keeps both covers drawing from one stream.
     */
    @Test
    fun `prng matches reference mulberry32 stream`() {
        val expected = mapOf(
            1 to longArrayOf(2693262067L, 11749833L, 2265367787L, 4213581821L),
            123456789 to longArrayOf(1107202814L, 4169434471L, 3372958138L, 885470128L),
            -7 to longArrayOf(1860010037L, 1397564179L, 2337619704L, 2062400319L),
        )
        for ((seed, values) in expected) {
            val rng = Mulberry32(seed)
            for (v in values) assertEquals("seed $seed", v, rng.nextUInt())
        }
    }

    @Test
    fun `page ornament seeds are distinct from chapter seeds`() {
        val pages = (1..604).map { pageOrnamentSeed(it) }.toSet()
        assertEquals(604, pages.size)
        val chapters = (1..114).flatMap { n ->
            listOf(1, 7, 200, 286).map { chapterOrnamentSeed(n, it) }
        }.toSet()
        assertTrue(pages.intersect(chapters).isEmpty())
    }

    @Test
    fun `same seed grows the same ornament`() {
        for (seed in intArrayOf(1, 42, -913, 2_000_000_011.toInt())) {
            assertEquals(generateCoverOrnament(seed), generateCoverOrnament(seed))
        }
    }

    @Test
    fun `different seeds grow different ornaments`() {
        val a = generateCoverOrnament(1)
        var anyDiffer = false
        for (seed in 2..12) {
            if (generateCoverOrnament(seed) != a) anyDiffer = true
        }
        assertTrue(anyDiffer)
    }

    @Test
    fun `every seed in a wide sample generates a sane ornament`() {
        for (seed in 0 until 300) {
            val o = generateCoverOrnament(seed * 7919 + seed)
            assertTrue(o.medallion.strokes.size >= 4)
            assertTrue(o.medallion.dots.isNotEmpty())
            assertTrue(o.cornerSeal.strokes.isNotEmpty())
            assertTrue(o.border.strokes.isNotEmpty())
            assertTrue(o.border.period > 0.5)
            assertTrue(o.field.strokes.size >= 2)
            assertTrue(o.field.cellW > 0 && o.field.cellH > 0)
            assertTrue(o.field.cellWidthDp in 40.0..200.0)

            // The medallion has no bezel; the seal's petal tips must aim
            // down the band axes just past the ring, and its bezel may
            // reach past the unit box by at most (tip − 0.5).
            assertEquals(0.0, o.medallion.tipRadius, 1e-9)
            assertTrue(o.cornerSeal.tipRadius > SEAL_RING_RADIUS)
            assertTrue(o.cornerSeal.tipRadius <= 0.7)
            // The frame's two gilt rules are the band's inner/outer; a
            // closed circle at the seal radius would be a third hoop.
            for (s in o.cornerSeal.strokes) {
                if (!s.closed) continue
                val ring = s.points.all { p ->
                    abs(hypot(p.x - 0.5, p.y - 0.5) - SEAL_RING_RADIUS) < 0.02
                }
                assertFalse("seal must not draw an enclosing ring", ring)
            }
            for (s in o.medallion.strokes) {
                assertTrue(s.points.size >= 2)
                assertTrue(s.birth >= 0.0 && s.birth + s.span <= 1.0001)
                for (p in s.points) {
                    assertTrue("medallion point out of unit box: $p", p.x in -0.001..1.001)
                    assertTrue("medallion point out of unit box: $p", p.y in -0.001..1.001)
                }
            }
            for (s in o.cornerSeal.strokes) {
                assertTrue(s.points.size >= 2)
                assertTrue(s.birth >= 0.0 && s.birth + s.span <= 1.0001)
                for (p in s.points) {
                    assertTrue("seal point too far out: $p", p.x in -0.2..1.2)
                    assertTrue("seal point too far out: $p", p.y in -0.2..1.2)
                }
            }
            // The khatam chain's link diamond straddles the period
            // boundary by design (its far half is completed by the
            // neighbouring tile when the band repeats), so the x margin
            // allows for it; y always stays inside the band.
            for (s in o.border.strokes) {
                for (p in s.points) {
                    assertTrue("border x outside period: $p", p.x in -0.2..o.border.period + 0.2)
                    assertTrue("border y outside band: $p", p.y in -0.001..1.001)
                }
            }
            // Field strokes stay near their cell (Hankin keeps rays inside
            // each polygon; polygons may legitimately straddle cell edges).
            val fx = 1.5 * o.field.cellW
            val fy = 1.5 * o.field.cellH
            for (s in o.field.strokes) {
                for (p in s.points) {
                    assertTrue("field point far outside cell: $p", p.x in -fx..fx + o.field.cellW)
                    assertTrue("field point far outside cell: $p", p.y in -fy..fy + o.field.cellH)
                }
            }
        }
    }

    @Test
    fun `medallion has full n-fold rotational symmetry`() {
        for (seed in intArrayOf(3, 17, 99, 1234, -55, 777_777)) {
            val m = generateCoverOrnament(seed).medallion
            val angle = 2.0 * PI / m.fold
            val pts = m.strokes.flatMap { it.points }
            for (p in pts) {
                val rx = 0.5 + (p.x - 0.5) * cos(angle) - (p.y - 0.5) * sin(angle)
                val ry = 0.5 + (p.x - 0.5) * sin(angle) + (p.y - 0.5) * cos(angle)
                val hit = pts.any { q -> abs(q.x - rx) < 1e-6 && abs(q.y - ry) < 1e-6 }
                assertTrue("seed $seed fold ${m.fold}: rotated point unmatched ($rx, $ry)", hit)
            }
        }
    }

    @Test
    fun `star outline meets its neighbours at every cell-edge midpoint`() {
        // Star tips land on shared edge midpoints, preserving the seamless
        // continuity that the former star-and-cross geometry required.
        for (seed in intArrayOf(5, 8, 21, 100, 4242)) {
            val f = generateCoverOrnament(seed).field
            val verts = f.strokes.flatMap { it.points }
            val midpoints = listOf(
                OrnamentPoint(f.cellW / 2, 0.0),
                OrnamentPoint(f.cellW / 2, f.cellH),
                OrnamentPoint(0.0, f.cellH / 2),
                OrnamentPoint(f.cellW, f.cellH / 2),
            )
            for (m in midpoints) {
                val hit = verts.any { abs(it.x - m.x) < 1e-9 && abs(it.y - m.y) < 1e-9 }
                assertTrue("seed $seed: no star contact at edge midpoint $m", hit)
            }
        }
    }

    @Test
    fun `never draws a hexagram - no triangles, no 6-fold stars, anywhere`() {
        for (seed in 0 until 400) {
            val o = generateCoverOrnament(seed * 104729 + 13)
            assertTrue("seal fold 6 (seed $seed)", o.cornerSeal.fold != 6)
            val everyStroke = o.medallion.strokes + o.cornerSeal.strokes +
                o.border.strokes + o.field.strokes
            for (s in everyStroke) {
                assertTrue(
                    "closed triangle found (seed $seed)",
                    !(s.closed && s.points.size == 3),
                )
            }
        }
    }

    @Test
    fun `never draws pentagrams - no 5-2 compounds stacked into occult seals`() {
        // A pentagram ({5/2}) is a closed 5-gon whose consecutive vertices
        // skip one angular neighbour (~144° on the circle). Two of those
        // interlaced is the pentacle compound ({10/4}); a convex pentagon
        // (~72°) is fine.
        val pentagramStep = 2.0 * Math.PI * 2.0 / 5.0
        for (seed in 0 until 400) {
            val o = generateCoverOrnament(seed * 104729 + 13)
            val everyStroke = o.medallion.strokes + o.cornerSeal.strokes +
                o.border.strokes + o.field.strokes
            for (s in everyStroke) {
                if (!s.closed || s.points.size != 5) continue
                val p0 = s.points[0]
                val p1 = s.points[1]
                val a0 = atan2(p0.y - 0.5, p0.x - 0.5)
                val a1 = atan2(p1.y - 0.5, p1.x - 0.5)
                var d = abs(a1 - a0)
                if (d > Math.PI) d = 2.0 * Math.PI - d
                assertTrue(
                    "pentagram 5-gon found (seed $seed)",
                    abs(d - pentagramStep) >= 0.05,
                )
            }
        }
    }

    /** Radius of every stroke's outermost point, largest first, deduped. */
    private fun zoneRadii(m: RosetteSpec): List<Double> =
        m.strokes
            .map { s -> s.points.maxOf { hypot(it.x - 0.5, it.y - 0.5) } }
            .distinctBy { Math.round(it * 1e6) }
            .sortedDescending()

    @Test
    fun `medallion zones never graze each other`() {
        // Two motifs a hair apart read as a misprint, not as a decision —
        // the defect that let the star's tips kiss the pearl band's rule.
        for (seed in 0 until 400) {
            val m = generateCoverOrnament(seed * 104729 + 13).medallion
            val radii = zoneRadii(m)
            for ((a, b) in radii.zipWithNext()) {
                assertTrue(
                    "seed $seed: zones at $a and $b nearly coincide",
                    a - b >= 0.02,
                )
            }
            // The star must clear the band's inner rule outright.
            assertEquals(0.485, radii[0], 1e-6)
            assertTrue("seed $seed: star grazes the rule", radii[1] - radii[2] >= 0.028)
        }
    }

    @Test
    fun `medallion is never a small motif floating in a bare field`() {
        // Each zone stays a readable fraction of the one outside it, and
        // the innermost one reaches the centre — no bare core.
        for (seed in 0 until 400) {
            val m = generateCoverOrnament(seed * 104729 + 13).medallion
            val radii = zoneRadii(m)
            // Below the two rules, every zone is sized from its parent.
            for ((a, b) in radii.drop(1).zipWithNext()) {
                assertTrue("seed $seed: $b is a speck inside $a", b / a >= 0.32)
            }
            assertTrue("seed $seed: hollow core (${radii.last()})", radii.last() <= 0.10)
            assertTrue("seed $seed: too few zones", radii.size >= 5)
        }
    }

    @Test
    fun `variety - a seed sample uses every fold, filigree variation and border`() {
        val folds = HashSet<Int>()
        val fieldStyles = HashSet<List<OrnamentPoint>>()
        val coverPatterns = HashSet<String>(); val chapterPatterns = HashSet<String>()
        val frames = HashSet<Int>()
        val borderShapes = HashSet<Int>()
        for (seed in 0 until 200) {
            val o = generateCoverOrnament(seed)
            folds.add(o.medallion.fold)
            fieldStyles.add(o.field.strokes[1].points)
            coverPatterns.add(o.field.pattern)
            chapterPatterns.add(generateChapterOrnament(seed).field.pattern)
            frames.add(o.field.strokes.first().points.size)
            borderShapes.add(o.border.strokes.size * 31 + o.border.dots.size)
        }
        assertEquals(setOf(8, 10, 12, 16), folds)
        assertEquals(FIELD_PATTERNS.toSet(), coverPatterns)
        assertEquals((FIELD_PATTERNS + "chamfered lattice").toSet(), chapterPatterns)
        assertEquals(setOf(4, 8, 16), frames)
        assertTrue("expected varied filigree geometry, got $fieldStyles", fieldStyles.size >= 3)
        assertTrue("expected several border grammars, got $borderShapes", borderShapes.size >= 3)
    }

    @Test
    fun `geometric fields keep filigree contained balanced and free of crossings`() {
        fun crosses(a: OrnamentPoint, b: OrnamentPoint, c: OrnamentPoint, d: OrnamentPoint): Boolean {
            val dx = b.x - a.x; val dy = b.y - a.y
            val ex = d.x - c.x; val ey = d.y - c.y
            val den = dx * ey - dy * ex
            if (abs(den) < 1e-10) return false
            val t = ((c.x - a.x) * ey - (c.y - a.y) * ex) / den
            val u = ((c.x - a.x) * dy - (c.y - a.y) * dx) / den
            return t > 1e-6 && t < 1 - 1e-6 && u > 1e-6 && u < 1 - 1e-6
        }
        repeat(400) { seed ->
            val f = generateCoverOrnament(seed * 104729 + 13).field
            val family = FIELD_PATTERNS.indexOf(f.pattern)
            val density = listOf(11.5, 11.0, 9.0)[family]
            val detailEnd = listOf(27, 19, 15)[family]
            assertEquals(if (family == 0) 31 else 23, f.strokes.size)
            assertEquals(2, f.strokes.count { it.weight == StrokeWeight.Rule })
            val pts = f.strokes.flatMap { it.points }
            val edges = f.strokes.flatMap { s ->
                (if (s.closed) s.points + s.points.first() else s.points).zipWithNext()
            }
            val inkLength = f.strokes.sumOf { s ->
                (if (s.closed) s.points + s.points.first() else s.points).zipWithNext()
                    .sumOf { (p, q) -> hypot(p.x - q.x, p.y - q.y) } *
                    if (s.weight == StrokeWeight.Rule) 1.0 else 0.55
            }
            assertTrue(inkLength / f.cellWidthDp >= density / 56 - 1e-9)
            assertTrue(inkLength / f.cellWidthDp <= density / 48 + 1e-9)
            assertTrue(pts.all { it.x in -1e-9..1 + 1e-9 && it.y in -1e-9..1 + 1e-9 })
            for (p in pts) {
                assertTrue(pts.any { q -> hypot(q.x - (1 - p.y), q.y - p.x) < 1e-9 })
                assertTrue(pts.any { q -> hypot(q.x - (1 - p.x), q.y - p.y) < 1e-9 })
            }
            val frame = f.strokes[1].points
            val heart = f.strokes[2].points
            fun distanceToEdge(p: OrnamentPoint, a: OrnamentPoint, b: OrnamentPoint): Double {
                val dx = b.x - a.x; val dy = b.y - a.y
                val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
                return hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
            }
            f.strokes.subList(3, detailEnd).forEachIndexed { i, s ->
                if (family == 1 || i % 3 != 2) assertTrue(heart.any { p ->
                    hypot(p.x - s.points.first().x, p.y - s.points.first().y) < 1e-9
                })
                for (p in s.points) {
                    assertTrue(hypot(p.x - 0.5, p.y - 0.5) < 0.411)
                    assertTrue(frame.indices.minOf { j ->
                        distanceToEdge(p, frame[j], frame[(j + 1) % frame.size])
                    } > 0.02)
                }
            }
            for (i in edges.indices) for (j in i + 1 until edges.size) {
                assertFalse("seed $seed: accidental crossing", crosses(
                    edges[i].first, edges[i].second, edges[j].first, edges[j].second,
                ))
            }
        }
    }

    @Test
    fun `field known answers match web including the reported bare-grid seed`() {
        val seeds = listOf(1, 8, 21, 132614421)
        val star = listOf(16, 16, 16) + List(8) { listOf(31, 31, 21) }.flatten() + List(4) { 21 }
        val garden = listOf(8, 8, 16) + List(20) { 21 }
        val lozenge = listOf(4, 4, 16) + List(4) { listOf(31, 31, 21) }.flatten() +
            List(4) { listOf(5, 21) }.flatten()
        val signatures = listOf(lozenge, star, garden, lozenge)
        val patterns = listOf("lozenge rosettes", "star-and-cross", "octagonal garden", "lozenge rosettes")
        val widths = listOf(47.73055739685947, 51.29965216862327, 50.4501521020009, 50.61806257444628)
        seeds.forEachIndexed { i, seed ->
            val f = generateCoverOrnament(seed).field
            assertEquals(patterns[i], f.pattern)
            assertEquals(signatures[i], f.strokes.map { it.points.size })
            assertEquals(widths[i], f.cellWidthDp, 1e-9)
        }
    }

    @Test
    fun `chapter seed recovers the chapter number regardless of ayah count`() {
        // Chapters are numbered 1..114 (not 0-indexed), so the recovered
        // digit is ((seed - 1) mod 114) + 1, not a plain mod 114.
        for (chapter in 1..114) {
            for (ayahCount in intArrayOf(3, 6, 11, 88, 286)) {
                val seed = chapterOrnamentSeed(chapter, ayahCount)
                assertEquals(chapter, (seed - 1) % 114 + 1)
            }
        }
    }

    @Test
    fun `chapter seed is unique across all 114 chapters even at a shared ayah count`() {
        // A real duplicate: 62, 63, 93, 100, 101 all have exactly 11 ayahs.
        val seeds = (1..114).map { chapterOrnamentSeed(it, 11) }
        assertEquals(114, seeds.toSet().size)
    }

    @Test
    fun `same chapter and ayah count reproduce the same ornament`() {
        val seed = chapterOrnamentSeed(2, 286)
        assertEquals(generateChapterOrnament(seed), generateChapterOrnament(seed))
    }

    @Test
    fun `chapters sharing an ayah count still render different rosettes and fields`() {
        val elevenAyahChapters = intArrayOf(62, 63, 93, 100, 101)
        val ornaments = elevenAyahChapters.map { generateChapterOrnament(chapterOrnamentSeed(it, 11)) }
        assertEquals(elevenAyahChapters.size, ornaments.map { it.rosette }.toSet().size)
        assertEquals(elevenAyahChapters.size, ornaments.map { it.field }.toSet().size)
    }

    @Test
    fun `chapter ornament never draws a hexagram`() {
        for (chapter in 1..114) {
            for (ayahCount in intArrayOf(3, 6, 11, 88, 286)) {
                val ornament = generateChapterOrnament(chapterOrnamentSeed(chapter, ayahCount))
                for (s in ornament.rosette.strokes + ornament.field.strokes) {
                    assertTrue(
                        "closed triangle found (chapter $chapter, $ayahCount ayahs)",
                        !(s.closed && s.points.size == 3),
                    )
                }
            }
        }
    }
}
