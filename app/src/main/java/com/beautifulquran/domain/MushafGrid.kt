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
 *  0.4  folio, its figure centred in the band
 *  ---
 * 16.02
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
     * Paper between the last line and the folio. The folio belongs to the
     * gap between the leaf's text and the dial's hairline — centred in it,
     * not glued to the text — so the band carries the air that sets it
     * there.
     */
    const val TAIL = 0.35f
    const val FOLIO = 0.40f

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
 *  -3   the folio's own Arabic figure
 *  -4   the running head, and the Latin numeral glossing the folio
 * ```
 *
 * The two figures of the folio stand a step apart because they are two
 * scripts: a Hafs numeral set at a Latin numeral's size reads smaller than it,
 * and the step is what makes the pair match to the eye rather than on paper.
 */
object MushafType {
    /** Major third. */
    const val RATIO = 1.25f

    /** [steps] below the page's hand — negative steps are smaller. */
    fun stepPx(glyphPx: Float, steps: Int): Float =
        glyphPx * RATIO.toDouble().pow(steps.toDouble()).toFloat()

    /** A chapter's name is written in the page's own hand. */
    const val TITLE = 0

    /** The folio's own Arabic figure. */
    const val FOLIO_FIGURE = -3

    /**
     * The leaf's smallest hand: the running head, and the Latin numeral that
     * glosses the folio — one size for everything you read once a page turn
     * and then ignore for fifteen lines.
     *
     * The head stood two steps under the revelation and the folio's figures
     * one step under that, which made wayfinding the second loudest thing on
     * the page. Both came down; the bands they sit in came down with them, and
     * the paper is the revelation's.
     */
    const val HEAD = -4
}
