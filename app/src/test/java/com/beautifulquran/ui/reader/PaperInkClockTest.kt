package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperInkClockTest {
    @Test fun pauseFreezesStrokeAndDriesInk() {
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
