package com.beautifulquran.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareImageScaleTest {

    @Test
    fun `one verse stays 1x`() {
        assertEquals(1f, shareImageScale(1, 1080, 800), 0.001f)
        assertEquals(1080, shareImageWidthPx(1f))
    }

    @Test
    fun `each added verse raises scale until the budget binds`() {
        assertEquals(2f, shareImageScale(2, 1080, 900), 0.001f)
        assertEquals(3f, shareImageScale(3, 1080, 900), 0.001f)
        assertEquals(2160, shareImageWidthPx(2f))
        assertEquals(3240, shareImageWidthPx(3f))
    }

    @Test
    fun `empty gather still 1x`() {
        assertEquals(1f, shareImageScale(0, 1080, 800), 0.001f)
    }

    @Test
    fun `long gather never shrinks below 1x and stays inside the pixel budget`() {
        val scale = shareImageScale(10, 1080, 8000)
        assertTrue(scale >= 1f)
        assertTrue(scale < 10f)
        val pixels = 1080f * scale * 8000f * scale
        assertTrue(pixels <= ShareImageRenderer.MAX_BITMAP_PIXELS + 1f)
        assertTrue(1080f * scale <= ShareImageRenderer.MAX_BITMAP_SIDE + 1f)
        assertTrue(8000f * scale <= ShareImageRenderer.MAX_BITMAP_SIDE + 1f)
    }
}
