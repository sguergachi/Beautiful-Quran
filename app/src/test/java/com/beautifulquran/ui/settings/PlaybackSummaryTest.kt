package com.beautifulquran.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSummaryTest {
    @Test
    fun theRowSaysWhetherAChapterPlaysOn() {
        assertEquals("Plays on to the next chapter", playbackSummary(autoplayNextChapter = true))
        assertEquals("Stops at the chapter's end", playbackSummary(autoplayNextChapter = false))
    }

    @Test
    fun aPauseBetweenVersesIsNamedOnlyWhenSet() {
        assertEquals(
            "Stops at the chapter's end · 2 s between verses",
            playbackSummary(autoplayNextChapter = false, verseGapSeconds = 2),
        )
        assertEquals("Plays on to the next chapter", playbackSummary(autoplayNextChapter = true, verseGapSeconds = 0))
    }

    @Test
    fun theChoicesReadAsSeconds() {
        assertEquals(listOf("None", "1 s", "2 s", "3 s", "5 s"), VERSE_GAP_CHOICES.map(::verseGapLabel))
    }
}
