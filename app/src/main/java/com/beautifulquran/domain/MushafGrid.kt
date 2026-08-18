package com.beautifulquran.domain

import kotlin.math.pow

/**
 * The leaf's vertical grid.
 *
 * A book has one vertical measure and everything hangs on it: the leading. Set
 * the running head, the gutters, the folio and the text on the same unit and
 * the page reads as one thing; give each its own dp and the leaf drifts a
 * little every time a dimension is touched — which is exactly how this page
 * came to carry eight unrelated constants.
 *
 * The unit is one line's pitch, and the leaf is [SLOTS] of them:
 *
 * ```
 *  0.3  running head — the smallest hand on the leaf
 * 0.32  head gutter — enough paper to stand the head off the text
 *  15   the revelation — the Madinah page's own grid
 *  0.5  folio, its figure centred in the band
 *  ---
 * 16.12
 * ```
 *
 * The furniture is trimmed to what it actually needs to read as furniture,
 * because the leaf's height is what caps the type: measured on the reference
 * page the measure would carry 97px of glyph and the well only 57px, so every
 * unit the chrome does not use is type size. The horizontal margins are already
 * a bare 10dp; fourteen percent of the leaf on vertical chrome was the odd one
 * out.
 *
 * The type well is exactly fifteen units whatever the leaf's height, so the
 * mushaf's own grid is the page's grid, and the chrome is measured in the same
 * breath as the scripture rather than against it.
 */
object MushafGrid {
    /**
     * The running head is one small label at each fore-edge, so it asks for
     * rather less than a line of the revelation's own pitch.
     *
     * It used to carry the chapter and the juzʾ twice over, Arabic above Latin,
     * and a full unit went on saying the same thing in two hands. One line of
     * wayfinding needs about half of that, and the paper saved goes to the
     * text, which is what the leaf is for.
     */
    const val RUNNING_HEAD = 0.30f

    /**
     * Paper between the head and the first line of revelation. A running head
     * that sits closer reads as part of the text block rather than as a head
     * standing over it — but it does not need a whole line of the revelation's
     * pitch to do that. Just under half a unit still clears the head band by
     * more than the head's own type is tall, and the rest goes to the text.
     */
    const val HEAD_GUTTER = 0.32f
    const val TEXT_LINES = MUSHAF_LINES_PER_PAGE
    /**
     * Nothing between the last line and the folio band. The folio's figure is
     * centred in its own band, which already sets it clear of the text — and
     * the unit this used to take is worth more to the revelation, where it
     * keeps the type about 6% larger than an eighteen-unit leaf that spends it
     * here. The folio band itself is two thirds of a unit: the figure is set
     * two steps down the scale, so a whole unit was mostly paper around it.
     */
    const val TAIL = 0f
    const val FOLIO = 0.50f

    /** The whole leaf, in units. */
    const val SLOTS = RUNNING_HEAD + HEAD_GUTTER + TEXT_LINES + TAIL + FOLIO

    /** One line's pitch: the leaf divided by its slots. */
    fun unitPx(leafHeightPx: Float): Float =
        (leafHeightPx / SLOTS).coerceAtLeast(1f)

    /** The height of the text well: fifteen lines of the same unit. */
    fun textWellPx(leafHeightPx: Float): Float = unitPx(leafHeightPx) * TEXT_LINES
}

/**
 * The leaf's type scale.
 *
 * One ratio, one anchor. The anchor is the page's own hand — the fitted glyph
 * size the mushaf is set in — and every other size on the leaf is a whole step
 * down from it at a major third (5:4), the interval a book has used for
 * headings and marginalia since Renaissance printing. Sizes chosen by eye do
 * not compose: 12sp beside 8.5sp beside 9sp is three unrelated decisions, and
 * the eye reads the disagreement even when it cannot name it.
 *
 * ```
 *   0   the revelation, and a chapter's name in its panel — the same hand
 *  -2   the folio's figure
 *  -3   its Latin gloss
 *  -4   the running head
 * ```
 */
object MushafType {
    /** Major third. */
    const val RATIO = 1.25f

    /** [steps] below the page's hand — negative steps are smaller. */
    fun stepPx(glyphPx: Float, steps: Int): Float =
        glyphPx * RATIO.toDouble().pow(steps.toDouble()).toFloat()

    /** A chapter's name is written in the page's own hand. */
    const val TITLE = 0

    /** Wayfinding: the folio's figure. */
    const val FURNITURE = -2

    /** The Latin gloss beside it. */
    const val GLOSS = -3

    /**
     * The running head — the smallest hand on the leaf.
     *
     * It stood with the folio at [FURNITURE] and read as a heading, which is
     * two steps too loud for a chapter name you glance at once a page turn and
     * then ignore for fifteen lines. Two steps down is a 36% cut, and the band
     * it sits in shrinks with it: paper the revelation takes.
     */
    const val HEAD = -4
}
