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
    fun `every full line takes justify - the name-list lines included`() {
        // The Quran's rule: every full line of the page is flush end to end.
        // The name-list lines (Al-Ahzab 62-65) carry few, long words and were
        // left short by the old five-word gate.
        assertTrue(mushafLineJustifies(5))
        assertTrue(mushafLineJustifies(9))
        assertTrue(mushafLineJustifies(2))
        assertTrue(mushafLineJustifies(4))
        assertTrue(!mushafLineJustifies(1))
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
        // A well tall enough that the measure is what decides the size.
        val well = 964f / MUSHAF_DESIGN_LINE_EM * 15f * MUSHAF_LINE_INK_EM + 1f
        val a = mushafUniformFontPx(measureWidthPx = 964f, wellHeightPx = well, slots = 15)
        val b = mushafUniformFontPx(measureWidthPx = 964f, wellHeightPx = well, slots = 15)
        assertEquals(a, b, 0f)
        assertEquals(964f / MUSHAF_DESIGN_LINE_EM, a, 0.01f)
    }

    @Test
    fun `a short well caps the size so fifteen lines still fit`() {
        val font = mushafUniformFontPx(measureWidthPx = 2000f, wellHeightPx = 1400f, slots = 15)
        // The size a line's own ink will fit in the paper the well gives it.
        assertEquals(1400f / (15f * MUSHAF_LINE_INK_EM), font, 0.01f)
    }

    @Test
    fun `the reader's size nudge cannot push a line's ink out of its slot`() {
        // Applied after the well had spoken, a larger text size grew the ink
        // while its slot stayed as tall, and descenders went into the line
        // below. The largest nudge must still fit the paper the well gives.
        val well = 1400f
        val big = mushafUniformFontPx(
            measureWidthPx = 2000f,
            wellHeightPx = well,
            slots = 15,
            fontScale = 1.12f,
        )
        assertTrue("ink overflows its slot", big * MUSHAF_LINE_INK_EM <= well / 15f + 0.01f)
    }

    @Test
    fun `a line inside the measure is never touched`() {
        // Three lines in four fill by gap alone; the letterforms are left be.
        assertEquals(1f, mushafLineCondense(naturalWidthPx = 964f, measureWidthPx = 964f), 0f)
        assertEquals(1f, mushafLineCondense(naturalWidthPx = 910f, measureWidthPx = 964f), 0f)
        assertEquals(1f, mushafLineCondense(naturalWidthPx = 400f, measureWidthPx = 964f), 0f)
    }

    @Test
    fun `a line past the measure is condensed, not resized`() {
        // Measured over the mushaf, half the condensed lines need under 3.5%.
        assertEquals(0.95f, mushafLineCondense(naturalWidthPx = 1015f, measureWidthPx = 964f), 0.005f)
        // The typographic expectation: the lines that ask for real condensing
        // are rare, and this is about as tight as the corpus gets.
        assertTrue(
            "the anomalous runs should sit near the expected worst case",
            mushafLineCondense(naturalWidthPx = 1120f, measureWidthPx = 964f) >=
                MUSHAF_MIN_LINE_CONDENSE,
        )
    }

    @Test
    fun `no line is ever left wider than the measure`() {
        // A line is drawn one node per word, so a line left wider than its
        // measure does not overhang tidily: its cells overrun, the weight
        // spacers between them collapse, and glyphs paint over their
        // neighbours — a word landed on the circled ١٢ of page 79 this way.
        // Fit is therefore absolute, including for the runs that ask to be set
        // tighter than MUSHAF_MIN_LINE_CONDENSE (53 lines of the mushaf do).
        listOf(1000f, 1100f, 1500f, 2200f).forEach { natural ->
            val k = mushafLineCondense(naturalWidthPx = natural, measureWidthPx = 964f)
            assertTrue("$natural overflowed", natural * k <= 964f + 0.01f)
        }
    }

    @Test
    fun `a page that cannot afford its pitch keeps its share of the well`() {
        // Tall type in a short well: the slot must not exceed the well's own
        // share, or fifteen lines would run off the leaf.
        val slot = mushafLineSlotPx(pageHeightPx = 900f, slots = 15, fontPx = 56f)
        assertEquals(60f, slot, 0.01f)
    }

    // The box compositor. It is not what sets a leaf once its own face has
    // loaded — mushafInkLineFit does, by the white each join actually carries —
    // but it is what sets every leaf on the frame before that, so the page the
    // reader first sees is this one's work.
    //
    // Set at a hundred px of type throughout, so the em bounds read straight
    // off the constants: the page's space is 18, its floor 13, its ceiling 30,
    // and the space a full line may open to once the letters are spent, 45.

    @Test
    fun `a line with no space in it is set at the page's own and left short`() {
        // One word, or none: there is no join to give or take, so there is
        // nothing to compose. It stands at the page's space and centred, which
        // is the same answer MushafQcfPageLine gives a line too short to
        // justify at all — see mushafLineJustifies.
        val single = mushafLineFit(
            inkWidthPx = 500f,
            gapCount = 0,
            measureWidthPx = 800f,
            fontPx = 100f,
        )
        assertEquals(1f, single.scale, 1e-4f)
        assertEquals(MUSHAF_WORD_GAP_EM * 100f, single.gapPx, 1e-4f)
        assertTrue(!single.flush)
        // And a line with no ink cannot be measured against anything.
        val empty = mushafLineFit(
            inkWidthPx = 0f,
            gapCount = 4,
            measureWidthPx = 800f,
            fontPx = 100f,
        )
        assertEquals(1f, empty.scale, 1e-4f)
        assertTrue(!empty.flush)
    }

    @Test
    fun `a line a little too wide closes its space and keeps its letters`() {
        // 700 of ink and four joins wants 772 of an 760 measure. The space
        // gives first: 15 px a join, still above the 13 floor, and no letter
        // is touched.
        val fit = mushafLineFit(
            inkWidthPx = 700f,
            gapCount = 4,
            measureWidthPx = 760f,
            fontPx = 100f,
        )
        assertEquals(1f, fit.scale, 1e-4f)
        assertEquals(15f, fit.gapPx, 1e-4f)
        assertTrue(fit.flush)
    }

    @Test
    fun `a line too dense for its floor narrows its letters, not its space`() {
        // 740 of ink in 760 leaves 5 px a join, under the floor. The space
        // stops at 13 and the letters give the rest: a line whose words touch
        // is not a line.
        val fit = mushafLineFit(
            inkWidthPx = 740f,
            gapCount = 4,
            measureWidthPx = 760f,
            fontPx = 100f,
        )
        assertEquals(MUSHAF_FLOOR_WORD_GAP_EM * 100f, fit.gapPx, 1e-4f)
        assertEquals((760f - 4 * 13f) / 740f, fit.scale, 1e-4f)
        assertTrue(fit.scale < 1f)
        assertTrue(fit.flush)
    }

    @Test
    fun `a line with room to spare opens its space before its letters`() {
        // 600 of ink in 700: 25 px a join, inside the 30 ceiling, letters as
        // drawn.
        val fit = mushafLineFit(
            inkWidthPx = 600f,
            gapCount = 4,
            measureWidthPx = 700f,
            fontPx = 100f,
        )
        assertEquals(1f, fit.scale, 1e-4f)
        assertEquals(25f, fit.gapPx, 1e-4f)
        assertTrue(fit.flush)
    }

    @Test
    fun `a line past the space's ceiling holds it there and stretches`() {
        // 600 of ink in 740 would want 35 a join. The space stops at 30 and
        // the letters take the remaining 20 px: a 1.033 stretch.
        val fit = mushafLineFit(
            inkWidthPx = 600f,
            gapCount = 4,
            measureWidthPx = 740f,
            fontPx = 100f,
        )
        assertEquals(MUSHAF_MAX_WORD_GAP_EM * 100f, fit.gapPx, 1e-4f)
        assertEquals((740f - 4 * 30f) / 600f, fit.scale, 1e-4f)
        assertTrue(fit.scale > 1f)
        assertTrue(fit.flush)
    }

    @Test
    fun `a full line the letters cannot reach is carried the rest by its space`() {
        // The last move. 600 of ink in 860 needs a 1.233 stretch, past the
        // bound. Rather than set a full line short, the letters hold at 1.15
        // and the space opens to 42.5 — inside the 45 a full line may take.
        // Without this step such a line was left short even though 1.15 and a
        // wider space would have filled it.
        val fit = mushafLineFit(
            inkWidthPx = 600f,
            gapCount = 4,
            measureWidthPx = 860f,
            fontPx = 100f,
        )
        assertEquals(MUSHAF_MAX_LINE_SCALE, fit.scale, 1e-4f)
        assertEquals(42.5f, fit.gapPx, 1e-4f)
        assertTrue(fit.flush)
        // And exactly at the bound the line is still flush: 870 puts the
        // space on 45 on the nose.
        assertTrue(
            mushafLineFit(
                inkWidthPx = 600f,
                gapCount = 4,
                measureWidthPx = 870f,
                fontPx = 100f,
            ).flush,
        )
    }

    @Test
    fun `a chapter's last words stand short and centred rather than as islands`() {
        // 600 of ink in 900 would leave 52.5 a join even with the letters at
        // their bound — past the 45 where a line stops reading as text and
        // starts reading as a row of islands. Four or five words closing a
        // chapter are set at the page's own space and centred instead.
        val fit = mushafLineFit(
            inkWidthPx = 600f,
            gapCount = 4,
            measureWidthPx = 900f,
            fontPx = 100f,
        )
        assertEquals(1f, fit.scale, 1e-4f)
        assertEquals(MUSHAF_WORD_GAP_EM * 100f, fit.gapPx, 1e-4f)
        assertTrue(!fit.flush)
    }

    @Test
    fun `a flush line reaches both margins exactly, however it got there`() {
        // The one thing every branch above has to agree on, and the only
        // assertion that catches an arithmetic slip in any of them: a line
        // called flush fills its measure to the pixel. A mushaf page's lines
        // reach both margins, and a leaf is drawn one node per word — a line
        // that overruns paints its words over their neighbours.
        val fontPx = 100f
        for (ink in listOf(300f, 500f, 600f, 700f, 740f, 800f, 900f, 1200f)) {
            for (gaps in 1..8) {
                for (measure in listOf(600f, 760f, 860f, 964f)) {
                    val fit = mushafLineFit(ink, gaps, measure, fontPx)
                    if (!fit.flush) continue
                    assertEquals(
                        "ink=$ink gaps=$gaps measure=$measure",
                        measure,
                        fit.scale * ink + gaps * fit.gapPx,
                        0.01f,
                    )
                }
            }
        }
    }

    @Test
    fun `the space never opens past what a full line may take`() {
        // The other side of the same sweep: whatever the line needed, the
        // space it was set on is one a page can carry. Past 0.45 em the return
        // measured almost nil and the line reads as islands.
        val fontPx = 100f
        for (ink in listOf(300f, 500f, 600f, 700f, 740f, 800f, 900f, 1200f)) {
            for (gaps in 1..8) {
                for (measure in listOf(600f, 760f, 860f, 964f)) {
                    val fit = mushafLineFit(ink, gaps, measure, fontPx)
                    assertTrue(
                        "ink=$ink gaps=$gaps measure=$measure gap=${fit.gapPx}",
                        fit.gapPx <= MUSHAF_STRETCH_WORD_GAP_EM * fontPx + 0.01f,
                    )
                    assertTrue(
                        "ink=$ink gaps=$gaps measure=$measure scale=${fit.scale}",
                        fit.scale <= MUSHAF_MAX_LINE_SCALE + 1e-4f,
                    )
                }
            }
        }
    }
}
