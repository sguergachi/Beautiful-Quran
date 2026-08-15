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
}
