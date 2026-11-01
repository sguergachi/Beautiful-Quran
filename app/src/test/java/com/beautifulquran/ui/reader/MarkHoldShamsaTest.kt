package com.beautifulquran.ui.reader

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class MarkHoldShamsaTest {
    private val frame = MarkFrame(cx = 100f, cy = 50f, rx = 20f, ry = 15f)

    @Test
    fun `mark frame sits on the cups above the baseline, not the line box centre`() {
        // A 40 px wide mark in a 120 px tall Hafs line box, baseline at 90.
        val f = markFrame(Rect(0f, 0f, 40f, 120f), baseline = 90f, minRy = 9f)
        assertEquals(20f, f.rx, 0.001f)
        assertEquals(40f * CUP_HEIGHT_PER_WIDTH / 2f, f.ry, 0.001f)
        assertEquals(90f - 40f * CUP_CENTRE_ABOVE_BASELINE_PER_WIDTH, f.cy, 0.001f)
        // Lower than the box centre: the leading sits above the ornament.
        assertTrue(f.cy > 60f)
    }

    @Test
    fun `without a baseline the frame falls back to the box, its height led by width`() {
        val f = markFrame(Rect(0f, 0f, 40f, 120f), baseline = null, minRy = 9f)
        assertEquals(20f * 0.78f, f.ry, 0.001f)
        assertEquals(60f, f.cy, 0.001f)
    }

    @Test
    fun `the shamsa never takes a six-fold count`() {
        // docs: no Star-of-David-reading geometry anywhere in the app.
        assertNotEquals(0, SHAMSA_RAY_COUNT % 6)
    }

    @Test
    fun `shamsa rays stand outward and alternate long and short`() {
        val rays = shamsaRays(frame, gap = 3f, longRay = 6f, shortRay = 3f, halfWidth = 1f, rtl = false)
        assertEquals(SHAMSA_RAY_COUNT, rays.size)
        rays.forEachIndexed { i, ray ->
            val outward = (ray.base.x - frame.cx) * ray.dirX + (ray.base.y - frame.cy) * ray.dirY
            assertTrue("ray $i points inward", outward > 0f)
            assertEquals(1f, hypot(ray.dirX, ray.dirY), 0.001f)
            assertEquals(if (i % 2 == 0) 6f else 3f, ray.length, 0.001f)
        }
    }

    @Test
    fun `rays are numbered from the top in the verse's reading direction`() {
        val ltr = shamsaRays(frame, gap = 3f, longRay = 6f, shortRay = 3f, halfWidth = 1f, rtl = false)
        val rtl = shamsaRays(frame, gap = 3f, longRay = 6f, shortRay = 3f, halfWidth = 1f, rtl = true)
        assertEquals(frame.cx, ltr.first().base.x, 0.001f)
        assertTrue(ltr.first().base.y < frame.cy)
        // Second ray: right of top for English, left of top for Arabic.
        assertTrue(ltr[1].base.x > frame.cx)
        assertTrue(rtl[1].base.x < frame.cx)
    }

    @Test
    fun `nothing shows before the finger lands`() {
        repeat(SHAMSA_RAY_COUNT) { i ->
            assertEquals(0f, shamsaRayLook(i, SHAMSA_RAY_COUNT, charge = 0f, burst = 0f).alpha, 0.0001f)
        }
    }

    @Test
    fun `a fresh press rings the mark with faint stubs`() {
        repeat(SHAMSA_RAY_COUNT) { i ->
            val look = shamsaRayLook(i, SHAMSA_RAY_COUNT, charge = 0.06f, burst = 0f)
            assertTrue("ray $i should already be visible", look.alpha > 0.2f)
            // The sweep has just reached the first ray; the rest wait as stubs.
            if (i > 0) assertTrue("ray $i should still be a stub", look.length < 0.5f)
        }
    }

    @Test
    fun `holding lights the rays in turn, then the whole ring`() {
        val first = shamsaRayLook(0, SHAMSA_RAY_COUNT, charge = 0.5f, burst = 0f)
        val last = shamsaRayLook(SHAMSA_RAY_COUNT - 1, SHAMSA_RAY_COUNT, charge = 0.5f, burst = 0f)
        assertTrue(first.length > last.length)
        assertTrue(first.alpha > last.alpha)
        repeat(SHAMSA_RAY_COUNT) { i ->
            val full = shamsaRayLook(i, SHAMSA_RAY_COUNT, charge = 1f, burst = 0f)
            assertEquals(1f, full.length, 0.001f)
            assertEquals(1f, full.width, 0.001f)
            assertEquals(CHARGE_LIFT_DP, full.lift, 0.001f)
        }
    }

    @Test
    fun `the hold accentuates - rays only grow and darken as it fills`() {
        var previous = shamsaRayLook(3, SHAMSA_RAY_COUNT, charge = 0.06f, burst = 0f)
        for (step in 2..20) {
            val look = shamsaRayLook(3, SHAMSA_RAY_COUNT, charge = step / 20f, burst = 0f)
            assertTrue(look.length >= previous.length - 1e-4f)
            assertTrue(look.alpha >= previous.alpha - 1e-4f)
            assertTrue(look.lift >= previous.lift - 1e-4f)
            previous = look
        }
    }

    @Test
    fun `the pop flies the rays out and leaves nothing on the mark`() {
        val full = shamsaRayLook(0, SHAMSA_RAY_COUNT, charge = 1f, burst = 0f)
        val mid = shamsaRayLook(0, SHAMSA_RAY_COUNT, charge = 1f, burst = 0.5f)
        val done = shamsaRayLook(0, SHAMSA_RAY_COUNT, charge = 1f, burst = 1f)
        assertTrue(mid.lift > full.lift)
        assertTrue(mid.length > full.length)
        assertTrue(mid.width < full.width)
        assertTrue(mid.alpha < full.alpha)
        assertEquals(0f, done.alpha, 0.0001f)
        assertEquals(CHARGE_LIFT_DP + BURST_LIFT_DP, done.lift, 0.001f)
    }

    @Test
    fun `staggered sequence starts in order and ends together`() {
        assertEquals(0f, staggered(0.1f, 9, 10, spread = 0.6f), 0.001f)
        assertTrue(staggered(0.1f, 0, 10, spread = 0.6f) > 0f)
        assertEquals(1f, staggered(1f, 9, 10, spread = 0.6f), 0.001f)
    }
}
