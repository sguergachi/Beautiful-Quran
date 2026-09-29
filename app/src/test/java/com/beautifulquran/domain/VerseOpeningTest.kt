package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerseOpeningTest {

    @Test
    fun `the file start seeks to the first word the highlight uses`() {
        // Hani 4:148, first word at 1951ms. 4:147 is an ordinary verse at 220ms.
        assertEquals(1_951L, verseOpeningSeekMs(firstWordStartMs = 1_951L, positionMs = 0L))
        assertEquals(220L, verseOpeningSeekMs(firstWordStartMs = 220L, positionMs = 0L))
        assertEquals(1_850L, verseOpeningSeekMs(firstWordStartMs = 1_850L, positionMs = 0L))
    }

    @Test
    fun `a lead inside the slack plays from the file start`() {
        assertNull(verseOpeningSeekMs(firstWordStartMs = 0L, positionMs = 0L))
        assertNull(verseOpeningSeekMs(firstWordStartMs = 30L, positionMs = 0L))
    }

    @Test
    fun `a jump that is not the file start keeps its position`() {
        assertNull(verseOpeningSeekMs(firstWordStartMs = 1_951L, positionMs = 9_130L))
        assertNull(verseOpeningSeekMs(firstWordStartMs = 1_951L, positionMs = 80L))
        assertEquals(1_951L, verseOpeningSeekMs(firstWordStartMs = 1_951L, positionMs = 20L))
    }
}
