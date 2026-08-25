package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MushafGridTest {

    @Test
    fun `fifteen of the leaf's units are revelation and the chrome takes the rest`() {
        assertEquals(16.75f, MushafGrid.SLOTS, 0.001f)
        assertEquals(15, MushafGrid.TEXT_LINES)
        // The head and its gutter stand clear without spending a second line.
        assertEquals(
            MushafGrid.SLOTS - MushafGrid.TEXT_LINES,
            MushafGrid.RUNNING_HEAD + MushafGrid.HEAD_GUTTER + MushafGrid.TAIL + MushafGrid.FOLIO,
            0.001f,
        )
    }

    @Test
    fun `every band on the leaf is a whole number of units`() {
        val leaf = 2004f
        val unit = MushafGrid.unitPx(leaf)
        val bands = listOf(
            MushafGrid.RUNNING_HEAD,
            MushafGrid.HEAD_GUTTER,
            MushafGrid.TEXT_LINES.toFloat(),
            MushafGrid.TAIL,
            MushafGrid.FOLIO,
        )
        assertEquals(leaf, bands.sum() * unit, 0.5f)
        assertEquals(MushafGrid.textWellPx(leaf), unit * 15, 0.01f)
    }

    @Test
    fun `the type scale is one ratio from the page's own hand`() {
        val glyph = 57f
        // A chapter's name is written in the same hand as the revelation.
        assertEquals(glyph, MushafType.stepPx(glyph, MushafType.TITLE), 0.01f)
        // The folio's figure is three steps down, wayfinding four.
        assertEquals(
            glyph / (1.25f * 1.25f * 1.25f),
            MushafType.stepPx(glyph, MushafType.FOLIO_FIGURE),
            0.01f,
        )
        assertEquals(
            glyph / (1.25f * 1.25f * 1.25f * 1.25f),
            MushafType.stepPx(glyph, MushafType.HEAD),
            0.01f,
        )
        // Each step is the same interval as the last: that is what makes it a
        // scale rather than three sizes that happen to differ.
        val a = MushafType.stepPx(glyph, -1) / MushafType.stepPx(glyph, 0)
        val b = MushafType.stepPx(glyph, -2) / MushafType.stepPx(glyph, -1)
        assertEquals(a, b, 0.0001f)
        // The running head is the smallest hand on the leaf.
        assertTrue(
            MushafType.stepPx(glyph, MushafType.HEAD) <
                MushafType.stepPx(glyph, MushafType.FOLIO_FIGURE),
        )
    }
}
