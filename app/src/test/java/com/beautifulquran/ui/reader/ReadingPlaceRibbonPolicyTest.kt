package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingPlaceRibbonPolicyTest {
    @Test
    fun pausedVerse_replacesParkedPlaceOnThisChapter() {
        assertEquals(7, readingPlaceRibbonAyah(3, 2, 2, 7, false, false))
    }

    @Test
    fun liveOrUnrelatedPlayback_keepsParkedPlace() {
        assertEquals(3, readingPlaceRibbonAyah(3, 2, 2, 7, true, false))
        assertEquals(3, readingPlaceRibbonAyah(3, 2, 2, 7, false, true))
        assertEquals(3, readingPlaceRibbonAyah(3, 2, 1, 7, false, false))
        assertEquals(3, readingPlaceRibbonAyah(3, 2, 2, 0, false, false))
    }
}
