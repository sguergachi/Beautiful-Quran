package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class VerseBookmarkRibbonTest {
    @Test
    fun bookmarkRibbon_movesInsideTheScreenEdgePlaceMarker() {
        assertEquals(8f, bookmarkRibbonInsetDp(false, 8f, 11f), 0f)
        assertEquals(18.92f, bookmarkRibbonInsetDp(true, 8f, 11f), 0.001f)
    }

    @Test
    fun placeRibbon_isNarrowerThanTheTappableBookmark() {
        assertEquals(7.92f, placeRibbonWidthDp(11f), 0.001f)
    }
}
