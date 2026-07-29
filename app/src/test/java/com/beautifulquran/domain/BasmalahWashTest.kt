package com.beautifulquran.domain

import com.beautifulquran.data.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BasmalahWashTest {

    /** Alafasy's `001001.mp3` word timings, straight out of the shipped DB. */
    private val alafasy = listOf(
        Segment(1, 60, 610),
        Segment(2, 610, 1439),
        Segment(3, 1439, 2532),
        Segment(4, 2532, 5870),
    )

    private fun at(positionMs: Long, segments: List<Segment> = alafasy): Float =
        BasmalahWash.progress(positionMs, segments)
            ?: error("expected a paced wash for $segments")

    @Test
    fun `the four words tile the artwork left to right in reading order`() {
        assertEquals(BasmalahWash.WORDS.size, BasmalahWash.WORD_END_PROGRESS.size)
        assertEquals(4, BasmalahWash.WORDS.size)
        var previous = 0f
        for (end in BasmalahWash.WORD_END_PROGRESS) {
            assertTrue("bands must ascend: $end after $previous", end > previous)
            previous = end
        }
        assertEquals(1f, BasmalahWash.WORD_END_PROGRESS.last(), 1e-6f)
    }

    @Test
    fun `no ink before the reciter starts`() {
        assertEquals(0f, at(0), 1e-6f)
        assertEquals(0f, at(59), 1e-6f)
        assertTrue(at(61) > 0f)
    }

    @Test
    fun `each word's band is crossed while that word is spoken`() {
        // Every word ends its band as the next word begins — that is the whole
        // point: ٱللَّهِ is washed while the reciter says "-llāhi".
        for ((index, segment) in alafasy.withIndex()) {
            val bandStart = if (index == 0) 0f else BasmalahWash.WORD_END_PROGRESS[index - 1]
            val bandEnd = BasmalahWash.WORD_END_PROGRESS[index]
            val entering = at(segment.startMs)
            val leaving = at(segment.endMs - 1)
            assertEquals("word ${index + 1} enters its band", bandStart, entering, 1e-3f)
            assertTrue(
                "word ${index + 1} must stay inside its band ($leaving vs $bandEnd)",
                leaving <= bandEnd + 1e-3f,
            )
            assertTrue("word ${index + 1} must advance", leaving > bandStart)
        }
    }

    @Test
    fun `the wash settles as the closing madd ends`() {
        assertEquals(1f, at(5870), 1e-6f)
        assertEquals(1f, at(9_000), 1e-6f)
        assertTrue("must not be settled mid-recitation", at(5000) < 1f)
    }

    @Test
    fun `progress never moves backwards`() {
        var previous = -1f
        for (ms in 0L..6200L step 8L) {
            val progress = at(ms)
            assertTrue("regressed at $ms: $progress after $previous", progress >= previous - 1e-6f)
            assertTrue(progress in 0f..1f)
            previous = progress
        }
    }

    @Test
    fun `the kashida sweep is fast and the closing madd is slow`() {
        // The artwork is not proportional to time: بِسۡمِ owns the long
        // elongated sīn (over half the width) for half a second, while
        // ٱلرَّحِيمِ holds most of the clip inside the last quarter.
        val firstWordSpeed = BasmalahWash.WORD_END_PROGRESS[0] / (610f - 60f)
        val lastBand = 1f - BasmalahWash.WORD_END_PROGRESS[2]
        val lastWordSpeed = lastBand / (5870f - 2532f)
        assertTrue(
            "kashida should outrun the closer ($firstWordSpeed vs $lastWordSpeed)",
            firstWordSpeed > lastWordSpeed * 5f,
        )
    }

    @Test
    fun `the closer parks on the madd the stop lengthens`() {
        // ٱلرَّحِيمِ: "ar-raḥīīīm" sustains the ي, then closes the م. Find where
        // inside the band the wash goes slowest and check it is that glyph —
        // ٱ ل ر ح ي م puts the ي's slot at 0.67..0.83 of the word, the closing
        // م at 0.83..1.
        val start = 2532L
        val end = 5870L
        val bandStart = BasmalahWash.WORD_END_PROGRESS[2]
        val band = 1f - bandStart
        val step = 40L
        var slowest = Float.MAX_VALUE
        var parkedAt = 0f
        var ms = start
        while (ms + step <= end) {
            val moved = at(ms + step) - at(ms)
            if (moved < slowest) {
                slowest = moved
                parkedAt = ((at(ms) + at(ms + step)) / 2f - bandStart) / band
            }
            ms += step
        }
        assertTrue("the park is on the ي, not the م (at $parkedAt of the word)", parkedAt in 0.6f..0.85f)
        val uniform = band * step / (end - start)
        assertTrue("the park must be much slower than uniform ($slowest vs $uniform)", slowest < uniform / 4f)
        assertTrue("the wash must still creep while parked", slowest > 0f)
        // And the last glyph is still crossed before the voice stops.
        assertEquals(1f, at(end), 1e-6f)
        assertTrue("the closer is crossed after the park", at(end) - at(end - 200) > 0f)
    }

    @Test
    fun `pacing off still tracks the words`() {
        val plain = BasmalahWash.progress(3000, alafasy, hold = null)
        assertNotNull(plain)
        val bandStart = BasmalahWash.WORD_END_PROGRESS[2]
        val phase = (3000f - 2532f) / (5870f - 2532f)
        assertEquals(bandStart + (1f - bandStart) * phase, plain!!, 1e-3f)
    }

    @Test
    fun `a word gap holds the wash into its own band`() {
        // Ash-Shuraym leaves 10 ms gaps between words; the karaoke hold means
        // the ink still reaches each band edge instead of stalling short.
        val gapped = listOf(
            Segment(1, 140, 650),
            Segment(2, 660, 1160),
            Segment(3, 1170, 2110),
            Segment(4, 2120, 2560),
        )
        val settled = BasmalahWash.progress(659, gapped)!!
        val band = BasmalahWash.WORD_END_PROGRESS[0]
        assertTrue("ink should reach بِسۡمِ's band edge ($settled of $band)", settled > band * 0.99f)
        assertTrue(settled <= band)
    }

    @Test
    fun `timings that cannot be mapped fall back to the caller`() {
        assertNull(BasmalahWash.progress(100, emptyList()))
        assertNull(BasmalahWash.progress(100, alafasy.dropLast(1)))
        // A repeated word — the wash would have to jump bands.
        assertNull(
            BasmalahWash.progress(
                100,
                listOf(
                    Segment(1, 60, 610),
                    Segment(2, 610, 1439),
                    Segment(2, 1439, 2000),
                    Segment(3, 2000, 2532),
                    Segment(4, 2532, 5870),
                ),
            ),
        )
        // Non-monotone clock.
        assertNull(
            BasmalahWash.progress(
                100,
                listOf(
                    Segment(1, 600, 900),
                    Segment(2, 100, 300),
                    Segment(3, 900, 1200),
                    Segment(4, 1200, 2000),
                ),
            ),
        )
    }

    @Test
    fun `every shipped reciter's basmalah is paced`() {
        // Onsets differ by seconds between reciters (Hani starts at 945 ms);
        // all of them must take the paced path, not the clip ramp.
        val shipped = listOf(
            listOf(Segment(1, 50, 520), Segment(2, 520, 1330), Segment(3, 1330, 2336), Segment(4, 2336, 4790)),
            listOf(Segment(1, 536, 970), Segment(2, 970, 1590), Segment(3, 1590, 2540), Segment(4, 2540, 4255)),
            listOf(Segment(1, 544, 960), Segment(2, 960, 1620), Segment(3, 1620, 2740), Segment(4, 2740, 4075)),
            listOf(Segment(1, 0, 650), Segment(2, 650, 1130), Segment(3, 1130, 1860), Segment(4, 1860, 2725)),
            listOf(Segment(1, 140, 650), Segment(2, 660, 1160), Segment(3, 1170, 2110), Segment(4, 2120, 2560)),
            listOf(Segment(1, 945, 2285), Segment(2, 2285, 2865), Segment(3, 2865, 3685), Segment(4, 3685, 5417)),
        )
        for (segments in shipped) {
            val first = segments.first()
            assertEquals("silent lead-in", 0f, at(first.startMs - 1, segments), 1e-6f)
            assertEquals(
                "settles with the voice",
                1f,
                at(segments.last().endMs, segments),
                1e-6f,
            )
            var previous = -1f
            for (ms in 0L..segments.last().endMs step 16L) {
                val progress = at(ms, segments)
                assertTrue("regressed at $ms", progress >= previous - 1e-6f)
                previous = progress
            }
        }
    }
}
