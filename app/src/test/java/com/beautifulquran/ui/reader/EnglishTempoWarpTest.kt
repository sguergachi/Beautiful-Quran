package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.AudibleSilence
import com.beautifulquran.domain.EnglishWordAlignment
import com.beautifulquran.domain.TajweedPacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishTempoWarpTest {
    private val held = requireNotNull(TajweedPacing.curve("ٱلضَّآلِّينَ")).tempo(0.6f, 1f)

    @Test
    fun `each spoken word maps onto itself and round trips`() {
        val warp = requireNotNull(EnglishTempoWarp.of(
            listOf(Segment(1, 1000, 2000), Segment(2, 2000, 2600)), emptyList(),
        ) { if (it.position == 1) held else null })
        assertEquals(1000L, warp.forward(1000))
        assertEquals(2000L, warp.forward(2000))
        assertEquals(500L, warp.forward(500))
        assertEquals(2300L, warp.forward(2300))
        var previous = 0L
        var moved = false
        for (at in 1000L..1999L) {
            val forward = warp.forward(at)
            assertTrue(forward >= previous)
            if (forward != at) moved = true
            assertEquals(at.toDouble(), warp.inverse(forward).toDouble(), 3.0)
            previous = forward
        }
        assertTrue(moved)
    }

    @Test
    fun `overlapping speech and measured quiet keep the steady clock`() {
        assertNull(EnglishTempoWarp.of(listOf(Segment(1, 1000, 2100), Segment(2, 2000, 2600)), emptyList()) { held })
        assertNull(EnglishTempoWarp.of(listOf(Segment(1, 1000, 2000)),
            listOf(AudibleSilence(1400, 1600))) { held })
    }

    @Test
    fun `Hani 64 1 keeps word boundaries and a forward edge with tajweed tempo`() {
        val row = javaClass.getResourceAsStream("/english-glorification-owners.tsv")!!
            .bufferedReader().use { it.readLines().first { r -> r.startsWith("64:1\t") } }.split('\t')
        val alignment = EnglishWordAlignment.align(row[1], row[2].split('|'), row[4].split('|'))!!
        val hani = listOf(
            Segment(1, 1020, 2140), Segment(2, 2140, 3150), Segment(3, 3150, 3450),
            Segment(4, 3450, 3660), Segment(5, 3660, 5090), Segment(6, 5090, 5750),
            Segment(7, 5750, 5950), Segment(8, 5950, 6770), Segment(9, 6770, 7150),
            Segment(10, 7150, 8060), Segment(11, 8060, 8590), Segment(12, 8590, 9730),
            Segment(13, 9730, 10370), Segment(14, 10370, 10870), Segment(15, 10870, 11570),
            Segment(16, 11570, 12890), Segment(17, 12890, 14625),
        )
        val warp = requireNotNull(EnglishTempoWarp.of(hani, emptyList()) { held })
        val steady = englishPresentationPlan(alignment, hani).readingFlow!!
        val paced = englishPresentationPlan(alignment, hani, tempo = warp).readingFlow!!
        for (segment in hani) {
            assertEquals(steady.through(segment.startMs), paced.through(segment.startMs), 0.002f)
        }
        var previous = 0f
        var differs = false
        for (at in 0L..14625L step 7) {
            val edge = paced.through(at)
            assertTrue("edge cannot retreat at $at", edge >= previous)
            if (kotlin.math.abs(edge - steady.through(at)) > 0.005f) differs = true
            previous = edge
        }
        assertTrue(differs)
        assertNotEquals(steady.mediaAt(0.3f), paced.mediaAt(0.3f))
        assertEquals(0.3f, paced.through(paced.mediaAt(0.3f)), 0.01f)
    }
}
