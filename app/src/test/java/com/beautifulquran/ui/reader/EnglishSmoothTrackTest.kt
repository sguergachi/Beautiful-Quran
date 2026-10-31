package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishSmoothTrackTest {
    // A short Arabic word with a long gloss between two long words with short ones.
    private val segments = listOf(Segment(1, 0, 900), Segment(2, 900, 1100), Segment(3, 1100, 2000),
        Segment(4, 3000, 3800))
    private val lengths = intArrayOf(3, 12, 3, 5)

    private fun clock(sigma: Float) = requireNotNull(
        EnglishScrollClock.of(lengths, intArrayOf(1, 2, 3, 4), { null }, segments, sigma) { null })

    private fun position(clock: EnglishScrollClock, t: Long) =
        lengths.indices.sumOf { (clock.progress(it, t)!! * lengths[it]).toDouble() }

    private fun boundaryJump(clock: EnglishScrollClock, t: Long): Double {
        val before = position(clock, t) - position(clock, t - 60)
        val after = position(clock, t + 60) - position(clock, t)
        return maxOf(before, after) / minOf(before, after)
    }

    @Test
    fun `each gloss is anchored to its Arabic word`() {
        val exact = clock(0f)
        assertEquals(0f, exact.progress(1, 900)!!, 0.01f)
        assertEquals(1f, exact.progress(1, 1100)!!, 0.01f)
        assertEquals(0.5f, exact.progress(2, 1550)!!, 0.02f)
        // A real pause finishes at the voiced end and holds to the next onset.
        assertEquals(1f, exact.progress(2, 2000)!!, 0.01f)
        assertEquals(0f, exact.progress(3, 2990)!!, 0.01f)
    }

    @Test
    fun `smoothing blends the speed across a word boundary without leaving the word`() {
        val exact = clock(0f)
        val smooth = clock(100f)
        assertTrue(boundaryJump(exact, 900) > 10)
        assertTrue("jump ${boundaryJump(smooth, 900)}", boundaryJump(smooth, 900) < 2.5)
        // The middle of each word stays on its word, and a held pause stays held.
        assertEquals(exact.progress(2, 1550)!!, smooth.progress(2, 1550)!!, 0.05f)
        assertEquals(position(smooth, 2400), position(smooth, 2600), 0.01)
        var previous = 0.0
        for (t in -500L..4300L step 3) {
            val at = position(smooth, t)
            assertTrue(at >= previous - 1e-6)
            previous = at
        }
        assertEquals(lengths.sum().toDouble(), position(smooth, 4500), 1e-3)
    }

    @Test
    fun `merged glosses share their Arabic words and a re-say holds`() {
        val merged = requireNotNull(EnglishScrollClock.of(intArrayOf(6, 0, 4), intArrayOf(1, 2, 3),
            { if (it == 1) 0 else null }, listOf(Segment(1, 0, 500), Segment(2, 500, 1000), Segment(3, 1000, 1500)), 4f) { null })
        assertEquals(0.5f, merged.progress(0, 500)!!, 0.02f)
        assertNull(merged.progress(1, 500))
        val resaid = requireNotNull(EnglishScrollClock.of(intArrayOf(4, 4), intArrayOf(1, 2), { null },
            listOf(Segment(1, 0, 500), Segment(1, 500, 1000), Segment(2, 1000, 1500)), 4f) { null })
        assertEquals(1f, resaid.progress(0, 600)!!, 0.01f)
        assertEquals(0f, resaid.progress(1, 900)!!, 0.01f)
    }

    @Test
    fun `inverse reads the first arrival or the end of a hold`() {
        val track = requireNotNull(EnglishSmoothTrack.through(listOf(0L to 0f, 1000L to 1f, 3000L to 1f, 4000L to 2f), 4f))
        assertEquals(500.0, track.mediaAt(0.5f).toDouble(), 8.0)
        assertTrue(track.mediaAt(0.99f) in 980L..1010L)
        assertTrue(track.mediaAt(1f, afterGap = true) >= 2980)
        assertNull(EnglishSmoothTrack.of(0, 1000, 1f) { 0f })
    }
}
