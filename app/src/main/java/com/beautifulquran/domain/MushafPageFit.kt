package com.beautifulquran.domain

/** Line-height multiple of the Hafs face used to pack a mushaf page. */
const val MUSHAF_LINE_EM = 2.2f

/**
 * Line pitch as a multiple of the fitted glyph size — the leading the printed
 * Madinah page is set on. A phone's text well is proportionally taller than
 * that page, so dividing the well by fifteen sets the lines further apart than
 * the mushaf ever does and the page reads loose and airy. Leading belongs to
 * the type, not to the leftover height: pitch from the glyph size, and let the
 * spare height fall into the head and tail margins instead.
 */
const val MUSHAF_LINE_PITCH_EM = 1.85f

/**
 * Line box height for a fitted page: the printed pitch, never more than the
 * well's own share of the height (a short page must still fit).
 */
fun mushafLineSlotPx(pageHeightPx: Float, slots: Int, fontPx: Float): Float {
    if (slots <= 0) return pageHeightPx.coerceAtLeast(1f)
    val share = pageHeightPx / slots
    val pitch = fontPx * MUSHAF_LINE_PITCH_EM
    return if (pitch <= 0f) share else minOf(share, pitch)
}

/** Every Madinah page is set on the same 15-line grid. */
const val MUSHAF_LINES_PER_PAGE = 15

/**
 * Slots to divide the text well by. A full page fills it; a short page
 * (al-Fātiḥah, a surah's last lines) keeps the *same* leading as a full
 * page and sits in the middle of the well rather than stretching across
 * it — one grid for all 604 pages, as the printed mushaf is set. Pages
 * carrying a basmalah preface run past 15 and simply pack tighter.
 */
fun mushafGridSlots(slotCount: Int): Int =
    maxOf(slotCount, MUSHAF_LINES_PER_PAGE).coerceAtLeast(1)

/** Smallest / largest fitted page size, in px, so a short page never balloons. */
const val MUSHAF_MIN_FONT_PX = 28f
const val MUSHAF_MAX_FONT_PX = 128f

/**
 * The mushaf's measure, in em of the QCF V2 page faces — the width one line of
 * the book is set to.
 *
 * A printed mushaf is one book: every one of the 604 leaves carries the same
 * hand at the same size, and the reader must never see the type change as the
 * page turns. So the size cannot be fitted to each page's own longest line,
 * which is what made a leaf's glyphs jump by a third from one page to the next.
 *
 * The QCF word glyphs are not pre-justified — measured with HarfBuzz over all
 * ~9,000 lines of the mushaf (tools/measure_mushaf_lines.py), a line's glyph
 * run spans 14.1 em (p10) through 15.6 em (p50) to 18.7 em (p99), and no
 * constant word space closes that spread (searched: the best leaves 17.8%).
 *
 * So a page cannot both hold one hand and have every line reach both margins
 * by spacing alone, and something must give. It is not the hand: a leaf whose
 * type changes size line by line reads as a fault, however cleverly fitted.
 * The measure is taken from the widest line the book contains, so the type is
 * one size everywhere and no line can overrun; each line then closes what it
 * lacks with its word gaps, as a compositor does. This is where the reference
 * implementations land too — quran.com fixes the size per scale step and lets
 * the line be what it is.
 */
const val MUSHAF_DESIGN_LINE_EM = 18.7f

/**
 * The one type size for every leaf: the measure divided by the design line,
 * never taller than the well can carry fifteen lines of. [fontScale] is the
 * reader's nudge around it, not a free resize.
 */
fun mushafUniformFontPx(
    measureWidthPx: Float,
    wellHeightPx: Float,
    slots: Int,
    fontScale: Float = 1f,
): Float {
    if (measureWidthPx <= 0f || wellHeightPx <= 0f || slots <= 0) return MUSHAF_MIN_FONT_PX
    val fromMeasure = measureWidthPx / MUSHAF_DESIGN_LINE_EM
    val fromWell = wellHeightPx / (slots * MUSHAF_LINE_PITCH_EM)
    return (minOf(fromMeasure, fromWell) * fontScale.coerceIn(0.88f, 1.12f))
        .coerceIn(MUSHAF_MIN_FONT_PX, MUSHAF_MAX_FONT_PX)
}

/**
 * How a line is fitted to the measure: the scale that makes its glyph run span
 * the page exactly.
 *
 * This is the difference between a printed mushaf and a stretched one. The
 * page is justified by the *hand* — a crowded line written a little tighter, a
 * sparse one a little more openly — not by pulling the words apart and leaving
 * rivers of paper between them. Scaling the line also keeps the glyphs as large
 * as the measure allows, which is what makes the ink read heavy and full rather
 * than thin and small.
 *
 * Bounded above so no line ever reads as a different hand from the one above
 * it, and whatever that leaves over is closed by the word gaps. There is no
 * such bound below: a line that will not fit must be written to fit, because
 * the alternative is ink over the fore-edge, where the leaf clips it and the
 * circled ayah mark riding at the line end comes out sliced in half.
 */
fun mushafLineFill(naturalWidthPx: Float, measureWidthPx: Float): Float {
    if (naturalWidthPx <= 0f || measureWidthPx <= 0f) return 1f
    return (measureWidthPx / naturalWidthPx)
        .coerceIn(MUSHAF_MIN_LINE_SQUEEZE, MUSHAF_MAX_LINE_STRETCH)
        // Never wider than the measure, whatever the bounds say.
        .coerceAtMost(measureWidthPx / naturalWidthPx)
}

/**
 * Floor for the handful of lines wider than the measure even at the book's own
 * size — a guard against clipping, not a design device. Anchoring on the p99
 * line leaves about one line in a hundred to close up, by a few percent.
 */
const val MUSHAF_MIN_LINE_SQUEEZE = 0.93f

/** A line is never opened up: the hand does not grow to fill paper. */
const val MUSHAF_MAX_LINE_STRETCH = 1.0f



/**
 * Extra letter-spacing (px) to put on each inter-word space so a mushaf
 * line fills [pageWidthPx]. Zero when the line already spans the page or
 * there is nothing to stretch.
 */


/** Never stretch a word gap past this fraction of the page font. */
const val MUSHAF_MAX_GAP_EM = 0.55f

/** Short lines stay naturally spaced; full justify needs enough words. */
const val MUSHAF_JUSTIFY_MIN_WORDS = 5

fun mushafLineJustifies(tokenCount: Int): Boolean = tokenCount >= MUSHAF_JUSTIFY_MIN_WORDS

/**
 * 1-based QCF page numbers to warm around the settled pager index.
 * Radius 2 covers the composed neighbour plus one more so a fling
 * does not [Typeface.createFromAsset] on the UI thread.
 */
fun mushafFontPreloadPages(
    settledIndex: Int,
    pageCount: Int,
    radius: Int = 2,
): List<Int> {
    val settled = settledIndex + 1
    return ((settled - radius)..(settled + radius)).filter { it in 1..pageCount }
}

fun mushafGapSpacingPx(
    naturalWidthPx: Float,
    pageWidthPx: Float,
    gapCount: Int,
    fontPx: Float = 0f,
): Float {
    if (gapCount <= 0 || pageWidthPx <= naturalWidthPx) return 0f
    val raw = (pageWidthPx - naturalWidthPx) / gapCount
    if (fontPx <= 0f) return raw
    return raw.coerceAtMost(fontPx * MUSHAF_MAX_GAP_EM)
}
