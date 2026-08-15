package com.beautifulquran.ui.theme.ornament

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class OrnamentGeneratorTest {

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
    fun `star-and-cross field kisses at every cell-edge midpoint`() {
        // The star's four cardinal points must land exactly on the cell-edge
        // midpoints, so when the cell tiles each star meets its orthogonal
        // neighbour tip-to-tip — the seam-free continuity of the weave.
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
                assertTrue("seed $seed: no star point at edge midpoint $m", hit)
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

    @Test
    fun `variety - a seed sample uses every fold, field style and border`() {
        val folds = HashSet<Int>()
        val fieldStyles = HashSet<Int>()
        val borderShapes = HashSet<Int>()
        for (seed in 0 until 200) {
            val o = generateCoverOrnament(seed)
            folds.add(o.medallion.fold)
            // Star style (khatam = 2 strokes vs octagram = 1) and centre-mark
            // presence both shift the field's stroke count.
            fieldStyles.add(o.field.strokes.size)
            borderShapes.add(o.border.strokes.size * 31 + o.border.dots.size)
        }
        assertEquals(setOf(8, 10, 12, 16), folds)
        assertTrue("expected several field styles, got $fieldStyles", fieldStyles.size >= 3)
        assertTrue("expected several border grammars, got $borderShapes", borderShapes.size >= 3)
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
