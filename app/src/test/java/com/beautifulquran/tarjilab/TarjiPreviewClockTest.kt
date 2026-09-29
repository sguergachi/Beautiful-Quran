package com.beautifulquran.tarjilab

import org.junit.Assert.assertEquals
import org.junit.Test

class TarjiPreviewClockTest {
    @Test fun `nonzero seek starts at chosen sample rather than total frames played`() {
        val clock = TarjiPreviewClock(4_000, 0, 2_000, 8_000)
        assertEquals(4_000, clock.frameAt(0))
        assertEquals(5_000, clock.frameAt(1_000))
        assertEquals(2_000, clock.frameAt(4_000))
        assertEquals(2_500, clock.frameAt(10_500))
    }

    @Test fun `existing counter is rebased after seeking`() {
        val clock = TarjiPreviewClock(6_000, 32_000, 2_000, 8_000)
        assertEquals(6_000, clock.frameAt(32_000))
        assertEquals(2_000, clock.frameAt(34_000))
    }

    @Test fun `unsigned playback counter can wrap without jumping`() {
        val clock = TarjiPreviewClock(4_000, -100, 2_000, 8_000)
        assertEquals(4_150, clock.frameAt(50))
    }

    @Test fun `stalled audio does not advance and speed needs no second clock`() {
        val clock = TarjiPreviewClock(0, 0, 0, 8_000)
        repeat(10) { assertEquals(0, clock.frameAt(0)) }
        assertEquals(2_000, clock.frameAt(2_000))
        assertEquals(4_000, clock.frameAt(4_000))
        assertEquals(0, clock.frameAt(8_000))
    }
}
