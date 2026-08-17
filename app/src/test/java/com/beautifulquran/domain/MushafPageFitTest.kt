package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MushafPageFitTest {









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
    fun `the hand never grows to fill paper`() {
        // The book is set at one size. A line short of the measure stays at
        // that size and closes the remainder with its word gaps — it is not
        // opened up, however much paper it has to cross.
        assertEquals(1f, mushafLineFill(naturalWidthPx = 964f, measureWidthPx = 964f), 0f)
        assertEquals(1f, mushafLineFill(naturalWidthPx = 910f, measureWidthPx = 964f), 0f)
        assertEquals(1f, mushafLineFill(naturalWidthPx = 400f, measureWidthPx = 964f), 0f)
    }

    @Test
    fun `only a line wider than the measure closes up, and barely`() {
        // Anchoring the size on the widest line the book contains leaves about
        // one line in a hundred over the measure; those close by a few percent
        // rather than clipping.
        assertEquals(0.95f, mushafLineFill(naturalWidthPx = 1015f, measureWidthPx = 964f), 0.005f)
        // The floor is advisory: a line far past the measure still closes all
        // the way, because ink over the fore-edge is clipped and a sliced ayah
        // mark is worse than a line set a little tighter.
        assertEquals(964f / 1500f, mushafLineFill(naturalWidthPx = 1500f, measureWidthPx = 964f), 0.001f)
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
