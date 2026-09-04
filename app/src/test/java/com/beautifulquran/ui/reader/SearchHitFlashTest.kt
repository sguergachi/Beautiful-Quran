package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHitFlashTest {

    @Test
    fun `three complete full-word breaths fade in and out`() {
        assertEquals(3, SearchHitFlash.BREATHS)
        assertEquals(810L, SearchHitFlash.breathMs())
        assertEquals(2_550L, SearchHitFlash.totalMs())
        assertTrue(SearchHitFlash.INHALE_MS < SearchHitFlash.EXHALE_MS)
        assertTrue(SearchHitFlash.totalMs() < 2_600L)
    }

    @Test
    fun `pulse emphasis is strong but stays tight to the glyph`() {
        assertTrue(SearchHitFlash.EMPHASIS_GLOW_ALPHA >= 0.9f)
        assertTrue(SearchHitFlash.EMPHASIS_GLOW_RADIUS in 1f..1.5f)
        assertEquals(0.4f, SearchHitFlash.BACKGROUND_ALPHA)
        assertEquals(280, SearchHitFlash.FOCUS_FADE_MS)
    }

    @Test
    fun `translator-only prefix match flashes the complete visible word`() {
        val text = "a companion [in Hellfire]"

        assertEquals(listOf(16..23), SearchHitFlash.textRanges(text, "hell"))
        assertEquals(listOf(16..23), SearchHitFlash.textRanges(text, "\"Hell\""))
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
