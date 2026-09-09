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
            val spent = (bands.runningHead + bands.headGutter + bands.well + bands.tail) * unit
            assertEquals(leaf, spent, 0.5f)
        }
        // The English leaf is the canonical grid; the Arabic one buys a row.
        assertEquals(MushafGrid.SLOTS, MUSHAF_ENGLISH_BANDS.slots, 0.0001f)
    }

    @Test
    fun `neither leaf pays for the folio, and both keep a foot`() {
        // The folio stands in the dial's head air. The foot stayed on the leaf,
        // because the text reaches it now that the leaf is measured rather than
        // counted, and a page whose descenders reach its page number has none.
        assertEquals(
            MushafGrid.RUNNING_HEAD + MushafGrid.HEAD_GUTTER + MushafGrid.TEXT_LINES +
                MushafGrid.TAIL,
            MUSHAF_ENGLISH_BANDS.slots,
            0.0001f,
        )
        assertEquals(MushafGrid.TAIL, MUSHAF_ENGLISH_BANDS.tail, 0f)
        assertEquals(MushafGrid.TAIL, MUSHAF_ARABIC_BANDS.tail, 0f)
        // The English leaf is still ahead of where the folio's band left it —
        // it gave up 0.40 for the folio and 0.35 for a tail, and buys the foot
        // back for 0.55. The Arabic leaf pays a little: its tail was 0.05, and
        // a leaf whose revelation reaches the page number has no foot at all.
        assertTrue(MUSHAF_ENGLISH_BANDS.well / MUSHAF_ENGLISH_BANDS.slots > 15f / 17.05f)
        assertTrue(MUSHAF_ARABIC_BANDS.tail > 0.05f)
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
        // The English well is no longer a round fifteen — it took what the
        // gutter gave up (ENGLISH_HEAD_GUTTER) — so the sixteenth row is the
        // thing to assert, not the difference.
        assertTrue(MUSHAF_ARABIC_BANDS.well > MUSHAF_ENGLISH_BANDS.well)
        assertTrue(MUSHAF_ARABIC_BANDS.headGutter < MUSHAF_ENGLISH_BANDS.headGutter)
    }

    @Test
    fun `the English gutter is one line of its own prose, not one Arabic unit`() {
        // The canonical unit is the Arabic line's pitch and this gutter stands
        // over English prose, whose line is smaller — so a full unit was 1.7
        // lines of air and read as a hole under the head. Same rule, measured
        // against the block it separates.
        assertEquals(ENGLISH_HEAD_GUTTER, MUSHAF_ENGLISH_BANDS.headGutter, 0f)
        assertTrue(MUSHAF_ENGLISH_BANDS.headGutter < MushafGrid.HEAD_GUTTER)
        // Whatever the gutter gave up, the well took: the leaf is the same leaf.
        assertEquals(
            MushafGrid.HEAD_GUTTER - ENGLISH_HEAD_GUTTER,
            MUSHAF_ENGLISH_BANDS.well - MushafGrid.TEXT_LINES,
            0.0001f,
        )
        assertTrue(MUSHAF_ENGLISH_BANDS.well > MushafGrid.TEXT_LINES)
    }

    @Test
    fun `the leaf picks its bands by the language it is set in`() {
        assertEquals(MUSHAF_ENGLISH_BANDS, mushafLeafBands(english = true))
        assertEquals(MUSHAF_ARABIC_BANDS, mushafLeafBands(english = false))
    }
}
