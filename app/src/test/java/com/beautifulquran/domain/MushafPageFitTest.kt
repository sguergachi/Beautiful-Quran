package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MushafPageFitTest {

    @Test
    fun `fifteen lines fill the page height`() {
        val height = 15f * MUSHAF_LINE_EM * 32f
        val font = mushafFontPx(height, lineCount = 15)
        assertEquals(32f, font, 0.01f)
    }

    @Test
    fun `short Fatiha page still fits its own line count`() {
        val height = 7f * MUSHAF_LINE_EM * 40f
        val font = mushafFontPx(height, lineCount = 7)
        assertEquals(40f, font, 0.01f)
    }

    @Test
    fun `font scale nudges without escaping the clamp`() {
        val height = 15f * MUSHAF_LINE_EM * 32f
        assertTrue(mushafFontPx(height, 15, fontScale = 1.12f) > 32f)
        assertEquals(MUSHAF_MAX_FONT_PX, mushafFontPx(10_000f, 2), 0.01f)
        assertEquals(MUSHAF_MIN_FONT_PX, mushafFontPx(1f, 15), 0.01f)
    }

    @Test
    fun `width fit leaves a short line at the height size`() {
        val fitted = mushafFontPxFittingWidth(32f, longestLineWidthPx = 200f, pageWidthPx = 400f)
        assertEquals(32f, fitted, 0.01f)
    }

    @Test
    fun `width fit shrinks a line that overruns the page`() {
        val fitted = mushafFontPxFittingWidth(40f, longestLineWidthPx = 800f, pageWidthPx = 400f)
        assertEquals(MUSHAF_MIN_FONT_PX, fitted, 0.01f)
    }

    @Test
    fun `width fit scales proportionally when still above the floor`() {
        val fitted = mushafFontPxFittingWidth(48f, longestLineWidthPx = 600f, pageWidthPx = 500f)
        assertEquals(40f, fitted, 0.01f)
    }

    @Test
    fun `measured line height scales the probe font to fill the page`() {
        // 15 lines × 60px = 900px page; probe 40px drew 80px tall → 30px font.
        assertEquals(
            30f,
            mushafFontPxFromMeasuredLine(
                pageHeightPx = 900f,
                lineCount = 15,
                measuredLineHeightPx = 80f,
                probeFontPx = 40f,
            ),
            0.01f,
        )
    }

    @Test
    fun `match-width scales a short line up to the page`() {
        assertEquals(40f, mushafFontPxMatchWidth(20f, 200f, 400f), 0.01f)
    }

    @Test
    fun `font preload is the settled page plus neighbours, 1-based`() {
        assertEquals(listOf(1, 2, 3), mushafFontPreloadPages(settledIndex = 0, pageCount = 604))
        assertEquals(
            listOf(96, 97, 98, 99, 100),
            mushafFontPreloadPages(settledIndex = 97, pageCount = 604),
        )
        assertEquals(listOf(602, 603, 604), mushafFontPreloadPages(settledIndex = 603, pageCount = 604))
    }

    @Test
    fun `only lines with enough words take full justify`() {
        assertTrue(mushafLineJustifies(5))
        assertTrue(mushafLineJustifies(9))
        assertTrue(!mushafLineJustifies(2))
        assertTrue(!mushafLineJustifies(4))
    }

    @Test
    fun `gap spacing is leftover width split across word gaps`() {
        assertEquals(0f, mushafGapSpacingPx(400f, 400f, gapCount = 8), 0.01f)
        assertEquals(0f, mushafGapSpacingPx(300f, 400f, gapCount = 0), 0.01f)
        assertEquals(10f, mushafGapSpacingPx(300f, 400f, gapCount = 10), 0.01f)
        assertEquals(
            20f * MUSHAF_MAX_GAP_EM,
            mushafGapSpacingPx(100f, 400f, gapCount = 2, fontPx = 20f),
            0.01f,
        )
    }

    @Test
    fun `a full page fills the well and a short page keeps its leading`() {
        // 15 lines divide the whole well; 8 lines take 8 of the same slots
        // and centre, rather than stretching one page's leading to fit.
        assertEquals(15, mushafGridSlots(15))
        assertEquals(15, mushafGridSlots(8))
        assertEquals(15, mushafGridSlots(1))
        // A basmalah preface can push a page past the grid; it packs tighter.
        assertEquals(16, mushafGridSlots(16))
        assertEquals(15, mushafGridSlots(0))
    }

    @Test
    fun `leading comes from the type, not the leftover height`() {
        // A width-bound page: the well is far taller than fifteen lines of
        // this size need, so the pitch stays the printed one and the spare
        // height falls into the margins instead of pulling the lines apart.
        val slot = mushafLineSlotPx(pageHeightPx = 1833f, slots = 15, fontPx = 56f)
        assertEquals(56f * MUSHAF_LINE_PITCH_EM, slot, 0.01f)
        assertTrue(slot < 1833f / 15f)
    }

    @Test
    fun `every leaf of the book is set at the same size`() {
        // The size is a property of the measure, not of the page: two leaves
        // in the same well get the same type however long their lines run.
        val a = mushafUniformFontPx(measureWidthPx = 964f, wellHeightPx = 1833f, slots = 15)
        val b = mushafUniformFontPx(measureWidthPx = 964f, wellHeightPx = 1833f, slots = 15)
        assertEquals(a, b, 0f)
        assertEquals(964f / MUSHAF_DESIGN_LINE_EM, a, 0.01f)
    }

    @Test
    fun `a short well caps the size so fifteen lines still fit`() {
        val font = mushafUniformFontPx(measureWidthPx = 2000f, wellHeightPx = 900f, slots = 15)
        assertEquals(900f / (15f * MUSHAF_LINE_PITCH_EM), font, 0.01f)
    }

    @Test
    fun `a line is fitted to the measure by its own hand`() {
        // Exactly the measure: written as the book is set.
        assertEquals(1f, mushafLineFill(naturalWidthPx = 964f, measureWidthPx = 964f), 0f)
        // Sparse line: opened up to reach the margin, rather than leaving
        // rivers of paper between its words.
        assertEquals(1.06f, mushafLineFill(naturalWidthPx = 910f, measureWidthPx = 964f), 0.005f)
        // Crowded line: closed up to fit.
        assertEquals(0.95f, mushafLineFill(naturalWidthPx = 1015f, measureWidthPx = 964f), 0.005f)
        // Never opened past the bound, so no line reads as a different hand.
        assertEquals(
            MUSHAF_MAX_LINE_STRETCH,
            mushafLineFill(naturalWidthPx = 400f, measureWidthPx = 964f),
            0.001f,
        )
    }

    @Test
    fun `no line is ever left wider than the measure`() {
        // Fitting the leaf beats holding the hand: ink over the fore-edge is
        // clipped, and the ayah mark at the line end comes out sliced.
        listOf(1000f, 1100f, 1500f, 2200f).forEach { natural ->
            val fill = mushafLineFill(naturalWidthPx = natural, measureWidthPx = 964f)
            assertTrue("$natural overflowed", natural * fill <= 964f + 0.01f)
        }
    }

    @Test
    fun `a page that cannot afford its pitch keeps its share of the well`() {
        // Tall type in a short well: the slot must not exceed the well's own
        // share, or fifteen lines would run off the leaf.
        val slot = mushafLineSlotPx(pageHeightPx = 900f, slots = 15, fontPx = 56f)
        assertEquals(60f, slot, 0.01f)
    }
}
