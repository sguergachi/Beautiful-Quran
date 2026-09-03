package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHitFlashTest {

    @Test
    fun `one wash becomes four full-word breaths`() {
        assertEquals(4, SearchHitFlash.PULSES)
        assertEquals(870L, SearchHitFlash.breathMs())
        assertEquals(3_340L, SearchHitFlash.totalMs())
        assertEquals(0f, SearchHitFlash.REST_ALPHA)
        assertTrue(SearchHitFlash.totalMs() < 3_500L)
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
