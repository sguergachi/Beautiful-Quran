package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Both hands spend the same leaf without overflow or unaccounted paper. */
class MushafLeafBandsTest {
    @Test
    fun `each hand spends its leaf exactly across window heights`() {
        for (english in listOf(false, true)) {
            val bands = mushafLeafBands(english)
            for (height in listOf(1600f, 2004f, 2300f)) {
                val spent = bands.slots * bands.unitPx(height)
                assertEquals(height, spent, 0.5f)
            }
        }
    }

    @Test
    fun `English and Arabic share their running head gutter well and foot`() {
        assertEquals(MUSHAF_ARABIC_BANDS, MUSHAF_ENGLISH_BANDS)
        assertEquals(mushafLeafBands(false), mushafLeafBands(true))
        assertEquals(MushafGrid.RUNNING_HEAD, MUSHAF_ENGLISH_BANDS.runningHead, 0f)
        assertEquals(0.50f, MUSHAF_ENGLISH_BANDS.headGutter, 0f)
    }

    @Test
    fun `both hands spend the old lower folio and foot on type`() {
        for (bands in listOf(MUSHAF_ARABIC_BANDS, MUSHAF_ENGLISH_BANDS)) {
            assertEquals(0f, bands.tail, 0f)
            assertEquals(MUSHAF_DISPLAY_LINES_PER_PAGE.toFloat(), bands.well, 0f)
            assertTrue(bands.well / bands.slots > MushafGrid.TEXT_LINES / MushafGrid.SLOTS)
        }
    }
}
