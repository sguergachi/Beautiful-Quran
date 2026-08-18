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
 * The QCF page faces carry each word as it was drawn on that line, and nothing
 * else: no space glyph of any kind (checked — U+0020, U+00A0, the thin spaces,
 * tatweel: none are in the cmap). The Madinah page's justification therefore
 * lives in the space *between* words, which the font does not carry and the
 * renderer must supply. Measured with HarfBuzz over all ~8,800 lines
 * (tools/verify_mushaf_lines.py), a line's glyph run spans 14.2 em (p10)
 * through 15.6 (p50) to 18.7 (p99) — and the line data is right: it agrees
 * word for word with the layout the fonts were cut for.
 *
 * So the composition is the classic one:
 *
 * ```
 *   line = Σ glyph advances + (words − 1) × gap
 * ```
 *
 * with one size for the whole book and the gap solved per line. The size is
 * anchored where the two costs balance, which is measurable rather than a
 * matter of taste. At 16.4 em: three lines in four fill by gap alone at a
 * median 0.12 em — an ordinary word space — and the rest are condensed
 * horizontally, half of them by under 3.5% and only 1.1% of all lines by more
 * than 12%. Anchoring on the widest line instead (18.7) leaves 99% of the page
 * gapping at a median 0.40 em, which is the "spread out" leaf; anchoring on
 * the median (15.6) throws 44% of lines into condensing.
 *
 * Condensed, never resized: a line set narrower keeps its height, weight and
 * colour, so the page still reads as one hand. Changing the size line by line
 * does not, which is why it reads as a fault.
 */
const val MUSHAF_DESIGN_LINE_EM = 16.4f

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
 * How far a line's letterforms are condensed to bring it inside the measure —
 * 1.0 for the three lines in four that need no condensing at all.
 *
 * This is the difference between a printed mushaf and a stretched one. The
 * page is justified by the *hand* — a crowded line written a little tighter, a
 * sparse one a little more openly — not by pulling the words apart and leaving
 * rivers of paper between them. Scaling the line also keeps the glyphs as large
 * as the measure allows, which is what makes the ink read heavy and full rather
 * than thin and small.
 *
 * Ink over the fore-edge is clipped and the circled mark riding at a line's end
 * is what gets sliced, so a line that will not fit is made to fit — but by its
 * letterforms narrowing, never by its size changing.
 */
fun mushafLineCondense(naturalWidthPx: Float, measureWidthPx: Float): Float {
    if (naturalWidthPx <= 0f || measureWidthPx <= 0f) return 1f
    val needed = measureWidthPx / naturalWidthPx
    // A line at or inside the measure is never touched: it fills by gap.
    // Past it, the line is condensed by exactly what it needs and never
    // clamped: a line is drawn one node per word, so a clamp does not stop at
    // a tidy overhang — the cells run past the measure, their weight spacers
    // collapse to nothing, and the glyphs paint over their neighbours. That is
    // what put a word on top of the circled ١٢ on page 79. Fit is a guarantee
    // here; how tight a line may get before it stops reading as the page's own
    // hand is a property of the data, asserted over the whole mushaf by
    // MushafPageFitTest against MUSHAF_MIN_LINE_CONDENSE.
    return if (needed >= 1f) 1f else needed
}

/**
 * How far a line is expected to be condensed at worst — the point past which a
 * line stops reading as the same hand as the page around it.
 *
 * This is a *measured expectation*, not a runtime clamp. Over the mushaf 1.1%
 * of lines ask for more than this, and they come from the few pages whose runs
 * are anomalous; clamping them was silently breaking their layout, so they are
 * now set as tight as they need to be. The constant survives as the threshold
 * the corpus is held to, so a data change that makes some line absurdly long
 * fails a test instead of quietly squeezing a page.
 */
const val MUSHAF_MIN_LINE_CONDENSE = 0.86f

/**
 * The word space the page is set on, as a fraction of the type size.
 *
 * The page faces supply almost none of their own: measured over 5,707 joins,
 * the air a face leaves between two words is 0.044 em at the median and
 * negative at a quarter of them — Arabic letters nest by design. So the space
 * between words is entirely the renderer's to choose, and choosing it once is
 * what makes a page look evenly set.
 */
const val MUSHAF_WORD_GAP_EM = 0.18f

/** As far as the space may open before the letterforms are asked to give. */
const val MUSHAF_MAX_WORD_GAP_EM = 0.30f

/** How far letterforms may be narrowed, and stretched, to hold that space. */
const val MUSHAF_MIN_LINE_SCALE = 0.80f
const val MUSHAF_MAX_LINE_SCALE = 1.06f

/**
 * The space a line keeps even when its letters have given all they may.
 *
 * A handful of lines in the mushaf are too dense to hold the page's space at
 * any tolerable letter width. They give up space down to this and no further —
 * the letters narrow past their usual floor instead. A line whose words touch
 * is not a line; a line written a little tighter than its neighbours is.
 */
const val MUSHAF_FLOOR_WORD_GAP_EM = 0.13f

/** How a line is set: its letterforms, its word space, and whether it fills the
 * measure or stands short and centred. */
data class MushafLineFit(
    val scale: Float,
    val gapPx: Float,
    val flush: Boolean,
)

/**
 * Sets one line: the word space first, the letterforms second.
 *
 * Spacing used to be the leftover — fit the run to the measure and divide
 * whatever paper remained — which starved dense lines and flooded sparse ones.
 * Over 738 lines that left a third of them under 0.10 em between words, the
 * tightest tenth *overlapping*, and the loosest hundredth at 1.17 em, which is
 * a river down the page.
 *
 * A compositor chooses the space and makes the line fit around it, which is
 * what this does. The space is [MUSHAF_WORD_GAP_EM] wherever it can be. A line
 * too wide for it narrows its letters; one too narrow opens its space as far as
 * [MUSHAF_MAX_WORD_GAP_EM] and only then stretches. A line that would have to
 * stretch past [MUSHAF_MAX_LINE_SCALE] is not a full line at all — a chapter's
 * last, most often — so it is set at the page's own space and centred, the way
 * it is printed.
 *
 * Measured over the same 738 lines: 72% are set at exactly the page's space,
 * none wider than 0.30 em, letterforms hold between 0.80 and 1.06 with a median
 * of 0.978, and 6.2% stand short.
 */
fun mushafLineFit(
    inkWidthPx: Float,
    gapCount: Int,
    measureWidthPx: Float,
    fontPx: Float,
): MushafLineFit {
    val gaps = gapCount.coerceAtLeast(0)
    val ideal = MUSHAF_WORD_GAP_EM * fontPx
    if (inkWidthPx <= 0f || measureWidthPx <= 0f || gaps == 0) {
        return MushafLineFit(scale = 1f, gapPx = ideal, flush = false)
    }
    val needed = inkWidthPx + gaps * ideal
    if (needed > measureWidthPx) {
        val scale = (measureWidthPx - gaps * ideal) / inkWidthPx
        if (scale >= MUSHAF_MIN_LINE_SCALE) return MushafLineFit(scale, ideal, flush = true)
        // The space gives only as far as its floor, and the letters take the
        // rest — narrowing past their usual limit if this line demands it.
        // Clamping the letters here instead drove the space to nothing, which
        // is how a dense line came out with its words touching.
        val floorGap = MUSHAF_FLOOR_WORD_GAP_EM * fontPx
        return MushafLineFit(
            scale = (measureWidthPx - gaps * floorGap) / inkWidthPx,
            gapPx = floorGap,
            flush = true,
        )
    }
    val opened = (measureWidthPx - inkWidthPx) / gaps
    if (opened <= MUSHAF_MAX_WORD_GAP_EM * fontPx) {
        return MushafLineFit(scale = 1f, gapPx = opened, flush = true)
    }
    val capped = MUSHAF_MAX_WORD_GAP_EM * fontPx
    val scale = (measureWidthPx - gaps * capped) / inkWidthPx
    return if (scale <= MUSHAF_MAX_LINE_SCALE) {
        MushafLineFit(scale, capped, flush = true)
    } else {
        MushafLineFit(scale = 1f, gapPx = ideal, flush = false)
    }
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
