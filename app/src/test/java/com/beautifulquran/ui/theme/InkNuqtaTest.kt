package com.beautifulquran.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class InkNuqtaTest {

    private val p = ShippedNuqtaParams
    private val clock = (0..100).map { it / 100f }
    private val fingers = nuqtaFingers(p.seed)

    @Test
    fun `the front runs out quickly and creeps to a soft stop`() {
        assertEquals(0f, nuqtaFront(0f, p.spreadSharpness), 1e-6f)
        assertEquals(1f, nuqtaFront(1f, p.spreadSharpness), 1e-6f)
        assertTrue("wetting should be quick", nuqtaFront(0.25f, p.spreadSharpness) > 0.3f)
        val lastStretch = 1f - nuqtaFront(0.9f, p.spreadSharpness)
        assertTrue("settle should be soft, moved $lastStretch", lastStretch < 0.04f)
    }

    @Test
    fun `one drop only ever grows, in every direction`() {
        clock.zipWithNext().forEach { (a, b) ->
            val ra = nuqtaDropRadii(a, p, fingers)
            val rb = nuqtaDropRadii(b, p, fingers)
            ra.indices.forEach { i -> assertTrue("direction $i receded at $b", rb[i] >= ra[i] - 1e-4f) }
        }
    }

    @Test
    fun `the edge is ragged mid-spread and whole at the settle`() {
        val mid = nuqtaDropRadii(0.2f, p, fingers)
        val raggedness = (mid.max() - mid.min()) / mid.average().toFloat()
        assertTrue("mid-spread edge should be organic, was $raggedness", raggedness > 0.12f)
        nuqtaDropRadii(1f, p, fingers).forEach { assertEquals(1f, it, 1e-4f) }
    }

    private fun stops(t: Float) = nuqtaInkStops(t, p, nuqtaDropRadii(t, p, fingers).max())

    @Test
    fun `ink is densest where it landed and never forms a ring`() {
        clock.drop(1).forEach { t ->
            val s = stops(t)
            for (k in 1 until NuqtaInkStopCount) {
                assertTrue("offsets must not go back at $t", s[k * 2] >= s[(k - 1) * 2] - 1e-6f)
                assertTrue("density rose outward at $t", s[k * 2 + 1] <= s[(k - 1) * 2 + 1] + 1e-6f)
            }
        }
    }

    @Test
    fun `pigment lands pale and soaks outward to full strength`() {
        assertTrue(stops(0.08f)[1] < p.inkAlpha * 0.8f)
        val coreEarly = stops(0.35f)[2]
        val coreLate = stops(0.7f)[2]
        assertTrue("dense core should soak outward: $coreEarly → $coreLate", coreLate > coreEarly + 0.2f)
        val settled = stops(1f)
        assertEquals(p.inkAlpha, settled[1], 1e-4f)
        assertEquals(p.inkAlpha, settled[5], 1e-4f)
    }

    @Test
    fun `the settled drop fills the whole nuqta at full strength`() {
        val originX = 0.5f + p.originDx / p.sizeDp
        val originY = 0.5f + p.originDy / p.sizeDp
        val farthestCorner = NuqtaCorners.maxOf { (x, y) -> hypot(x - originX, y - originY) }
        val solid = 0.7f * p.reach * (1f - nuqtaFeather(1f, p))
        assertTrue("solid ink $solid must reach corner $farthestCorner", solid >= farthestCorner)
    }

    @Test
    fun `fibre patterns are stable and normalised`() {
        val again = nuqtaFingers(p.seed)
        assertTrue(fingers.contentEquals(again))
        assertEquals(1f, fingers.maxOf { kotlin.math.abs(it) }, 1e-4f)
    }
}
