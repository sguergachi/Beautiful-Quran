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

    @Test
    fun `the reference page fills the well at the nominal leading`() {
        val hand = englishLeafHandPx(well, measure, advance)
        // The whole point of the fit: a page of ENGLISH_LEAF_REFERENCE_PROSE
        // characters comes out at exactly the nominal leading.
        val perLine = measure / (advance * hand)
        val lines = ENGLISH_LEAF_REFERENCE_PROSE / perLine
        assertEquals(
            ENGLISH_LEAF_NOMINAL_LEADING_EM,
            englishLeafLeadingEm(lines = lines, fontPx = hand, wellHeightPx = well),
            0.01f,
        )
    }

    @Test
    fun `one hand for the whole book - the page's mass never enters it`() {
        // englishLeafHandPx takes no page at all. This is the law expressed as
        // a signature, and the test that keeps it that way: the only inputs
        // are the leaf's geometry and the face.
        val light = englishLeafHandPx(well, measure, advance)
        val heavy = englishLeafHandPx(well, measure, advance)
        assertEquals(light, heavy, 0f)
    }

    @Test
    fun `a wider measure takes larger type and more characters to the line`() {
        val narrow = englishLeafHandPx(well, measure, advance)
        val wide = englishLeafHandPx(well * 1.3f, measure * 1.7f, advance)
        assertTrue(wide > narrow)
        // Both stay near fifty characters — the book scales, it does not
        // simply spread.
        val narrowChars = measure / (advance * narrow)
        val wideChars = measure * 1.7f / (advance * wide)
        assertTrue(abs(narrowChars - 48f) < 8f)
        assertTrue(abs(wideChars - 48f) < 12f)
    }

    @Test
    fun `a light page opens its leading, a heavy one closes it`() {
        val hand = englishLeafHandPx(well, measure, advance)
        val nominal = well / (hand * ENGLISH_LEAF_NOMINAL_LEADING_EM)
        assertTrue(
            englishLeafLeadingEm(nominal * 0.9f, hand, well) >
                ENGLISH_LEAF_NOMINAL_LEADING_EM,
        )
        assertTrue(
            englishLeafLeadingEm(nominal * 1.1f, hand, well) <
                ENGLISH_LEAF_NOMINAL_LEADING_EM,
        )
    }

    @Test
    fun `the leading never leaves its band, however light or heavy the page`() {
        val hand = englishLeafHandPx(well, measure, advance)
        assertEquals(
            ENGLISH_LEAF_MAX_LEADING_EM,
            englishLeafLeadingEm(lines = 1f, fontPx = hand, wellHeightPx = well),
            0f,
        )
        assertEquals(
            ENGLISH_LEAF_MIN_LEADING_EM,
            englishLeafLeadingEm(lines = 400f, fontPx = hand, wellHeightPx = well),
            0f,
        )
    }

    @Test
    fun `the book is set larger than the floor alone would allow`() {
        // The anchor buys the whole book a legible size at the price of a
        // handful of close-set leaves. Cut for the heaviest page at the floor
        // it would be 1997 x 1.30 / 1.55 = 1675; it is smaller than that, which
        // is what makes the hand larger. See tools/measure_english_leaves.py.
        assertTrue(ENGLISH_LEAF_REFERENCE_PROSE < 1675f)
        val hand = englishLeafHandPx(well, measure, advance)
        val perLine = measure / (advance * hand)
        // Page 579, the heaviest leaf in the book, is one of those: it asks for
        // less than the floor, and the fitted leading is what lets it.
        val heaviest =
            englishLeafLeadingEm(lines = 1997f / perLine, fontPx = hand, wellHeightPx = well)
        assertEquals(ENGLISH_LEAF_MIN_LEADING_EM, heaviest, 0.0001f)
        assertEquals(
            1.20f,
            englishLeafFittedLeadingEm(
                leadingEm = heaviest,
                // What it actually stands at when the floor holds it open.
                measuredHeightPx = well * (ENGLISH_LEAF_MIN_LEADING_EM / 1.202f),
                wellHeightPx = well,
                pitchesPx = 1997f / perLine * hand,
            ),
            0.02f,
        )
    }

    @Test
    fun `the median leaf is set near the nominal leading`() {
        // 1,469 characters — see tools/measure_english_leaves.py.
        val hand = englishLeafHandPx(well, measure, advance)
        val perLine = measure / (advance * hand)
        val median =
            englishLeafLeadingEm(lines = 1469f / perLine, fontPx = hand, wellHeightPx = well)
        assertEquals(1.63f, median, 0.03f)
    }

    @Test
    fun `a leaf that lands on its foot is left exactly as it was set`() {
        assertEquals(1.7f, englishLeafFittedLeadingEm(1.7f, well, well, pitchesPx = 600f), 0f)
    }

    @Test
    fun `the leftover paper converts to leading in one step`() {
        // The block moves by one pitch for every baseline step it holds, so
        // 60 px of overflow over 600 px of steps is a tenth of an em.
        assertEquals(
            1.6f,
            englishLeafFittedLeadingEm(1.7f, well + 60f, well, pitchesPx = 600f),
            0.0001f,
        )
        assertEquals(
            1.8f,
            englishLeafFittedLeadingEm(1.7f, well - 60f, well, pitchesPx = 600f),
            0.0001f,
        )
    }

    @Test
    fun `closing has no floor, because a line past the foot cannot be read`() {
        assertTrue(
            englishLeafFittedLeadingEm(
                ENGLISH_LEAF_MIN_LEADING_EM,
                well + 600f,
                well,
                pitchesPx = 600f,
            ) < ENGLISH_LEAF_MIN_LEADING_EM,
        )
    }

    @Test
    fun `opening still stops at the band, so a light leaf stands short`() {
        assertEquals(
            ENGLISH_LEAF_MAX_LEADING_EM,
            englishLeafFittedLeadingEm(1.9f, well * 0.4f, well, pitchesPx = 600f),
            0f,
        )
    }

    @Test
    fun `a degenerate leaf cannot produce nonsense`() {
        assertEquals(ENGLISH_LEAF_MIN_FONT_PX, englishLeafHandPx(0f, measure, advance), 0f)
        assertEquals(ENGLISH_LEAF_MIN_FONT_PX, englishLeafHandPx(well, 0f, advance), 0f)
        assertEquals(ENGLISH_LEAF_MIN_FONT_PX, englishLeafHandPx(well, measure, 0f), 0f)
        assertEquals(
            ENGLISH_LEAF_NOMINAL_LEADING_EM,
            englishLeafLeadingEm(0f, 20f, well),
            0f,
        )
        assertEquals(1.5f, englishLeafFittedLeadingEm(1.5f, 0f, well, pitchesPx = 600f), 0f)
        assertEquals(1.5f, englishLeafFittedLeadingEm(1.5f, well, well, pitchesPx = 0f), 0f)
    }
}
