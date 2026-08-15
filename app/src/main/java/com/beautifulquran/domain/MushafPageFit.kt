package com.beautifulquran.domain

/** Line-height multiple of the Hafs face used to pack a mushaf page. */
const val MUSHAF_LINE_EM = 2.2f

/** Smallest / largest fitted page size, in px, so a short page never balloons. */
const val MUSHAF_MIN_FONT_PX = 28f
const val MUSHAF_MAX_FONT_PX = 128f

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
