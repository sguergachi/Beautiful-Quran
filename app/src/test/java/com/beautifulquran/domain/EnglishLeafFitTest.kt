package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The English leaf's setting laws, held to the numbers in
 * `tools/measure_english_leaves.py`. A phone-shaped leaf is used throughout,
 * in device pixels as the fit takes them: a 3x screen's 764 dp well against
 * its 300 dp measure, and EB Garamond's average character advance of about
 * 0.40 em.
 */
class EnglishLeafFitTest {

    private val well = 764f * 3f
    private val measure = 300f * 3f
    private val advance = 0.40f

    /** Characters to the line at the book's hand — the fit's own arithmetic. */
    private fun charsPerLine(): Float {
        val hand = englishLeafHandPx(well, measure, advance)
        return measure / (advance * hand)
    }

    /** Where a leaf of [mass] characters ends, as a share of the well. */
    private fun fill(mass: Float): Float {
        val hand = englishLeafHandPx(well, measure, advance)
        return mass / charsPerLine() * hand * ENGLISH_LEAF_LEADING_EM / well
    }

    @Test
    fun `one hand for the whole book - the page's mass never enters it`() {
        // englishLeafHandPx takes no page at all. This is the law expressed as
        // a signature, and the test that keeps it that way: the only inputs
        // are the leaf's geometry and the face.
        assertEquals(
            englishLeafHandPx(well, measure, advance),
            englishLeafHandPx(well, measure, advance),
            0f,
        )
    }

    @Test
    fun `a wider measure takes larger type and more characters to the line`() {
        val narrow = englishLeafHandPx(well, measure, advance)
        val wide = englishLeafHandPx(well * 1.3f, measure * 1.7f, advance)
        assertTrue(wide > narrow)
        // Both stay near fifty characters — the book scales, it does not
        // simply spread.
        assertTrue(abs(charsPerLine() - 48f) < 10f)
        assertTrue(abs(measure * 1.7f / (advance * wide) - 48f) < 14f)
    }

    @Test
    fun `the hand is cut so the heaviest leaf in the book fills its well`() {
        // The whole of rule 3: with one leading for the book, the heaviest leaf
        // is what sets the type. Page 579 carries 1,997 characters — see
        // tools/measure_english_leaves.py — and comes out just inside the well,
        // the margin being the 3% the anchor holds back for the estimate.
        val heaviest = fill(1997f)
        assertTrue("heaviest leaf overflows: $heaviest", heaviest <= 1f)
        assertTrue("heaviest leaf wastes the page: $heaviest", heaviest > 0.94f)
    }

    @Test
    fun `every other leaf ends short of the foot, by exactly how much lighter it is`() {
        // The price of one leading, and the thing that replaces it: the foot.
        assertEquals(0.71f, fill(1469f), 0.02f) // median
        assertEquals(0.62f, fill(1286f), 0.02f) // tenth percentile
        assertEquals(0.51f, fill(1055f), 0.02f) // first percentile
        // And it is linear in the mass, because nothing else varies.
        assertEquals(2f, fill(1400f) / fill(700f), 0.001f)
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
        // A leaf with paper to spare keeps the book's leading and its white.
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
        assertEquals(ENGLISH_LEAF_MIN_FONT_PX, englishLeafHandPx(0f, measure, advance), 0f)
        assertEquals(ENGLISH_LEAF_MIN_FONT_PX, englishLeafHandPx(well, 0f, advance), 0f)
        assertEquals(ENGLISH_LEAF_MIN_FONT_PX, englishLeafHandPx(well, measure, 0f), 0f)
        assertEquals(
            ENGLISH_LEAF_LEADING_EM,
            englishLeafFittedLeadingEm(ENGLISH_LEAF_LEADING_EM, well * 2f, well, pitchesPx = 0f),
            0f,
        )
    }
}
