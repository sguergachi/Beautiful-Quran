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
        val three = shareImageScale(3, 1080, 900)
        assertTrue(three in 2f..3f)
        assertEquals(2160, shareImageWidthPx(2f))
    }

    @Test
    fun `empty gather still 1x`() {
        assertEquals(1f, shareImageScale(0, 1080, 800), 0.001f)
    }

    @Test
    fun `stub wrap-height does not explode scale`() {
        assertEquals(1f, shareImageScale(20, 1080, 1), 0.001f)
        assertEquals(1f, shareImageScale(20, 1080, 63), 0.001f)
    }

    @Test
    fun `long gather stays 1x and fit brings it inside the canvas budget`() {
        val scale = shareImageScale(10, 1080, 8000)
        assertEquals(1f, scale, 0.001f)
        val fit = shareImageFit(1080, 8000)
        assertTrue(fit.widthPx * fit.heightPx.toLong() <= ShareImageRenderer.MAX_BITMAP_PIXELS)
        assertTrue(fit.heightPx <= ShareImageRenderer.MAX_BITMAP_SIDE)
    }

    @Test
    fun `fit never exceeds the canvas budget`() {
        val tall = shareImageFit(1080, 40_000)
        assertTrue(tall.widthPx * tall.heightPx.toLong() <= ShareImageRenderer.MAX_BITMAP_PIXELS)
        assertTrue(tall.widthPx <= ShareImageRenderer.MAX_BITMAP_SIDE)
        assertTrue(tall.heightPx <= ShareImageRenderer.MAX_BITMAP_SIDE)
        val ok = shareImageFit(1080, 800)
        assertEquals(1080, ok.widthPx)
        assertEquals(800, ok.heightPx)
    }
}
