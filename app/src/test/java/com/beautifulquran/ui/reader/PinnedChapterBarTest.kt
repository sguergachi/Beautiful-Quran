package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedChapterBarTest {

    @Test
    fun staysAcrossTheCoverAndTheReader() {
        assertTrue(
            showPinnedChapterBar(
                stackPage = 1f,
                readerOpen = true,
                mushaf = false,
                gathering = false,
                overlayBlocking = false,
                coverSession = false,
            ),
        )
        assertTrue(
            showPinnedChapterBar(
                stackPage = 0.7f,
                readerOpen = true,
                mushaf = false,
                gathering = false,
                overlayBlocking = false,
                coverSession = true,
            ),
        )
        assertTrue(
            showPinnedChapterBar(
                stackPage = 0.2f,
                readerOpen = true,
                mushaf = false,
                gathering = false,
                overlayBlocking = false,
                coverSession = true,
            ),
        )
    }

    @Test
    fun coverWithoutAVerseDropsTheBar() {
        assertFalse(
            showPinnedChapterBar(
                stackPage = 0.2f,
                readerOpen = true,
                mushaf = false,
                gathering = false,
                overlayBlocking = false,
                coverSession = false,
            ),
        )
    }

    @Test
    fun staysPinnedAndRidesTheReaderWhenSettingsOpens() {
        // Dropping it here swapped in the reader's own bar mid-swipe, which
        // recomposed the reader and the chapter list on the turn's first frames.
        for (page in listOf(1.01f, 1.2f, 2f, 3f)) {
            assertTrue(
                showPinnedChapterBar(
                    stackPage = page,
                    readerOpen = true,
                    mushaf = false,
                    gathering = false,
                    overlayBlocking = false,
                    coverSession = false,
                ),
            )
        }
        assertEquals(0f, pinnedBarTurn(0.4f), 0f)
        assertEquals(0f, pinnedBarTurn(1f), 0f)
        assertEquals(0.5f, pinnedBarTurn(1.5f), 1e-6f)
        assertEquals(1f, pinnedBarTurn(2f), 0f)
        assertEquals(1f, pinnedBarTurn(3f), 0f)
    }

    @Test
    fun mushafGatherAndOverlaysKeepTheirOwnChrome() {
        assertFalse(
            showPinnedChapterBar(
                stackPage = 1f,
                readerOpen = true,
                mushaf = true,
                gathering = false,
                overlayBlocking = false,
                coverSession = true,
            ),
        )
        assertFalse(
            showPinnedChapterBar(
                stackPage = 1f,
                readerOpen = true,
                mushaf = false,
                gathering = true,
                overlayBlocking = false,
                coverSession = true,
            ),
        )
        assertFalse(
            showPinnedChapterBar(
                stackPage = 1f,
                readerOpen = true,
                mushaf = false,
                gathering = false,
                overlayBlocking = true,
                coverSession = true,
            ),
        )
        assertFalse(
            showPinnedChapterBar(
                stackPage = 1f,
                readerOpen = false,
                mushaf = false,
                gathering = false,
                overlayBlocking = false,
                coverSession = true,
            ),
        )
    }
}

class ScrollReaderBackArrowTest {

    @Test
    fun parkedReaderShowsTheArrow() {
        assertTrue(showScrollReaderBackArrow(page = 1f, mushaf = false, dragHidesBack = false))
    }

    @Test
    fun swipeAndTheTurnKeepTheArrowOff() {
        assertFalse(showScrollReaderBackArrow(page = 1f, mushaf = false, dragHidesBack = true))
        assertFalse(showScrollReaderBackArrow(page = 0.7f, mushaf = false, dragHidesBack = true))
        assertFalse(showScrollReaderBackArrow(page = 0.7f, mushaf = false, dragHidesBack = false))
        assertFalse(showScrollReaderBackArrow(page = 0f, mushaf = false, dragHidesBack = false))
    }

    @Test
    fun mushafKeepsItsBookControl() {
        assertTrue(showScrollReaderBackArrow(page = 0.7f, mushaf = true, dragHidesBack = true))
    }

    @Test
    fun fingerUpNeverRevealsTheArrow() {
        assertFalse(hideScrollBackOnFingerUp(scrollReaderOpen = true, target = 1, page = 1f))
        assertFalse(hideScrollBackOnFingerUp(scrollReaderOpen = true, target = 1, page = 0.995f))
        // A lagged page read still looks parked while the settle target has left.
        assertTrue(hideScrollBackOnFingerUp(scrollReaderOpen = true, target = 0, page = 1f))
        assertTrue(hideScrollBackOnFingerUp(scrollReaderOpen = true, target = 1, page = 0.7f))
        assertFalse(hideScrollBackOnFingerUp(scrollReaderOpen = false, target = 0, page = 0f))
    }
}
