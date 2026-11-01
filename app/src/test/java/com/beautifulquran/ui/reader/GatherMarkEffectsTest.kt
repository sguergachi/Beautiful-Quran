package com.beautifulquran.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.beautifulquran.data.GatherMarkEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class GatherMarkEffectsTest {
    private val frame = MarkFrame(cx = 100f, cy = 50f, rx = 20f, ry = 15f)

    /** Normalised distance from the mark's centre: 1 = on its ellipse. */
    private fun ellipseRadius(p: Offset): Float =
        hypot((p.x - frame.cx) / frame.rx, (p.y - frame.cy) / frame.ry)

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
    fun `persisted ordinals keep the shipped brush circle first`() {
        // Settings stores this enum by ordinal: reordering would swap readers' choice.
        assertEquals(GatherMarkEffect.BRUSH_CIRCLE, GatherMarkEffect.entries.first())
        assertEquals(6, GatherMarkEffect.entries.size)
    }

    @Test
    fun `ring ornaments never take a six-fold count`() {
        // docs: no Star-of-David-reading geometry anywhere in the app.
        assertNotEquals(0, SHAMSA_RAY_COUNT % 6)
        assertNotEquals(0, PEARL_RING_COUNT % 6)
    }

    @Test
    fun `pearls ring the mark outside its cups, starting at the top`() {
        val centres = pearlRingCentres(frame, gap = 4f, rtl = false)
        assertEquals(PEARL_RING_COUNT, centres.size)
        centres.forEach { assertTrue(ellipseRadius(it) > 1.05f) }
        assertEquals(frame.cx, centres.first().x, 0.001f)
        assertTrue(centres.first().y < frame.cy)
    }

    @Test
    fun `pearl sequence runs the way the verse reads`() {
        val ltr = pearlRingCentres(frame, gap = 4f, rtl = false)
        val rtl = pearlRingCentres(frame, gap = 4f, rtl = true)
        // Second bead: right of top for English, left of top for Arabic.
        assertTrue(ltr[1].x > frame.cx)
        assertTrue(rtl[1].x < frame.cx)
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
    fun `swash sits under the mark and is written from the line's start`() {
        fun swash(rtl: Boolean, progress: Float = 1f) = qalamSwashOutline(
            frame = frame, progress = progress, rtl = rtl, gap = 3f, reach = 6f,
            peakHalf = 1.6f, sag = 1.4f, lift = 4.5f,
        )
        val ltr = swash(rtl = false)!!
        val rtl = swash(rtl = true)!!
        // Starts at the left for English, the right for Arabic.
        assertTrue(ltr.top.first().x < frame.cx && ltr.top.last().x > frame.cx)
        assertTrue(rtl.top.first().x > frame.cx && rtl.top.last().x < frame.cx)
        // Never climbs into the cups, even at the flick.
        (ltr.top + ltr.bottom).forEach { assertTrue(it.y > frame.cy + frame.ry - 4.5f) }
        // A half-written stroke stops halfway across.
        val half = swash(rtl = false, progress = 0.5f)!!
        assertEquals(frame.cx, half.top.last().x, 1.5f)
        assertNull(swash(rtl = false, progress = 0f))
    }

    @Test
    fun `ink drop overshoots its edge, then dries back to rest`() {
        val landed = inkDropPhase(0.4f)
        assertTrue(landed.spread > 1f)
        assertEquals(1f, landed.wet, 0.001f)
        val dry = inkDropPhase(1f)
        assertEquals(1f, dry.spread, 0.001f)
        assertEquals(0f, dry.wet, 0.001f)
        assertEquals(0f, inkDropPhase(0f).spread, 0.001f)
    }

    @Test
    fun `taj crown floats over the cups and is mirror-symmetric`() {
        val dpPx = 1f
        val foot = tajFoot(frame, dpPx)
        assertEquals(frame.cx, foot.x, 0.001f)
        assertTrue(foot.y < frame.cy - frame.ry)
        val (right, _) = tajVolute(frame, dpPx, right = true)
        val (left, _) = tajVolute(frame, dpPx, right = false)
        right.zip(left).forEach { (r, l) ->
            assertEquals(frame.cx - r.x, l.x - frame.cx, 0.01f)
            assertEquals(r.y, l.y, 0.01f)
        }
    }

    @Test
    fun `taj volutes curl up and back inward, never down into the cups`() {
        val dpPx = 1f
        val (points, tangents) = tajVolute(frame, dpPx, right = true)
        val foot = tajFoot(frame, dpPx)
        // Every point stays above the cups' top.
        points.forEach { assertTrue(it.y < frame.cy - frame.ry) }
        // The curl rises above its foot and, partway round, heads back toward
        // the centre (its last turn swings out again as the spiral closes).
        assertTrue(points.minOf { it.y } < foot.y)
        assertTrue(tangents.any { it.x < -0.5f })
        // And it stays on its own side of the drop.
        points.forEach { assertTrue(it.x > frame.cx) }
    }

    @Test
    fun `staggered sequence starts in order and ends together`() {
        assertEquals(0f, staggered(0.1f, 9, 10, spread = 0.6f), 0.001f)
        assertTrue(staggered(0.1f, 0, 10, spread = 0.6f) > 0f)
        assertEquals(1f, staggered(1f, 9, 10, spread = 0.6f), 0.001f)
        assertEquals(1f, easeOutBack(1f), 0.001f)
        assertTrue(easeOutBack(0.7f) > 1f)
    }

    @Test
    fun `nothing paints until the finger lands or the verse is gathered`() {
        assertNull(gatherMarkFrameState(paint = 0f, presence = 0f, press = 0f, pop = 1f))
    }

    @Test
    fun `a press lays only a bead of ink, pressed in`() {
        val held = gatherMarkFrameState(paint = 0f, presence = 0f, press = 1f, pop = 1f)!!
        assertEquals(ANTICIPATION, held.paint, 0.001f)
        assertEquals(1f, held.alpha, 0.001f)
        assertTrue(held.scale < 1f)
        // Halfway into the press, halfway to the bead.
        val landing = gatherMarkFrameState(paint = 0f, presence = 0f, press = 0.5f, pop = 1f)!!
        assertEquals(ANTICIPATION / 2f, landing.paint, 0.001f)
    }

    @Test
    fun `release carries on from the press without a jump`() {
        // rememberGatherMarkInk seeds paint and pop from the press at release.
        val press = 0.8f
        val held = gatherMarkFrameState(paint = 0f, presence = 0f, press = press, pop = 1f)!!
        val released = gatherMarkFrameState(
            paint = ANTICIPATION * press,
            presence = 1f,
            press = 0f,
            pop = 1f - PRESS_SQUASH * press,
        )!!
        assertEquals(held.paint, released.paint, 0.001f)
        assertEquals(held.scale, released.scale, 0.001f)
    }

    @Test
    fun `pressing a gathered mark lifts its ink before it goes`() {
        val resting = gatherMarkFrameState(paint = 1f, presence = 1f, press = 0f, pop = 1f)!!
        val pressed = gatherMarkFrameState(paint = 1f, presence = 1f, press = 1f, pop = 1f)!!
        assertEquals(1f, resting.alpha, 0.001f)
        assertEquals(1f - PRESS_LIFT, pressed.alpha, 0.001f)
        assertEquals(1f, pressed.paint, 0.001f)
    }
}
