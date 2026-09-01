package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The leaf's grid. Whatever a setting does with the paper, it has to spend all
 * of it and no more: a leaf whose bands sum over its height runs its last line
 * off the page, and one that sums under it leaves a strip nothing accounts for.
 * Each setting spends against its own total, since the folio left the leaf and
 * the two no longer come to the same figure.
 */
class MushafLeafBandsTest {

    @Test
    fun `each setting spends its own leaf exactly`() {
        val leaf = 2004f
        for (bands in listOf(MUSHAF_ARABIC_BANDS, MUSHAF_ENGLISH_BANDS)) {
            val unit = bands.unitPx(leaf)
            val spent = (bands.runningHead + bands.headGutter + bands.well) * unit
            assertEquals(leaf, spent, 0.5f)
        }
        // The English leaf is the canonical grid; the Arabic one buys a row.
        assertEquals(MushafGrid.SLOTS, MUSHAF_ENGLISH_BANDS.slots, 0.0001f)
    }

    @Test
    fun `neither leaf pays for the folio any more`() {
        // It stands in the dial's head air, so the leaf's height ends with the
        // revelation and the English hand gets a twenty-third line for it.
        assertEquals(
            MushafGrid.RUNNING_HEAD + MushafGrid.HEAD_GUTTER + MushafGrid.TEXT_LINES,
            MUSHAF_ENGLISH_BANDS.slots,
            0.0001f,
        )
        assertTrue(MUSHAF_ENGLISH_BANDS.well / MUSHAF_ENGLISH_BANDS.slots > 15f / 17.05f)
        assertTrue(MUSHAF_ARABIC_BANDS.well / MUSHAF_ARABIC_BANDS.slots > 16f / 17.05f)
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
    }

    @Test
    fun `the English leaf keeps the canonical gutter, which was sized for Latin ink`() {
        assertEquals(MushafGrid.HEAD_GUTTER, MUSHAF_ENGLISH_BANDS.headGutter, 0f)
        assertEquals(MushafGrid.TEXT_LINES.toFloat(), MUSHAF_ENGLISH_BANDS.well, 0f)
    }

    @Test
    fun `the leaf picks its bands by the language it is set in`() {
        assertEquals(MUSHAF_ENGLISH_BANDS, mushafLeafBands(english = true))
        assertEquals(MUSHAF_ARABIC_BANDS, mushafLeafBands(english = false))
    }
}
