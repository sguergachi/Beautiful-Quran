package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperInkClockTest {
    @Test fun aRepeatedWordReceivesTheSameWetAndDryCycleAsItsFirstPass() {
        val first = PaperInkClock(100, 1100, 1000)
        val repeated = PaperInkClock(100, 1100, 1000)
        // Complete and dry the previous occurrence before rewinding for repeat.
        repeated.update(600, true, 0)
        repeated.update(1100, true, 500)
        repeated.update(1100, true, 1500)
        assertEquals(0f, repeated.wet)
        for (time in 0L..2200L step 20) {
            first.update(time, true, time)
            repeated.update(time, true, 2000 + time)
            assertEquals(first.progress, repeated.progress)
            assertEquals(first.wet, repeated.wet)
        }
    }

    @Test fun anUpcomingWordReportsHowFarAheadItIsWithoutApplyingInk() {
        val clock = PaperInkClock(1000, 2000, 1000)
        clock.update(0, true, 0)
        assertEquals(-1f, clock.ahead)
        clock.update(850, true, 850)
        assertEquals(-.15f, clock.ahead, 1e-6f)
        assertEquals(0f, clock.progress)
        assertEquals(0f, clock.wet)
        assertTrue(!clock.applying)
        clock.update(1500, true, 1500)
        assertEquals(0f, clock.ahead)
        assertEquals(.5f, clock.progress)
    }

    @Test fun inkApplicationStopsOnPauseAndAtWordHandoff() {
        val clock = PaperInkClock(100, 1100, 1000)
        clock.update(0, true, 0)
        assertTrue(!clock.applying)
        clock.update(100, true, 100)
        assertTrue(clock.applying)
        clock.update(600, false, 600)
        assertTrue(!clock.applying)
        assertEquals(1f, clock.wet)
        clock.update(600, true, 700)
        assertTrue(clock.applying)
        clock.update(1100, true, 1200)
        assertTrue(!clock.applying)
    }

    @Test fun pauseFreezesFillAndDriesInk() {
        val clock = PaperInkClock(100, 1100, 1000)
        clock.update(600, true, 0)
        assertEquals(.5f, clock.progress)
        assertEquals(1f, clock.wet)
        clock.update(600, false, 10)
        clock.update(600, false, 510)
        assertEquals(.5f, clock.progress)
        assertTrue(clock.wet in .1f.. .9f)
        clock.update(600, false, 1010)
        assertEquals(0f, clock.wet)
        clock.update(600, true, 1020)
        assertEquals(.5f, clock.progress)
        assertEquals(1f, clock.wet)
    }

    @Test fun handoffCompletesPreviousWordBeforeNextStarts() {
        val clocks = listOf(PaperInkClock(100, 700, 1000), PaperInkClock(700, 1600, 1000))
        for (position in 0L..1800L) {
            clocks.forEach { it.update(position, true, position) }
            if (clocks[1].progress > 0f) assertEquals(1f, clocks[0].progress)
        }
        assertEquals(1f, clocks[0].progress)
        assertEquals(1f, clocks[1].progress)
    }

    @Test fun backwardSeekClearsFutureInkAndRestartsItsWetPhase() {
        val clock = PaperInkClock(100, 1100, 1000)
        clock.update(600, true, 0)
        clock.update(0, true, 10)
        assertEquals(0f, clock.progress)
        assertEquals(0f, clock.wet)
        clock.update(100, true, 20)
        assertEquals(0f, clock.progress)
        assertEquals(1f, clock.wet)
    }
}
