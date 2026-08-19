package com.beautifulquran.ui.theme.ornament

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

class CoverMonogramTest {

    private val mark = CoverMonogram

    @Test
    fun `no enclosing circle — every stroke is an open path`() {
        assertTrue(mark.strokes.isNotEmpty())
        for (s in mark.strokes) {
            assertFalse("cover mark must not close into a ring: $s", s.closed)
        }
        assertTrue(mark.dots.isEmpty())
    }

    @Test
    fun `crescent is two offset open arcs, not concentric rings`() {
        val arcs = mark.strokes.filter { it.points.size >= 20 }
        assertEquals("two crescent arcs", 2, arcs.size)
        val c0 = arcCenter(arcs[0])!!
        val c1 = arcCenter(arcs[1])!!
        val offset = hypot(c0.x - c1.x, c0.y - c1.y)
        assertTrue("crescent centres must be offset, not concentric: $offset", offset > 0.02)
        for (arc in arcs) {
            val sweep = arcSweep(arc)
            assertTrue("arc must not be a full circle (sweep=$sweep)", sweep < 2.0 * PI - 0.4)
            assertTrue("arc must cover the left of the moon (sweep=$sweep)", sweep > PI)
        }
    }

    @Test
    fun `geometry stays in the unit box`() {
        for (s in mark.strokes) {
            assertTrue(s.points.size >= 2)
            assertTrue(s.birth >= 0.0 && s.birth + s.span <= 1.0001)
            for (p in s.points) {
                assertTrue("point out of unit box: $p", p.x in -0.001..1.001)
                assertTrue("point out of unit box: $p", p.y in -0.001..1.001)
            }
        }
    }

    @Test
    fun `allah sits in the opening — right of the crescent's inner centre`() {
        val inner = mark.strokes.filter { it.points.size >= 20 }.minBy { it.points.size }
        val c = arcCenter(inner)!!
        val lettering = mark.strokes.filter { it.points.size < 20 }
        assertTrue(lettering.size >= 4)
        val midX = lettering.flatMap { it.points }.map { it.x }.average()
        assertTrue("الله should sit in the crescent's opening (right), midX=$midX c.x=${c.x}", midX > c.x)
    }
}
