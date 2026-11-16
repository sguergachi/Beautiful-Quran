package com.beautifulquran.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSummaryTest {
    @Test
    fun theRowSaysWhetherAChapterPlaysOn() {
        assertEquals("Plays on to the next chapter", playbackSummary(autoplayNextChapter = true))
        assertEquals("Stops at the chapter's end", playbackSummary(autoplayNextChapter = false))
    }
}
