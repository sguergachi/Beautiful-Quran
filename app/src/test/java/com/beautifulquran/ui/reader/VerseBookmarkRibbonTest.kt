package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class VerseBookmarkRibbonTest {
    @Test
    fun bookmarkRibbon_reservesTheScreenEdgePlaceLane() {
        assertEquals(18.92f, bookmarkRibbonInsetDp(8f, 11f), 0.001f)
    }

    @Test
    fun placeRibbon_isNarrowerThanTheTappableBookmark() {
        assertEquals(7.92f, placeRibbonWidthDp(11f), 0.001f)
    }
}
