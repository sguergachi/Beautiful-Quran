package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHitFlashTest {

    @Test
    fun `pulses reuse the ink-engine repeat wash timings`() {
        val cycleMs = SearchHitFlash.cycleMs()
        assertEquals(2, SearchHitFlash.PULSES)
        assertEquals(
            InkEngine.tuning.repeatSweepMs + InkEngine.tuning.repeatFadeOutMs,
            cycleMs,
        )
        assertEquals(cycleMs.toLong() * SearchHitFlash.PULSES, SearchHitFlash.totalMs())
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
