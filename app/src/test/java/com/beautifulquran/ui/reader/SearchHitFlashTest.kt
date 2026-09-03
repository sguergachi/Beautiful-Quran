package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHitFlashTest {

    @Test
    fun `five side wipes stay distinct and brief`() {
        assertEquals(5, SearchHitFlash.WIPES)
        assertEquals(480L, SearchHitFlash.wipeMs())
        assertEquals(2_720L, SearchHitFlash.totalMs())
        assertTrue(SearchHitFlash.REST_MS >= 60L)
        assertTrue(SearchHitFlash.FEATHER in 0.2f..0.4f)
        assertTrue(SearchHitFlash.totalMs() < 3_000L)
    }

    @Test
    fun `pulse emphasis is strong but stays tight to the glyph`() {
        assertTrue(SearchHitFlash.EMPHASIS_GLOW_ALPHA >= 0.9f)
        assertTrue(SearchHitFlash.EMPHASIS_GLOW_RADIUS in 1f..1.5f)
    }

    @Test
    fun `mushaf waits for its leaf and never for the unmounted scrolling list`() {
        assertTrue(
            SearchHitFlash.isTargetSettled(
                mushafMode = true,
                scrollingVerseSettled = false,
                mushafLeafSettled = true,
            ),
        )
        assertFalse(
            SearchHitFlash.isTargetSettled(
                mushafMode = true,
                scrollingVerseSettled = true,
                mushafLeafSettled = false,
            ),
        )
    }

    @Test
    fun `scrolling reader waits for verse geometry and ignores mushaf state`() {
        assertTrue(
            SearchHitFlash.isTargetSettled(
                mushafMode = false,
                scrollingVerseSettled = true,
                mushafLeafSettled = false,
            ),
        )
        assertFalse(
            SearchHitFlash.isTargetSettled(
                mushafMode = false,
                scrollingVerseSettled = false,
                mushafLeafSettled = true,
            ),
        )
    }
}
