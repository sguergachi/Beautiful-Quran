package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class VerseBookmarkRibbonTest {
    @Test
    fun placeRibbon_sitsBesideRubyWithoutOverlap() {
        assertEquals(8f, placeRibbonInsetDp(false, 8f, 11f), 0f)
        assertEquals(22f, placeRibbonInsetDp(true, 8f, 11f), 0f)
    }
}
