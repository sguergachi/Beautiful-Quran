package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightClockTest {

    @Test
    fun `forward samples pass through`() {
        val clock = HighlightClock()
        assertEquals(100L, clock.sample("a", 100))
        assertEquals(133L, clock.sample("a", 133))
        assertEquals(166L, clock.sample("a", 166))
    }

    @Test
    fun `small backward step is held at the previous position`() {
        val clock = HighlightClock()
        clock.sample("a", 1000)
        // MediaController extrapolation corrected backward by 40 ms — the
        // jitter that bounced the active word and flickered the ink.
        assertEquals(1000L, clock.sample("a", 960))
    }

    @Test
    fun `clock resumes from the raw position once it moves forward again`() {
        val clock = HighlightClock()
        clock.sample("a", 1000)
        clock.sample("a", 960)
        assertEquals(1005L, clock.sample("a", 1005))
    }

    @Test
    fun `repeated jitter cannot creep the clock backward`() {
        val clock = HighlightClock()
        clock.sample("a", 1000)
        assertEquals(1000L, clock.sample("a", 900))
        assertEquals(1000L, clock.sample("a", 850))
        assertEquals(1000L, clock.sample("a", 990))
    }

    @Test
    fun `a genuine backward seek passes through outside settle`() {
        val clock = HighlightClock(minSettlePolls = 0, stablePollsNeeded = 0)
        clock.sample("a", 5000)
        // Word tap / loop restart: jump well past the jitter threshold.
        assertEquals(1000L, clock.sample("a", 1000))
        assertEquals(1033L, clock.sample("a", 1033))
    }

    @Test
    fun `a key change resets the clock even to an earlier position`() {
        val clock = HighlightClock()
        clock.sample("ayah 1", 45_000)
        // Ayah handoff: the next clip starts near zero.
        assertEquals(50L, clock.sample("ayah 2", 50))
    }

    @Test
    fun `regression exactly at the threshold is a seek outside settle`() {
        val clock = HighlightClock(seekThresholdMs = 250, minSettlePolls = 0, stablePollsNeeded = 0)
        clock.sample("a", 1000)
        assertEquals(750L, clock.sample("a", 750))
    }

    @Test
    fun `regression just under the threshold is jitter`() {
        val clock = HighlightClock(seekThresholdMs = 250, minSettlePolls = 0, stablePollsNeeded = 0)
        clock.sample("a", 1000)
        assertEquals(1000L, clock.sample("a", 751))
    }

    @Test
    fun `acceptNextSample lets a short backward seek through`() {
        val clock = HighlightClock(seekThresholdMs = 250)
        clock.sample("a", 1000)
        // Word tap 100 ms earlier would normally be held as jitter.
        clock.acceptNextSample()
        assertEquals(900L, clock.sample("a", 900))
        // Subsequent small regressions are held again.
        assertEquals(900L, clock.sample("a", 880))
    }

    @Test
    fun `post-seek overshoot then snap-back does not bounce the clock`() {
        // Back-button / previous-ayah: first sample is 0, then the controller
        // briefly reports ~800 ms (word 2–3), then a real ~100 ms, then a
        // correction back. Without settle that lights word 2, starts its wash,
        // then re-enters it.
        val clock = HighlightClock()
        clock.acceptNextSample()
        assertEquals(0L, clock.sample("ayah 4", 0))
        assertEquals(0L, clock.sample("ayah 4", 800)) // overshoot ignored
        assertEquals(100L, clock.sample("ayah 4", 100)) // believable advance
        assertEquals(100L, clock.sample("ayah 4", 40)) // snap-back held
        assertEquals(140L, clock.sample("ayah 4", 140))
    }

    @Test
    fun `settle holds large regressions that would otherwise count as seeks`() {
        val clock = HighlightClock(seekThresholdMs = 250)
        clock.sample("a", 0)
        // Ramp within the settle step cap so we are mid-settle at ~500 ms.
        assertEquals(100L, clock.sample("a", 100))
        assertEquals(200L, clock.sample("a", 200))
        assertEquals(300L, clock.sample("a", 300))
        assertEquals(400L, clock.sample("a", 400))
        assertEquals(500L, clock.sample("a", 500))
        // 400 ms regression is above SEEK_THRESHOLD but still in settle → hold.
        assertEquals(500L, clock.sample("a", 100))
    }

    @Test
    fun `after settle expires a large regression is accepted as a seek`() {
        val clock = HighlightClock(minSettlePolls = 2, stablePollsNeeded = 0, maxSettleStepMs = 10_000)
        clock.sample("a", 5000)
        clock.sample("a", 5033) // settleLeft 1 → 0
        clock.sample("a", 5066) // settle exhausted
        assertEquals(1000L, clock.sample("a", 1000))
    }

    @Test
    fun `a snap-back after the old settle window is still held`() {
        // The 1:7-word-3 replay: the handoff estimate creeps believably
        // (≤100 ms/poll passes the step cap), then corrects back long after
        // the old 12-poll settle had expired — that read as a seek and
        // replayed the word's wash.
        val clock = HighlightClock()
        var clockMs = clock.sample("ayah 7", 0L)
        // ~20 polls of believable creep into word 3's span.
        for (i in 1..20) clockMs = clock.sample("ayah 7", i * 90L)
        assertEquals(1800L, clockMs)
        // The estimate snaps back to the true position — deep into the old
        // window's past, but inside the longer one: held, not a "seek".
        assertEquals(1800L, clock.sample("ayah 7", 300L))
    }

    @Test
    fun `a big regression passes once the clock has converged`() {
        val clock = HighlightClock()
        var clockMs = clock.sample("a", 0L)
        // Believable playback: converges, minimum window passes.
        for (i in 1..60) clockMs = clock.sample("a", i * 33L)
        assertEquals(1980L, clockMs)
        assertEquals(300L, clock.sample("a", 300L))
    }

    @Test
    fun `settle ends early once the estimate tracks realtime`() {
        // Normal playback right after a handoff: ~33 ms steps are believable,
        // so the minimum window applies and the guard lifts at poll 12 — a
        // genuine seek after that must pass immediately.
        val clock = HighlightClock()
        var clockMs = clock.sample("ayah 7", 0L)
        for (i in 1..12) clockMs = clock.sample("ayah 7", i * 33L)
        assertEquals(396L, clockMs)
        assertEquals(100L, clock.sample("ayah 7", 100L))
    }
}
