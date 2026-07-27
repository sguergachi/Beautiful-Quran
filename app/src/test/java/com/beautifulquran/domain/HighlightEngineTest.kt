package com.beautifulquran.domain

import com.beautifulquran.data.model.Segment
import com.beautifulquran.data.model.SubwordKeyframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HighlightEngineTest {

    // Al-Fatihah 1:1 shaped data: 4 words with small gaps between them.
    private val segments = listOf(
        Segment(position = 1, startMs = 0, endMs = 960),
        Segment(position = 2, startMs = 970, endMs = 1420),
        Segment(position = 3, startMs = 1430, endMs = 2670),
        Segment(position = 4, startMs = 2680, endMs = 6210),
    )

    @Test
    fun `empty segments highlight nothing`() {
        assertNull(HighlightEngine.activeWord(emptyList(), 500))
    }

    @Test
    fun `position inside a word lights that word`() {
        assertEquals(1, HighlightEngine.activeWord(segments, 0))
        assertEquals(1, HighlightEngine.activeWord(segments, 500))
        assertEquals(2, HighlightEngine.activeWord(segments, 1000))
        assertEquals(4, HighlightEngine.activeWord(segments, 3000))
    }

    @Test
    fun `gap between words holds the previous word`() {
        assertEquals(1, HighlightEngine.activeWord(segments, 965))
        assertEquals(3, HighlightEngine.activeWord(segments, 2675))
    }

    @Test
    fun `nothing lights before the first word`() {
        val withBasmalahLead = listOf(
            Segment(position = 1, startMs = 4000, endMs = 5000),
            Segment(position = 2, startMs = 5000, endMs = 6000),
        )
        assertNull(HighlightEngine.activeWord(withBasmalahLead, 2000))
        assertEquals(1, HighlightEngine.activeWord(withBasmalahLead, 4500))
    }

    @Test
    fun `nothing lights after the last word ends`() {
        assertNull(HighlightEngine.activeWord(segments, 6210))
        assertNull(HighlightEngine.activeWord(segments, 99999))
    }

    @Test
    fun `boundaries are start-inclusive end-exclusive`() {
        assertEquals(2, HighlightEngine.activeWord(segments, 970))
        assertEquals(3, HighlightEngine.activeWord(segments, 1430))
    }

    // Reciter recites words 1,2,3 then repeats 2,3 before moving to 4:
    // positions 1,2,3,2,3,4 with the repeated spans pointing back.
    private val withRepeat = listOf(
        Segment(position = 1, startMs = 0, endMs = 1000),
        Segment(position = 2, startMs = 1000, endMs = 2000),
        Segment(position = 3, startMs = 2000, endMs = 3000),
        Segment(position = 2, startMs = 3000, endMs = 4000),
        Segment(position = 3, startMs = 4000, endMs = 5000),
        Segment(position = 4, startMs = 5000, endMs = 6000),
    )

    @Test
    fun `first pass is not flagged as a repeat`() {
        val info = HighlightEngine.activeInfo(withRepeat, 2500)!!
        assertEquals(3, info.position)
        assertEquals(false, info.isRepeat)
        assertEquals(3, info.highWater)
        assertEquals(3, info.repeatStart)
    }

    @Test
    fun `re-recited word is flagged as a repeat and holds the high-water mark`() {
        val info = HighlightEngine.activeInfo(withRepeat, 3500)!!
        assertEquals(2, info.position)      // jumped back to word 2
        assertEquals(true, info.isRepeat)
        assertEquals(3, info.highWater)     // word 3 was already reached
        assertEquals(2, info.repeatStart)
    }

    @Test
    fun `repeat start holds across the repeated phrase`() {
        val info = HighlightEngine.activeInfo(withRepeat, 4500)!!
        assertEquals(3, info.position)
        assertEquals(true, info.isRepeat)
        assertEquals(3, info.highWater)
        assertEquals(2, info.repeatStart)
    }

    @Test
    fun `advancing past the repeat clears the repeat flag`() {
        val info = HighlightEngine.activeInfo(withRepeat, 5500)!!
        assertEquals(4, info.position)
        assertEquals(false, info.isRepeat)
        assertEquals(4, info.highWater)
        assertEquals(4, info.repeatStart)
    }

    @Test
    fun `second repeat chain in the same ayah starts fresh`() {
        // 1,2,(repeat 1),3,4,(repeat 3,4): two independent backtracks.
        val twoChains = listOf(
            Segment(1, 0, 1000),
            Segment(2, 1000, 2000),
            Segment(1, 2000, 3000),
            Segment(3, 3000, 4000),
            Segment(4, 4000, 5000),
            Segment(3, 5000, 6000),
            Segment(4, 6000, 7000),
        )
        val first = HighlightEngine.activeInfo(twoChains, 2500)!!
        assertEquals(true, first.isRepeat)
        assertEquals(1, first.repeatStart)
        assertEquals(2, first.highWater)

        // Word 3's first pass is new material — chain over.
        val between = HighlightEngine.activeInfo(twoChains, 3500)!!
        assertEquals(false, between.isRepeat)
        assertEquals(3, between.repeatStart)

        val second = HighlightEngine.activeInfo(twoChains, 6500)!!
        assertEquals(true, second.isRepeat)
        assertEquals(3, second.repeatStart)
        assertEquals(4, second.highWater)
    }

    @Test
    fun `repeat back to the first word holds the whole chain`() {
        val fromStart = listOf(
            Segment(1, 0, 1000),
            Segment(2, 1000, 2000),
            Segment(1, 2000, 3000),
            Segment(2, 3000, 4000),
        )
        val info = HighlightEngine.activeInfo(fromStart, 3500)!!
        assertEquals(2, info.position)
        assertEquals(true, info.isRepeat)
        assertEquals(1, info.repeatStart)
        assertEquals(2, info.highWater)
    }

    @Test
    fun `position exactly on a repeat segment boundary belongs to the repeat`() {
        val info = HighlightEngine.activeInfo(withRepeat, 3000)!!
        assertEquals(2, info.position)
        assertEquals(true, info.isRepeat)
    }

    @Test
    fun `hold end is the next segment start so the sweep matches karaoke hold`() {
        // Gap between word 1 end (960) and word 2 start (970): hold through the gap.
        val info = HighlightEngine.activeInfo(segments, 500)!!
        assertEquals(1, info.position)
        assertEquals(960, info.endMs)
        assertEquals(970, info.holdEndMs)

        val last = HighlightEngine.activeInfo(segments, 3000)!!
        assertEquals(4, last.position)
        assertEquals(6210, last.endMs)
        assertEquals(6210, last.holdEndMs)
    }

    @Test
    fun `PreparedTimings matches convenience activeInfo and allocates tables once`() {
        val prepared = HighlightEngine.PreparedTimings.prepare(withRepeat)
        for (ms in listOf(500L, 2500L, 3500L, 4500L, 5500L, 99999L)) {
            assertEquals(
                HighlightEngine.activeInfo(withRepeat, ms),
                prepared.activeInfo(ms),
            )
        }
        // Same instance answers every tick — no per-call IntArray rebuild.
        assertEquals(withRepeat, prepared.segments)
    }

    @Test
    fun `active occurrence preserves its V2 acoustic keyframes`() {
        val points = listOf(SubwordKeyframe(120, 0.5f), SubwordKeyframe(400, 1f))
        val info = HighlightEngine.activeInfo(
            listOf(Segment(1, 100, 500, points)),
            200,
        )

        assertEquals(points, info?.subwordKeyframes)
    }
}
