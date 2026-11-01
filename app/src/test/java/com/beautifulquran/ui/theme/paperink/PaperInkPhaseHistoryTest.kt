package com.beautifulquran.ui.theme.paperink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperInkPhaseHistoryTest {
    @Test
    fun `a word drawn at the same phase on consecutive frames is at rest`() {
        val history = PaperInkPhaseHistory<String, Float>()
        assertFalse(history.seen(1, "And", 0.25f))
        assertTrue(history.seen(2, "And", 0.25f))
        assertTrue(history.seen(3, "And", 0.25f))
    }

    @Test
    fun `a scrolling word lands on a new phase every frame and is moving`() {
        val history = PaperInkPhaseHistory<String, Float>()
        var phase = 0.1f
        for (frame in 1L..20L) {
            assertFalse(history.seen(frame, "verse", phase))
            phase = (phase + 0.37f) % 1f
        }
        // It stops: one frame later it is at rest and gets its exact capture.
        assertFalse(history.seen(21, "verse", 0.5f))
        assertTrue(history.seen(22, "verse", 0.5f))
    }

    @Test
    fun `the same word printed twice is two placements, both at rest`() {
        val history = PaperInkPhaseHistory<String, Float>()
        history.seen(1, "the", 0.2f)
        history.seen(1, "the", 0.7f)
        assertTrue(history.seen(2, "the", 0.2f))
        assertTrue(history.seen(2, "the", 0.7f))
    }
}
