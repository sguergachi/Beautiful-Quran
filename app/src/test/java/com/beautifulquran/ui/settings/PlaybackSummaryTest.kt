package com.beautifulquran.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSummaryTest {
    @Test
    fun theRowNamesTheVoiceAndWhetherItPlaysOn() {
        assertEquals(
            "Mishary Alafasy · plays on to the next chapter",
            playbackSummary("Mishary Alafasy", autoplayNextChapter = true),
        )
        assertEquals(
            "Mishary Alafasy · stops at the chapter's end",
            playbackSummary("Mishary Alafasy", autoplayNextChapter = false),
        )
    }

    @Test
    fun beforeTheVoicesLoadTheRowDescribesThePage() {
        assertEquals("Reciter and chapter flow", playbackSummary(null, autoplayNextChapter = true))
        assertEquals("Reciter and chapter flow", playbackSummary(null, autoplayNextChapter = false))
    }
}
