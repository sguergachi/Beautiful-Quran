package com.beautifulquran.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareImageFitTest {

    @Test
    fun `short card keeps its measured height`() {
        val fit = shareImageFit(800, ShareImageRenderer.MAX_HEIGHT_PX)
        assertEquals(800, fit.heightPx)
        assertFalse(fit.pinFooter)
    }

    @Test
    fun `card at the cap is not pinned`() {
        val fit = shareImageFit(
            ShareImageRenderer.MAX_HEIGHT_PX,
            ShareImageRenderer.MAX_HEIGHT_PX,
        )
        assertEquals(ShareImageRenderer.MAX_HEIGHT_PX, fit.heightPx)
        assertFalse(fit.pinFooter)
    }

    @Test
    fun `tall gather becomes a capped sheet that pins the chapter footer`() {
        val fit = shareImageFit(4_800, ShareImageRenderer.MAX_HEIGHT_PX)
        assertEquals(ShareImageRenderer.MAX_HEIGHT_PX, fit.heightPx)
        assertTrue(fit.pinFooter)
    }

    @Test
    fun `empty measure still produces a 1px sheet`() {
        val fit = shareImageFit(0, ShareImageRenderer.MAX_HEIGHT_PX)
        assertEquals(1, fit.heightPx)
        assertFalse(fit.pinFooter)
    }
}
