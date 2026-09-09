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

    /** The English leaf's line: even word bands, no tajweed. */
    private fun plain(positionMs: Long, segments: List<Segment> = alafasy): Float =
        BasmalahWash.plainProgress(positionMs, segments)
            ?: error("expected a plain wash for $segments")

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
    fun `the prose line takes even quarters, one per word`() {
        // Alafasy: بِسۡمِ runs 60..610, so its whole band is the sentence's
        // first quarter — not the half of the artwork its kashida covers.
        assertEquals(0f, plain(60), 1e-6f)
        assertEquals(0.125f, plain(335), 0.002f)
        assertEquals(0.25f, plain(610), 1e-6f)
        assertEquals(0.5f, plain(1439), 1e-6f)
        assertEquals(0.75f, plain(2532), 1e-6f)
    }

    @Test
    fun `the prose line crosses its band at one speed, the calligraphy does not`() {
        // ٱلرَّحِيمِ, the closing madd: the calligraphy sprints its run-up and
        // then parks on the letter the stop lengthens. A line of English prose
        // has no letter anyone is holding, so equal time is equal distance.
        val start = 2532L
        val end = 5870L
        val quarters = (1..4).map { start + (end - start) * it / 4 }
        val proseSteps = quarters.map { plain(it) }.zipWithNext { a, b -> b - a }
        val firstStep = plain(quarters[0]) - plain(start)
        for (step in proseSteps) {
            assertEquals("prose must cross its quarter evenly", firstStep, step, 0.002f)
        }
        val pacedSteps = quarters.map { at(it) }.zipWithNext { a, b -> b - a }
        assertTrue(
            "the paced wash must not be even: ${pacedSteps.joinToString()}",
            pacedSteps.max() > pacedSteps.min() * 3f,
        )
    }

    @Test
    fun `the prose feather leaves the last quarter untouched until its turn`() {
        // Same guard as MAX_FEATHER, for even bands: the gradient runs one
        // feather ahead of the front, so the far end is first touched at
        // 1 / (1 + feather) — which must be no earlier than 3/4.
        assertEquals(0.75f, 1f / (1f + BasmalahWash.PLAIN_MAX_FEATHER), 1e-6f)
    }

    @Test
    fun `the prose line refuses the rows the calligraphy refuses`() {
        val repeated = listOf(
            Segment(1, 0, 100),
            Segment(1, 100, 200),
            Segment(3, 200, 300),
            Segment(4, 300, 400),
        )
        assertNull(BasmalahWash.plainProgress(150, repeated))
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
    fun `the closing word settles with the clip, never before the voice`() {
        // Every shipped clip, measured with ffprobe + an energy envelope: the
        // rows stop marking before the reciter stops (As-Sudais by 345 ms), so
        // settling on the row's own mark finished the ink mid-madd. The wash
        // must not be complete before the voice is, and must be complete by the
        // end of the clip.
        data class Clip(val name: String, val segments: List<Segment>, val durationMs: Long, val voiceEndMs: Long)
        val clips = listOf(
            Clip("Alafasy", alafasy, 6031, 6020),
            Clip("Husary", listOf(Segment(1, 50, 520), Segment(2, 520, 1330), Segment(3, 1330, 2336), Segment(4, 2336, 4790)), 5120, 4650),
            Clip("AbdulBaset", listOf(Segment(1, 536, 970), Segment(2, 970, 1590), Segment(3, 1590, 2540), Segment(4, 2540, 4255)), 4336, 4320),
            Clip("Minshawi", listOf(Segment(1, 544, 960), Segment(2, 960, 1620), Segment(3, 1620, 2740), Segment(4, 2740, 4075)), 4971, 4260),
            Clip("As-Sudais", listOf(Segment(1, 0, 650), Segment(2, 650, 1130), Segment(3, 1130, 1860), Segment(4, 1860, 2725)), 3082, 3070),
            Clip("Ash-Shuraym", listOf(Segment(1, 140, 650), Segment(2, 660, 1160), Segment(3, 1170, 2110), Segment(4, 2120, 2560)), 2722, 2710),
            Clip("Hani", listOf(Segment(1, 945, 2285), Segment(2, 2285, 2865), Segment(3, 2865, 3685), Segment(4, 3685, 5417)), 4472, 4010),
        )
        for ((name, segments, durationMs, voiceEndMs) in clips) {
            val settled = BasmalahWash.progress(voiceEndMs - 60, segments, durationMs)!!
            assertTrue("$name settled before the voice stopped ($settled)", settled < 1f)
            assertEquals(
                "$name must be settled by the end of its clip",
                1f,
                BasmalahWash.progress(durationMs, segments, durationMs)!!,
                1e-6f,
            )
            var previous = -1f
            for (ms in 0L..durationMs step 8L) {
                val progress = BasmalahWash.progress(ms, segments, durationMs)!!
                assertTrue("$name regressed at $ms", progress >= previous - 1e-6f)
                previous = progress
            }
        }
    }

    @Test
    fun `an unusually long tail of silence cannot stretch the closer forever`() {
        // Guard: the closer may at most double its own marked length, so a file
        // that trails ten seconds of room tone does not make the ink crawl.
        val hugeTail = 30_000L
        val settlesAt = (2532L + (5870L - 2532L)) + (5870L - 2532L)
        assertEquals(1f, BasmalahWash.progress(settlesAt, alafasy, hugeTail)!!, 1e-6f)
        assertTrue(BasmalahWash.progress(settlesAt - 100, alafasy, hugeTail)!! < 1f)
    }

    @Test
    fun `the feather cap keeps the closing word untouched until its turn`() {
        // The wash gradient runs one feather ahead of the solid front, so the
        // faint edge first reaches the end of the artwork at 1 / (1 + feather).
        // Capped, that is exactly when ٱلرَّحِيمِ begins.
        assertEquals(
            BasmalahWash.WORD_END_PROGRESS[2],
            1f / (1f + BasmalahWash.MAX_FEATHER),
            1e-5f,
        )
        // The verse-word feather would have touched it at 38% of the clip.
        assertTrue(BasmalahWash.MAX_FEATHER < 0.4f)
        assertTrue("must stay soft, not a hard peel", BasmalahWash.MAX_FEATHER > 0.15f)
    }

    @Test
    fun `a row that overruns its own clip is fitted inside it`() {
        // Hani Ar-Rifai: the row ends 945 ms after his 001001.mp3 does, so
        // without the fit the wash would stall at ~87% with the audio over.
        val hani = listOf(
            Segment(1, 945, 2285),
            Segment(2, 2285, 2865),
            Segment(3, 2865, 3685),
            Segment(4, 3685, 5417),
        )
        val clipMs = 4472L
        assertTrue("unfitted, the clip ends mid-wash", at(clipMs, hani) < 0.95f)
        // Fitted, it lands exactly as the clip ends.
        assertEquals(1f, BasmalahWash.progress(clipMs, hani, clipMs)!!, 1e-6f)
        // The measured onset is kept — no ink before the voice.
        assertEquals(0f, BasmalahWash.progress(944, hani, clipMs)!!, 1e-6f)
        var previous = -1f
        for (ms in 0L..clipMs step 8L) {
            val progress = BasmalahWash.progress(ms, hani, clipMs)!!
            assertTrue("regressed at $ms", progress >= previous - 1e-6f)
            previous = progress
        }
        // A row whose last mark is the clip's end keeps its own clock exactly.
        for (ms in longArrayOf(0, 1000, 3000, 5000)) {
            assertEquals(
                at(ms),
                BasmalahWash.progress(ms, alafasy, 5870)!!,
                1e-6f,
            )
        }
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
