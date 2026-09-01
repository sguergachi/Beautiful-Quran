package com.beautifulquran.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingPlaceRibbonMotionTest {
    @Test
    fun unfurl_waitsForACompletedReaderReturnWithAPlace() {
        assertFalse(shouldUnfurlReadingPlaceRibbon(false, true, true))
        assertFalse(shouldUnfurlReadingPlaceRibbon(true, false, true))
        assertFalse(shouldUnfurlReadingPlaceRibbon(true, true, false))
        assertTrue(shouldUnfurlReadingPlaceRibbon(true, true, true))
    }
}
