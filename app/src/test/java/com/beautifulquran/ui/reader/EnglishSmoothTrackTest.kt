package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishSmoothTrackTest {
    // A short Arabic word between two long ones, its gloss at an ordinary reading
    // speed (10 letters a second) but three times its neighbours'. Faster glosses
    // borrow time instead (see the levelling test below).
    private val segments = listOf(Segment(1, 0, 900), Segment(2, 900, 1100), Segment(3, 1100, 2000),
        Segment(4, 3000, 3800))
    private val lengths = intArrayOf(3, 2, 3, 5)

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
        assertTrue(boundaryJump(exact, 900) > 2.5)
        assertTrue("jump ${boundaryJump(smooth, 900)}", boundaryJump(smooth, 900) < 1.8)
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

    @Test
    fun `ease brings the ink to rest at a stop and keeps distant timing`() {
        // Steady 10 chars/s, a stop 3000..3600, steady again.
        val knots = listOf(0L to 0f, 3000L to 30f, 3600L to 30f, 6600L to 60f)
        val stop = listOf(3000L..3600L)
        val crisp = requireNotNull(EnglishSmoothTrack.through(knots, 140f, stop))
        val eased = requireNotNull(EnglishSmoothTrack.through(knots, 140f, stop, easeMs = 260f))
        fun speed(track: EnglishSmoothTrack, t: Long) = (track.at(t + 20) - track.at(t)) * 1000f / 20
        // Arriving: crisp is still at full speed 40 ms before the stop; eased has nearly come to rest.
        assertTrue(speed(crisp, 2960) > 8f)
        assertTrue("eased ${speed(eased, 2960)}", speed(eased, 2960) < 2f)
        // The stop holds, and the ink lands exactly where it did.
        assertEquals(30f, eased.at(3000), 0.01f)
        assertEquals(30f, eased.at(3500), 0.01f)
        // Far from the stop the timing is untouched.
        assertEquals(crisp.at(1200), eased.at(1200), 0.05f)
        assertEquals(crisp.at(5600), eased.at(5600), 0.05f)
    }

    @Test
    fun `a short word with a long gloss borrows time from its neighbours`() {
        // Alafasy 3:4's close: azizun 1730 ms, dhu 260 ms ("All-Able (of)"), intiqam.
        val timed = listOf(Segment(16, 20365, 22095), Segment(17, 22095, 22355), Segment(18, 22355, 26030))
        val lengths = intArrayOf(15, 13, 11)
        val clock = requireNotNull(EnglishScrollClock.of(lengths, intArrayOf(16, 17, 18), { null }, timed, 140f) { null })
        fun fillMs(i: Int): Long {
            val t10 = (19000L..27000L step 5).first { clock.progress(i, it)!! >= 0.1f }
            val t90 = (19000L..27000L step 5).first { clock.progress(i, it)!! >= 0.9f }
            return t90 - t10
        }
        // Without borrowing it filled in about 200 ms (~50 letters a second).
        assertTrue("dhu fills in ${fillMs(1)} ms", fillMs(1) >= 550)
        // It still sits on its word: half-inked within 350 ms of the Arabic word's middle.
        val half = (19000L..27000L step 5).first { clock.progress(1, it)!! >= 0.5f }
        assertTrue("half at $half", kotlin.math.abs(half - 22225) <= 350)
        // An ordinary neighbour keeps its timing.
        val azizHalf = (19000L..27000L step 5).first { clock.progress(0, it)!! >= 0.5f }
        assertTrue("aziz half at $azizHalf", kotlin.math.abs(azizHalf - 21230) <= 250)
    }

    @Test
    fun `the inked gloss is the active word, across its borrowed window`() {
        val timed = listOf(Segment(16, 20365, 22095), Segment(17, 22095, 22355), Segment(18, 22355, 26030))
        val clock = requireNotNull(EnglishScrollClock.of(intArrayOf(15, 13, 11), intArrayOf(16, 17, 18), { null },
            timed, 140f) { null })
        val dhu = requireNotNull(clock.window(1))
        // "All-Able (of)" starts before dhu's own onset and runs past its end.
        assertTrue("window $dhu", dhu.first < 22095 - 150 && dhu.last > 22355 + 150)
        // While it inks, it is the active word, not the Arabic word being voiced.
        assertEquals(1, clock.wordAt(22000))
        assertEquals(1, clock.wordAt(22500))
        assertEquals(0, clock.wordAt(21000))
        assertEquals(2, clock.wordAt(23500))
        // Active ownership never runs backward.
        var last = -1
        for (t in 20000L..26500L step 10) {
            val at = clock.wordAt(t) ?: continue
            assertTrue(at >= last)
            last = at
        }
    }
}

