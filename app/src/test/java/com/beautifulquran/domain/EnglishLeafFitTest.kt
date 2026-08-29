package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the book's one hand and one leading are arrived at. */
class EnglishLeafFitTest {

    private val well = 764f * 3f

    @Test
    fun `the reference block is a leaf's worth of real prose`() {
        val block = englishLeafReferenceBlock()
        assertEquals(
            (ENGLISH_LEAF_CAPACITY_CHARS * ENGLISH_LEAF_REFERENCE_MARGIN).toInt(),
            block.length,
        )
        assertTrue(block.startsWith("Blackening the skins"))
        // Prose, not a repeated word: it is where the lines break that decides
        // how much paper a page takes.
        assertTrue(block.count { it == ' ' } > block.length / 8)
    }

    @Test
    fun `the specimen is set in the same hand as the book it measures`() {
        // The old specimen was one plain sentence repeated, and it was not
        // ordinary: 3.7 letters to the word where the translation averages 4.4.
        // Short words pack tighter and waste less at the end of a ragged line,
        // so it set 43 characters to the line where the book sets 40.3 — the
        // hand came out 6% small and 108 leaves overflowed their well.
        val words = ENGLISH_LEAF_SPECIMEN.trim().split(" ").filter { it.isNotEmpty() }
        val meanWord = words.sumOf { it.length }.toDouble() / words.size
        assertTrue("specimen words average $meanWord", meanWord > 4.2 && meanWord < 4.6)
        // And it is prose of this translation, brackets and all, because that
        // is what has to fit.
        assertTrue(ENGLISH_LEAF_SPECIMEN.contains("[angels]"))
    }

    @Test
    fun `the hand is the size at which a leaf's worth of prose fills the well`() {
        // Nothing estimated: the caller lays the reference block out at a probe
        // size and this turns what it measured into the size that would fit.
        // Height goes as the square of the hand, so a block that came out twice
        // the well means a hand of 1/root-2.
        assertEquals(
            ENGLISH_LEAF_PROBE_FONT_PX * 0.7071f,
            englishLeafHandPx(ENGLISH_LEAF_PROBE_FONT_PX, well * 2f, well),
            0.01f,
        )
        assertEquals(
            ENGLISH_LEAF_PROBE_FONT_PX,
            englishLeafHandPx(ENGLISH_LEAF_PROBE_FONT_PX, well, well),
            0.001f,
        )
        // A block that came out short asks for a larger hand.
        assertTrue(englishLeafHandPx(40f, well * 0.5f, well) > 40f)
    }

    @Test
    fun `one hand for the whole book - no page enters it`() {
        // englishLeafHandPx takes no page at all: a probe, what that probe
        // measured, and the well. That is the law expressed as a signature.
        assertEquals(
            englishLeafHandPx(40f, well * 1.2f, well),
            englishLeafHandPx(40f, well * 1.2f, well),
            0f,
        )
    }

    @Test
    fun `a leaf that lands inside its well is drawn on the book's leading`() {
        assertEquals(
            ENGLISH_LEAF_LEADING_EM,
            englishLeafFittedLeadingEm(
                ENGLISH_LEAF_LEADING_EM,
                measuredHeightPx = well - 1f,
                wellHeightPx = well,
                pitchesPx = 600f,
            ),
            0f,
        )
    }

    @Test
    fun `only an overflow moves it, and only by the overflow`() {
        // The block moves one pitch for every baseline step it holds, so 60 px
        // over 600 px of steps is a tenth of an em — closing, never opening.
        assertEquals(
            ENGLISH_LEAF_LEADING_EM - 0.1f,
            englishLeafFittedLeadingEm(
                ENGLISH_LEAF_LEADING_EM,
                measuredHeightPx = well + 60f,
                wellHeightPx = well,
                pitchesPx = 600f,
            ),
            0.0001f,
        )
        assertEquals(
            ENGLISH_LEAF_LEADING_EM,
            englishLeafFittedLeadingEm(
                ENGLISH_LEAF_LEADING_EM,
                measuredHeightPx = well * 0.4f,
                wellHeightPx = well,
                pitchesPx = 600f,
            ),
            0f,
        )
    }

    @Test
    fun `a degenerate leaf cannot produce nonsense`() {
        assertEquals(ENGLISH_LEAF_MIN_FONT_PX, englishLeafHandPx(0f, well, well), 0f)
        assertEquals(ENGLISH_LEAF_MIN_FONT_PX, englishLeafHandPx(40f, 0f, well), 0f)
        assertEquals(ENGLISH_LEAF_MIN_FONT_PX, englishLeafHandPx(40f, well, 0f), 0f)
        assertEquals(
            ENGLISH_LEAF_LEADING_EM,
            englishLeafFittedLeadingEm(ENGLISH_LEAF_LEADING_EM, well * 2f, well, pitchesPx = 0f),
            0f,
        )
    }
}
