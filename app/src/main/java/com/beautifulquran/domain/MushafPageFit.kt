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
const val MUSHAF_LINE_PITCH_EM = 2.05f

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
 * run spans 14.1 em (p10) through 15.6 em (p50) to 16.9 em (p90). The median
 * line is the book's measure: setting the type from it fills the page with the
 * largest hand the leaf can carry. Shorter runs close the small remainder with
 * their word gaps; longer ones are drawn a little tighter by
 * [mushafLineSqueeze], the way a calligrapher fits a crowded line.
 */
const val MUSHAF_DESIGN_LINE_EM = 16.2f

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
 * Scale for a single line whose glyph run runs past the measure — the rare
 * long line is set a little tighter instead of pulling its whole page down.
 */
fun mushafLineSqueeze(naturalWidthPx: Float, measureWidthPx: Float): Float {
    if (naturalWidthPx <= 0f || measureWidthPx <= 0f) return 1f
    return (measureWidthPx / naturalWidthPx).coerceIn(MUSHAF_MIN_LINE_SQUEEZE, 1f)
}

/** A crowded line is never set smaller than this share of the book's size. */
const val MUSHAF_MIN_LINE_SQUEEZE = 0.88f

/**
 * Font size in pixels so [lineCount] mushaf lines fill [pageHeightPx].
 * [fontScale] is a reader nudge around the fitted size, not a free resize.
 */
fun mushafFontPx(
    pageHeightPx: Float,
    lineCount: Int,
    fontScale: Float = 1f,
): Float {
    if (lineCount <= 0 || pageHeightPx <= 0f) return MUSHAF_MIN_FONT_PX
    val lineHeight = pageHeightPx / lineCount
    val fitted = lineHeight / MUSHAF_LINE_EM
    val nudged = fitted * fontScale.coerceIn(0.88f, 1.12f)
    return nudged.coerceIn(MUSHAF_MIN_FONT_PX, MUSHAF_MAX_FONT_PX)
}

/**
 * Scale a height-fitted size down so the longest mushaf line stays inside
 * [pageWidthPx]. Never grows past the height fit — overflow is the only
 * reason to shrink.
 */
fun mushafFontPxFittingWidth(
    heightFittedPx: Float,
    longestLineWidthPx: Float,
    pageWidthPx: Float,
): Float {
    if (pageWidthPx <= 0f || longestLineWidthPx <= pageWidthPx) {
        return heightFittedPx
    }
    return (heightFittedPx * (pageWidthPx / longestLineWidthPx))
        .coerceIn(MUSHAF_MIN_FONT_PX, heightFittedPx)
}

/**
 * Extra letter-spacing (px) to put on each inter-word space so a mushaf
 * line fills [pageWidthPx]. Zero when the line already spans the page or
 * there is nothing to stretch.
 */
/**
 * Scale [probeFontPx] so [lineCount] lines of [measuredLineHeightPx] fill
 * [pageHeightPx]. Use a real measured line — Digital Khatt's marks are
 * taller than a guessed em multiple.
 */
fun mushafFontPxFromMeasuredLine(
    pageHeightPx: Float,
    lineCount: Int,
    measuredLineHeightPx: Float,
    probeFontPx: Float,
    fontScale: Float = 1f,
): Float {
    if (lineCount <= 0 || pageHeightPx <= 0f ||
        measuredLineHeightPx <= 0f || probeFontPx <= 0f
    ) {
        return MUSHAF_MIN_FONT_PX
    }
    val targetLine = pageHeightPx / lineCount
    val fitted = probeFontPx * (targetLine / measuredLineHeightPx)
    return (fitted * fontScale.coerceIn(0.88f, 1.12f))
        .coerceIn(MUSHAF_MIN_FONT_PX, MUSHAF_MAX_FONT_PX)
}

/** Scale [currentPx] so a measured line of [measuredWidthPx] becomes [targetWidthPx]. */
fun mushafFontPxMatchWidth(
    currentPx: Float,
    measuredWidthPx: Float,
    targetWidthPx: Float,
): Float {
    if (measuredWidthPx <= 0f || targetWidthPx <= 0f) return currentPx
    return (currentPx * targetWidthPx / measuredWidthPx)
        .coerceIn(MUSHAF_MIN_FONT_PX, MUSHAF_MAX_FONT_PX)
}

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
