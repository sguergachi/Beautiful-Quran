package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HizbGapTest {

    @Test
    fun `the rub el hizb mark is the gate`() {
        assertTrue(isHizbStop("۞وَأَيُّوبَ إِذۡ نَادَىٰ"))
        assertFalse(isHizbStop("وَمِنَ ٱلشَّيَٰطِينِ مَن يَغُوصُونَ"))
    }

    @Test
    fun `every anbiya hizb starts just before the first word`() {
        // Hani 21:29, 21:51, and 21:83. 21:82 leads by 1850ms and has no ۞.
        assertEquals(2_050L, hizbGapEntryMs(hizbStop = true, firstWordStartMs = 2_210L))
        assertEquals(2_900L, hizbGapEntryMs(hizbStop = true, firstWordStartMs = 3_060L))
        assertEquals(2_040L, hizbGapEntryMs(hizbStop = true, firstWordStartMs = 2_200L))
        assertEquals(0L, hizbGapEntryMs(hizbStop = false, firstWordStartMs = 1_850L))
    }

    @Test
    fun `a short hizb pause keeps the attack and a breath plays through`() {
        // Hani 39:53 is a hizb verse whose first word is at 450ms.
        assertEquals(290L, hizbGapEntryMs(hizbStop = true, firstWordStartMs = 450L))
        assertEquals(40L, hizbGapEntryMs(hizbStop = true, firstWordStartMs = 200L))
        assertEquals(0L, hizbGapEntryMs(hizbStop = true, firstWordStartMs = 160L))
        assertEquals(0L, hizbGapEntryMs(hizbStop = false, firstWordStartMs = 140L))
    }

    @Test
    fun `only an automatic advance into the next file seeks`() {
        assertEquals(
            2_040L,
            hizbGapSeekMs(
                automaticItemAdvance = true,
                hizbStop = true,
                firstWordStartMs = 2_200L,
                positionMs = 0L,
            ),
        )
        assertNull(
            hizbGapSeekMs(
                automaticItemAdvance = false,
                hizbStop = true,
                firstWordStartMs = 2_200L,
                positionMs = 0L,
            ),
        )
        assertNull(
            hizbGapSeekMs(
                automaticItemAdvance = true,
                hizbStop = false,
                firstWordStartMs = 1_850L,
                positionMs = 0L,
            ),
        )
        assertEquals(
            290L,
            hizbGapSeekMs(
                automaticItemAdvance = true,
                hizbStop = true,
                firstWordStartMs = 450L,
                positionMs = 0L,
            ),
        )
        // 200ms lead leaves a 40ms seek, inside the slack, so playback stays put.
        assertNull(
            hizbGapSeekMs(
                automaticItemAdvance = true,
                hizbStop = true,
                firstWordStartMs = 200L,
                positionMs = 0L,
            ),
        )
        assertNull(
            hizbGapSeekMs(
                automaticItemAdvance = true,
                hizbStop = true,
                firstWordStartMs = 2_200L,
                positionMs = 2_000L,
            ),
        )
        assertEquals(
            2_040L,
            hizbGapSeekMs(
                automaticItemAdvance = true,
                hizbStop = true,
                firstWordStartMs = 2_200L,
                positionMs = 1_999L,
            ),
        )
    }
}
