package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingPlaceRibbonPolicyTest {
    @Test
    fun playedVerse_becomesPauseDropTargetOnThisChapter() {
        assertEquals(7, pausedReadingPlaceRibbonAyah(2, 2, 7, false, false, true))
    }

    @Test
    fun liveBufferingUnrelatedOrAlreadyPausedPlayback_doesNotDrop() {
        assertEquals(null, pausedReadingPlaceRibbonAyah(2, 2, 7, true, false, true))
        assertEquals(null, pausedReadingPlaceRibbonAyah(2, 2, 7, false, true, true))
        assertEquals(null, pausedReadingPlaceRibbonAyah(2, 1, 7, false, false, true))
        assertEquals(null, pausedReadingPlaceRibbonAyah(2, 2, 0, false, false, true))
        assertEquals(null, pausedReadingPlaceRibbonAyah(2, 2, 7, false, false, false))
    }
}
