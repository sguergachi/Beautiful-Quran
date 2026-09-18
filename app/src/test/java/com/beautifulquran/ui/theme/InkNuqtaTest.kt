package com.beautifulquran.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkNuqtaTest {

    private val clock = (0..100).map { it / 100f }
    private val layers = with(ShippedNuqtaParams) { listOf(wet, body, pool) }

    @Test
    fun `every layer starts dry and ends fully spread`() {
        layers.forEach { layer ->
            assertEquals("$layer at touch", 0f, nuqtaReach(0f, layer), 1e-4f)
            assertEquals("$layer at settle", 1f, nuqtaReach(1f, layer), 1e-4f)
        }
    }

    @Test
    fun `ink only ever spreads outward, never overshoots`() {
        layers.forEach { layer ->
            clock.zipWithNext().forEach { (a, b) ->
                val ra = nuqtaReach(a, layer)
                val rb = nuqtaReach(b, layer)
                assertTrue("$layer receded at $b", rb >= ra - 1e-4f)
                assertTrue("$layer overshot at $b", rb <= 1f + 1e-4f)
            }
        }
    }

    @Test
    fun `wet edge leads the body, and the body leads the pool`() {
        clock.filter { it > 0f && it < 1f }.forEach { t ->
            val wet = nuqtaReach(t, ShippedNuqtaParams.wet)
            val body = nuqtaReach(t, ShippedNuqtaParams.body)
            val pool = nuqtaReach(t, ShippedNuqtaParams.pool)
            assertTrue("body passed the wet edge at $t", wet >= body - 1e-4f)
            assertTrue("pool passed the body at $t", body >= pool - 1e-4f)
        }
    }

    @Test
    fun `wetting is quick and the settle is soft`() {
        // A third of the way in the edge is already most of the way out…
        assertTrue(nuqtaReach(0.33f, ShippedNuqtaParams.wet) > 0.8f)
        // …while the pool is still creeping into the corners in the last fifth.
        val lateReach = nuqtaReach(1f, ShippedNuqtaParams.pool) - nuqtaReach(0.8f, ShippedNuqtaParams.pool)
        assertTrue("pool should still be settling late, moved $lateReach", lateReach in 0.01f..0.15f)
    }
}
