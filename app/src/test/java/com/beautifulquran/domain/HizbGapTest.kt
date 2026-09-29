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
    fun `anbiya 83 starts just before the first word`() {
        // Hani 21:83 is the hizb verse, first word at 2200ms.
        // 21:82 leads by 1850ms and is not a hizb verse.
        assertEquals(2040L, hizbGapEntryMs(hizbStop = true, firstWordStartMs = 2_200L))
        assertEquals(0L, hizbGapEntryMs(hizbStop = false, firstWordStartMs = 1_850L))
    }

    @Test
    fun `breaths and short hizb leads play from the file start`() {
        assertEquals(0L, hizbGapEntryMs(hizbStop = false, firstWordStartMs = 140L))
        assertEquals(0L, hizbGapEntryMs(hizbStop = true, firstWordStartMs = 200L))
        assertEquals(0L, hizbGapEntryMs(hizbStop = true, firstWordStartMs = 799L))
        assertEquals(640L, hizbGapEntryMs(hizbStop = true, firstWordStartMs = 800L))
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
