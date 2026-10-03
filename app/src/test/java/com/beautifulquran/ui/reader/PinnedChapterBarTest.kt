package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedChapterBarTest {

    @Test
    fun scrollBarStaysMountedEvenBeforePlayback() {
        assertTrue(showPinnedChapterBar(
            readerOpen = true, mushaf = false, gathering = false, overlayBlocking = false,
        ))
        // Home covers it before playback; the reader still shows it underneath.
        val unplayed = pinnedChapterBarZIndex(coverSession = false)
        assertTrue(unplayed > 1f)
        assertTrue(unplayed < 2f)
        assertTrue(pinnedChapterBarZIndex(coverSession = true) > 2f)
    }

    @Test
    fun barRidesReaderToSettingsAndReturnsWithoutChangingOwners() {
        for ((page, turn) in listOf(1f to 0f, 1.5f to 0.5f, 2f to 1f, 3f to 1f,
            1.5f to 0.5f, 1f to 0f, 0.4f to 0f)) {
            assertEquals(turn, pinnedBarTurn(page), 1e-6f)
        }
    }

    @Test
    fun mushafGatherAndOverlaysKeepTheirOwnChrome() {
        assertFalse(showPinnedChapterBar(
            readerOpen = true, mushaf = true, gathering = false, overlayBlocking = false,
        ))
        assertFalse(showPinnedChapterBar(
            readerOpen = true, mushaf = false, gathering = true, overlayBlocking = false,
        ))
        assertFalse(showPinnedChapterBar(
            readerOpen = true, mushaf = false, gathering = false, overlayBlocking = true,
        ))
        assertFalse(showPinnedChapterBar(
            readerOpen = false, mushaf = false, gathering = false, overlayBlocking = false,
        ))
    }
}
