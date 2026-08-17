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
 *   1   running head
 *   1   head gutter
 *  15   the revelation — the Madinah page's own grid
 *   1   tail, the last line to the folio
 *   1   folio
 *  ---
 *  19
 * ```
 *
 * The type well is exactly fifteen units whatever the leaf's height, so the
 * mushaf's own grid is the page's grid, and the chrome is measured in the same
 * breath as the scripture rather than against it.
 */
object MushafGrid {
    const val RUNNING_HEAD = 1
    const val HEAD_GUTTER = 1
    const val TEXT_LINES = MUSHAF_LINES_PER_PAGE
    const val TAIL = 1
    const val FOLIO = 1

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
 *  -2   running head, folio figures
 *  -3   their Latin gloss
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

    /** Wayfinding: running head, folio. */
    const val FURNITURE = -2

    /** The Latin gloss under each. */
    const val GLOSS = -3
}
