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
    fun `the hand is cut so the heaviest leaf in the book fits at the tightest leading`() {
        // The anchor's defining property, and the whole reason the type never
        // has to change: page 579 carries 1,997 characters — see
        // tools/measure_english_leaves.py — and comes out at the floor, not
        // past it.
        val hand = englishLeafHandPx(well, measure, advance)
        val perLine = measure / (advance * hand)
        assertEquals(
            ENGLISH_LEAF_MIN_LEADING_EM,
            englishLeafLeadingEm(lines = 1997f / perLine, fontPx = hand, wellHeightPx = well),
            0.01f,
        )
    }

    @Test
    fun `a leaf that already fits is left exactly as it was set`() {
        assertEquals(1.7f, englishLeafFittedLeadingEm(1.7f, well - 1f, well), 0f)
        assertEquals(1.7f, englishLeafFittedLeadingEm(1.7f, well, well), 0f)
    }

    @Test
    fun `a leaf that stands past the foot closes its leading by exactly the overflow`() {
        // The block's height is proportional to its leading, so one step lands
        // it on the foot — and it may close past the comfortable floor to do
        // it, because a line past the foot is revelation the reader cannot see.
        assertEquals(1.6f, englishLeafFittedLeadingEm(2.0f, well * 1.25f, well), 0.0001f)
        assertTrue(
            englishLeafFittedLeadingEm(ENGLISH_LEAF_MIN_LEADING_EM, well * 1.1f, well) <
                ENGLISH_LEAF_MIN_LEADING_EM,
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
        assertEquals(1.5f, englishLeafFittedLeadingEm(1.5f, 0f, well), 0f)
    }
}
