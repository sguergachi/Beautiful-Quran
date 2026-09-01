package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class VerseBookmarkRibbonTest {
    @Test
    fun onlyReaderBookmarkRibbon_reservesTheScreenEdgePlaceLane() {
        assertEquals(8f, bookmarkRibbonInsetDp(false, 8f, 11f), 0f)
        assertEquals(18.92f, bookmarkRibbonInsetDp(true, 8f, 11f), 0.001f)
        assertEquals(8f, placeRibbonInsetDp(false, 8f), 0f)
        assertEquals(4f, placeRibbonInsetDp(true, 8f), 0f)
    }

    @Test
    fun placeRibbon_isNarrowerThanTheTappableBookmark() {
        assertEquals(7.92f, placeRibbonWidthDp(11f), 0.001f)
        assertEquals(7.92f, placeRibbonTapGuardWidthDp(true, 11f), 0.001f)
        assertEquals(0f, placeRibbonTapGuardWidthDp(false, 11f), 0f)
    }

    @Test
    fun gatherOrdinal_sitsInTheBookmarkNubSlot() {
        assertEquals(11f, BookmarkRibbonWidthDp)
        assertEquals(14f, BookmarkNubLengthDp)
        assertEquals(16f, GatherOrdinalSp)
        assertEquals(24f, BookmarkTopInsetDp)
        assertEquals(
            18.92f,
            bookmarkRibbonInsetDp(true, BookmarkEdgeInsetDp, BookmarkRibbonWidthDp),
            0.001f,
        )
    }

    @Test
    fun completedPlaceUnfurl_consumesOnlyItsOwnGeneration() {
        assertEquals(0, remainingUnfurlSignal(current = 3, consumed = 3))
        assertEquals(4, remainingUnfurlSignal(current = 4, consumed = 3))
    }
}
