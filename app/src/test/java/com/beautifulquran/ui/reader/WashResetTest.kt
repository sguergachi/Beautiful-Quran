package com.beautifulquran.ui.reader

import com.beautifulquran.domain.HighlightClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The no-reset law, pinned. A word's bloom must never visibly restart: the
 * ink reveals once, and nothing — not a handoff estimate bounce, not a
 * mid-word retune, not the shimmer — may replay, rewind, or re-veil it while
 * it is showing. Every rule here is a regression that actually shipped.
 */
class WashResetTest {

    // --- The sweep entry decision ---------------------------------------

    @Test
    fun `an active word with an unchanged activation never re-arms`() {
        // Rising edge arms once…
        assertEquals(
            SweepEntryAction.Arm,
            sweepEntryAction(
                wasActive = false,
                previousActivation = 0L,
                active = true,
                activation = 7L,
                hasSweep = true,
            ),
        )
        // …then every later frame of the same activation is Keep — forever.
        repeat(100) {
            assertEquals(
                SweepEntryAction.Keep,
                sweepEntryAction(
                    wasActive = true,
                    previousActivation = 7L,
                    active = true,
                    activation = 7L,
                    hasSweep = true,
                ),
            )
        }
    }

    @Test
    fun `only a rising edge or a genuine seek can arm`() {
        // Inactive never arms (even with a fresh activation lying around).
        assertEquals(
            SweepEntryAction.Clear,
            sweepEntryAction(
                wasActive = true,
                previousActivation = 7L,
                active = false,
                activation = 8L,
                hasSweep = true,
            ),
        )
        // Activation bump while active = the listener sought: arm.
        assertEquals(
            SweepEntryAction.Arm,
            sweepEntryAction(
                wasActive = true,
                previousActivation = 7L,
                active = true,
                activation = 8L,
                hasSweep = true,
            ),
        )
        // No sweep (nothing lit) never arms.
        assertEquals(
            SweepEntryAction.Clear,
            sweepEntryAction(
                wasActive = false,
                previousActivation = 0L,
                active = true,
                activation = 8L,
                hasSweep = false,
            ),
        )
    }

    // --- The residual after handoff --------------------------------------

    @Test
    fun `a residual never rewinds a wash that already moved`() {
        // Mid-wash handoff: resume exactly where the ink is.
        assertEquals(0.62f, residualSweepAnchor(applied = true, currentProgress = 0.62f), 0f)
        assertEquals(0.62f, residualSweepAnchor(applied = false, currentProgress = 0.62f), 0f)
        // Only the untouched idle ceiling may rewind to 0 (it was never shown).
        assertEquals(0f, residualSweepAnchor(applied = false, currentProgress = 1f), 0f)
    }

    @Test
    fun `English prose waits for the preceding wash to finish`() {
        assertTrue(canStartSequentialSweep(predecessorProgress = null))
        assertTrue(canStartSequentialSweep(predecessorProgress = 1f))
        assertFalse(canStartSequentialSweep(predecessorProgress = 0.999f))
    }

    @Test
    fun `continued sweep progress is monotonic as the wash advances`() {
        var prev = 0f
        for (i in 0..100) {
            val p = continuedSweepProgress(progress = i / 100f, start = 0.3f)
            assertTrue("wash moved backward at $i: $prev -> $p", p >= prev)
            prev = p
        }
    }

    // --- The highlight clock ----------------------------------------------

    @Test
    fun `the clock never steps backward through a handoff creep and snap-back`() {
        // The ayah-item handoff that replayed word 2/3: the estimate creeps
        // believably, snaps back late, then recovers at realtime. The clock
        // output must be non-decreasing through the whole sequence — a
        // backward step is what replays the bloom.
        val clock = HighlightClock()
        var prev = clock.sample("ayah 7", 0L)
        val series = ArrayList<Long>()
        for (i in 1..20) series += i * 90L // creep into word 3's span
        series += 300L // the late snap-back
        for (i in 1..60) series += 300L + i * 33L // realtime recovery
        for (raw in series) {
            val c = clock.sample("ayah 7", raw)
            assertTrue("clock stepped backward: $prev -> $c", c >= prev)
            prev = c
        }
    }

    @Test
    fun `the clock never steps backward through a mid-verse seek correction`() {
        // Tap a word mid-verse: the estimate oscillates around the target
        // before converging. No backward clock step, no replay.
        val clock = HighlightClock()
        var prev = clock.sample("ayah 7", 1680L)
        clock.acceptNextSample() // user seek: arm
        prev = clock.sample("ayah 7", 1680L)
        val wobbles = longArrayOf(1900, 1750, 1600, 1500, 1620, 1680, 1713, 1746)
        for (raw in wobbles) {
            val c = clock.sample("ayah 7", raw)
            assertTrue("clock stepped backward: $prev -> $c", c >= prev)
            prev = c
        }
    }

    @Test
    fun `a late correction after false convergence cannot replay the bloom`() {
        val clock = HighlightClock()
        clock.acceptNextSample()
        var prev = clock.sample("ayah 7", 0L)
        // A stale MediaController estimate can look exactly like realtime for
        // long enough to pass the old convergence gate.
        for (i in 1..60) {
            val current = clock.sample("ayah 7", i * 33L)
            assertTrue(current >= prev)
            prev = current
        }
        // Its eventual authoritative correction is not another performance
        // event and therefore cannot move the ink clock backward.
        assertEquals(prev, clock.sample("ayah 7", 100L))
    }
}
