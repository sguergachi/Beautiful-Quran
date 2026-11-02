package com.beautifulquran.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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

    @Test
    fun `a tap carries the ring on to the lesson, never back`() {
        assertEquals(MARK_TAP_TEASE, markTapTeasePeak(0f), 0.0001f)
        assertEquals(MARK_TAP_TEASE, markTapTeasePeak(0.1f), 0.0001f)
        // A hold let go late keeps what it earned.
        assertEquals(0.8f, markTapTeasePeak(0.8f), 0.0001f)
        // The lesson's ring is visibly partial: some rays lit, some still stubs.
        val lit = shamsaRayLook(0, SHAMSA_RAY_COUNT, charge = MARK_TAP_TEASE, burst = 0f)
        val unlit = shamsaRayLook(SHAMSA_RAY_COUNT - 1, SHAMSA_RAY_COUNT, charge = MARK_TAP_TEASE, burst = 0f)
        assertEquals(1f, lit.length, 0.05f)
        assertEquals(RAY_STUB, unlit.length, 0.001f)
    }

    @Test
    fun `the lesson's pulse points at the unlit rays only`() {
        val charge = MARK_TAP_TEASE
        val litRest = shamsaRayLook(0, SHAMSA_RAY_COUNT, charge, burst = 0f, hint = 0f)
        val litPulse = shamsaRayLook(0, SHAMSA_RAY_COUNT, charge, burst = 0f, hint = 1f)
        val gapRest = shamsaRayLook(SHAMSA_RAY_COUNT - 1, SHAMSA_RAY_COUNT, charge, burst = 0f, hint = 0f)
        val gapPulse = shamsaRayLook(SHAMSA_RAY_COUNT - 1, SHAMSA_RAY_COUNT, charge, burst = 0f, hint = 1f)
        assertTrue(gapPulse.alpha > gapRest.alpha + 0.2f)
        assertTrue(gapPulse.length > gapRest.length)
        assertEquals(litRest.alpha, litPulse.alpha, 0.02f)
        // Even pulsed, an unlit ray stays lighter than a lit one: it is the gap.
        assertTrue(gapPulse.alpha < litRest.alpha)
    }

    @Test
    fun `the ring grows from the cups to stand clear around the finger`() {
        val finger = Offset(130f, 40f)
        val atPress = expandedFrame(frame, finger, expandedRadius = 34f, expansion = 0f)
        assertEquals(frame, atPress)
        val grown = expandedFrame(frame, finger, expandedRadius = 34f, expansion = 1f)
        assertEquals(finger.x, grown.cx, 0.001f)
        assertEquals(finger.y, grown.cy, 0.001f)
        assertEquals(34f, grown.rx, 0.001f)
        assertEquals(34f, grown.ry, 0.001f)
        // No finger known: it grows in place.
        val inPlace = expandedFrame(frame, Offset.Unspecified, expandedRadius = 34f, expansion = 1f)
        assertEquals(frame.cx, inPlace.cx, 0.001f)
    }

    @Test
    fun `expansion shows early in the hold and follows the charge back down`() {
        assertEquals(0f, shamsaExpansion(0f), 0.0001f)
        assertEquals(1f, shamsaExpansion(0.45f), 0.0001f)
        assertEquals(1f, shamsaExpansion(1f), 0.0001f)
        assertTrue(shamsaExpansion(0.15f) > 0.5f)
        // The tap lesson's peak reaches the full sunburst: a real power-up.
        assertEquals(1f, shamsaExpansion(MARK_TAP_TEASE), 0.0001f)
    }

    @Test
    fun `the lesson's surge swells every ray together`() {
        repeat(SHAMSA_RAY_COUNT) { i ->
            val rest = shamsaRayLook(i, SHAMSA_RAY_COUNT, charge = MARK_TAP_TEASE, burst = 0f)
            val surged = shamsaRayLook(i, SHAMSA_RAY_COUNT, charge = MARK_TAP_TEASE, burst = 0f, surge = 1f)
            assertTrue(surged.length > rest.length)
            assertTrue(surged.lift > rest.lift)
            assertTrue(surged.alpha >= rest.alpha)
            assertTrue(surged.alpha <= 1f)
        }
    }

    @Test
    fun `the gilt leans on the gold stop that stands off the page`() {
        val gold = Color(0xFFA8831D)
        val bright = Color(0xFFC9AE67)
        val deep = Color(0xFF7A5B05)
        // Cream: the deep gold carries the ray, warming to gold at the tip.
        val cream = markHoldGold(gold, bright, deep, darkPage = false)
        assertEquals(deep, cream.base)
        assertEquals(gold, cream.tip)
        assertTrue(cream.base.luminance() < gold.luminance())
        // Night: the gold carries it, kindling to bright gold at the tip.
        val night = markHoldGold(gold, bright, deep, darkPage = true)
        assertEquals(gold, night.base)
        assertEquals(bright, night.tip)
        assertTrue(night.tip.luminance() > gold.luminance())
    }

    private val target = MarkHoldTarget(
        bounds = Rect(0f, 0f, 40f, 30f),
        baseline = 25f,
        finger = Offset(20f, 15f),
        rtl = false,
        onHold = {},
    )

    /** Runs [events] through the rules from idle, returning each step's action and the final state. */
    private fun play(vararg events: MarkPressEvent, from: HoldState = HoldState()): Pair<List<HoldAction>, HoldState> {
        var state = from
        val actions = events.map { event ->
            val (next, action) = nextHoldStep(state, event)
            state = next
            action
        }
        return actions to state
    }

    @Test
    fun `a press charges and a lift while charging is a tap that gets the lesson`() {
        val (actions, state) = play(MarkPressEvent.Down(target), MarkPressEvent.Released)
        assertEquals(listOf(HoldAction.StartCharge, HoldAction.StartLesson), actions)
        assertEquals(HoldPhase.Lesson, state.phase)
    }

    @Test
    fun `a slide while charging retracts with no lesson`() {
        val (actions, state) = play(MarkPressEvent.Down(target), MarkPressEvent.Cancelled)
        assertEquals(listOf(HoldAction.StartCharge, HoldAction.Retract), actions)
        assertEquals(HoldPhase.Retracting, state.phase)
    }

    @Test
    fun `nothing a finger does after the pop takes it back`() {
        val popped = HoldState(HoldPhase.Popped)
        // The lift or slide that ends the gesture that popped is ignored.
        assertEquals(HoldAction.Ignore, nextHoldStep(popped, MarkPressEvent.Released).second)
        assertEquals(HoldAction.Ignore, nextHoldStep(popped, MarkPressEvent.Cancelled).second)
        assertEquals(HoldPhase.Popped, nextHoldStep(popped, MarkPressEvent.Cancelled).first.phase)
    }

    @Test
    fun `a press during the burst is ignored together with its own lift`() {
        val (actions, state) = play(
            MarkPressEvent.Down(target),
            MarkPressEvent.Released,
            from = HoldState(HoldPhase.Popped),
        )
        assertEquals(listOf(HoldAction.Ignore, HoldAction.Ignore), actions)
        assertEquals(false, state.ignoringPress)
        // The next press after that is a fresh one.
        assertEquals(HoldAction.StartCharge, nextHoldStep(state.copy(phase = HoldPhase.Idle), MarkPressEvent.Down(target)).second)
    }

    @Test
    fun `pressing again during a lesson or a retract carries on charging`() {
        assertEquals(HoldAction.StartCharge, nextHoldStep(HoldState(HoldPhase.Lesson), MarkPressEvent.Down(target)).second)
        assertEquals(HoldAction.StartCharge, nextHoldStep(HoldState(HoldPhase.Retracting), MarkPressEvent.Down(target)).second)
    }

    @Test
    fun `a stray lift with no press in progress does nothing`() {
        assertEquals(HoldAction.Ignore, nextHoldStep(HoldState(), MarkPressEvent.Released).second)
        assertEquals(HoldAction.Ignore, nextHoldStep(HoldState(HoldPhase.Lesson), MarkPressEvent.Released).second)
    }
}
