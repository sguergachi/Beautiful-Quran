package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The leaf's grid. Whatever a setting does with the paper, it has to spend all
 * of it and no more: a leaf whose bands sum over its height runs the folio off
 * the page, and one that sums under it leaves a strip nothing accounts for.
 */
class MushafLeafBandsTest {

    @Test
    fun `both settings spend the leaf exactly`() {
        assertEquals(MushafGrid.SLOTS, MUSHAF_ARABIC_BANDS.slots, 0.0001f)
        assertEquals(MushafGrid.SLOTS, MUSHAF_ENGLISH_BANDS.slots, 0.0001f)
    }

    @Test
    fun `the running head is the same band in both, so the language does not move it`() {
        assertEquals(MUSHAF_ARABIC_BANDS.runningHead, MUSHAF_ENGLISH_BANDS.runningHead, 0f)
        assertEquals(MushafGrid.RUNNING_HEAD, MUSHAF_ARABIC_BANDS.runningHead, 0f)
    }

    @Test
    fun `the Arabic leaf buys a sixteenth row from its gutters`() {
        assertEquals(
            MUSHAF_DISPLAY_LINES_PER_PAGE.toFloat(),
            MUSHAF_ARABIC_BANDS.well,
            0f,
        )
        assertEquals(MUSHAF_ENGLISH_BANDS.well + 1f, MUSHAF_ARABIC_BANDS.well, 0.0001f)
        assertTrue(MUSHAF_ARABIC_BANDS.headGutter < MUSHAF_ENGLISH_BANDS.headGutter)
        assertTrue(MUSHAF_ARABIC_BANDS.tail < MUSHAF_ENGLISH_BANDS.tail)
    }

    @Test
    fun `the English leaf keeps the canonical gutters, which were sized for Latin ink`() {
        assertEquals(MushafGrid.HEAD_GUTTER, MUSHAF_ENGLISH_BANDS.headGutter, 0f)
        assertEquals(MushafGrid.TAIL, MUSHAF_ENGLISH_BANDS.tail, 0f)
        assertEquals(MushafGrid.FOLIO, MUSHAF_ENGLISH_BANDS.folio, 0f)
        assertEquals(MushafGrid.TEXT_LINES.toFloat(), MUSHAF_ENGLISH_BANDS.well, 0f)
    }

    @Test
    fun `the leaf picks its bands by the language it is set in`() {
        assertEquals(MUSHAF_ENGLISH_BANDS, mushafLeafBands(english = true))
        assertEquals(MUSHAF_ARABIC_BANDS, mushafLeafBands(english = false))
    }
}
