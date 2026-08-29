package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class VerseBookmarkRibbonTest {
    @Test
    fun placeRibbon_reservesTheBookmarkClothOrOutlineLane() {
        assertEquals(8f, placeRibbonInsetDp(false, 8f, 11f), 0f)
        assertEquals(22f, placeRibbonInsetDp(true, 8f, 11f), 0f)
    }

    @Test
    fun placeRibbon_isNarrowerThanTheTappableBookmark() {
        assertEquals(7.92f, placeRibbonWidthDp(11f), 0.001f)
    }
}
