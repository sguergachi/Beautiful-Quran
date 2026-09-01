package com.beautifulquran.share

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareImageStitchTest {

    @Test
    fun `strips stack to the sum of their heights`() {
        assertEquals(0, shareImageStitchHeight(intArrayOf()))
        assertEquals(800, shareImageStitchHeight(intArrayOf(800)))
        assertEquals(800 + 640 + 120, shareImageStitchHeight(intArrayOf(800, 640, 120)))
    }

    @Test
    fun `negative strip heights do not shrink the sheet`() {
        assertEquals(100, shareImageStitchHeight(intArrayOf(100, -4)))
    }
}
