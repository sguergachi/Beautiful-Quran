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
    fun `the hand only gives once the leading has closed all the way`() {
        val hand = englishLeafHandPx(well, measure, advance)
        val fitsTight = well / (hand * ENGLISH_LEAF_MIN_LEADING_EM)
        assertEquals(1f, englishLeafHandGive(fitsTight, hand, well), 0f)
        assertEquals(1f, englishLeafHandGive(fitsTight * 0.5f, hand, well), 0f)
        assertTrue(englishLeafHandGive(fitsTight * 1.2f, hand, well) < 1f)
    }

    @Test
    fun `the give is the square root of the overflow, not the overflow`() {
        val hand = 20f
        // A leaf that overflows the tightest leading by 21%.
        val lines = well / (hand * ENGLISH_LEAF_MIN_LEADING_EM) * 1.21f
        // √(1/1.21), not 1/1.21: narrowing the hand takes both the line count
        // and the line height with it, so the block comes down by the square.
        assertEquals(0.9091f, englishLeafHandGive(lines, hand, well), 0.001f)
    }

    @Test
    fun `the give has a floor`() {
        val hand = 20f
        val absurd = well / (hand * ENGLISH_LEAF_MIN_LEADING_EM) * 100f
        assertEquals(ENGLISH_LEAF_MIN_HAND, englishLeafHandGive(absurd, hand, well), 0f)
    }

    @Test
    fun `the heaviest leaf in the book stays clear of that floor`() {
        val hand = englishLeafHandPx(well, measure, advance)
        // Page 579 carries 1,997 characters against the 1,440 the hand is
        // fitted to, and is the heaviest in the book — see
        // tools/measure_english_leaves.py.
        val referenceLines = well / (hand * ENGLISH_LEAF_NOMINAL_LEADING_EM)
        val worst = englishLeafHandGive(
            lines = referenceLines * 1997f / ENGLISH_LEAF_REFERENCE_PROSE,
            fontPx = hand,
            wellHeightPx = well,
        )
        assertEquals(0.927f, worst, 0.002f)
        assertTrue(worst > ENGLISH_LEAF_MIN_HAND)
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
        assertEquals(1f, englishLeafHandGive(0f, 20f, well), 0f)
    }
}
