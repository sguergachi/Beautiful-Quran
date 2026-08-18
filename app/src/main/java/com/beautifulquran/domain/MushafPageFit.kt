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
 * The narrowest paper allowed between one word's last stroke and the next
 * word's first, as a fraction of the page's type size.
 *
 * This is the guarantee the rest of the fit is built on. Without it a line's
 * spacing was whatever paper happened to be left after the ink, and measured
 * over 738 lines that left 36.6% of them under 0.10 em and the tightest tenth
 * *negative* — words overlapping. A compositor does the opposite: the word
 * space is chosen first, and a line that will not take it has its letters
 * tightened instead. Costed over the same lines, honouring 0.17 em tightens
 * the median line by about 2% — invisible — and only the densest twentieth by
 * more than 14%.
 */
const val MUSHAF_MIN_WORD_GAP_EM = 0.17f

/**
 * How far a line's letterforms are condensed so its words can keep [
 * MUSHAF_MIN_WORD_GAP_EM] between them.
 *
 * [inkWidthPx] is the ink alone — the sum of what the words actually mark, not
 * of their advance boxes, because the page faces carry side bearings that vary
 * enormously from glyph to glyph and spacing by advance is what let two words
 * collide while their neighbours drifted apart.
 */
fun mushafInkCondense(
    inkWidthPx: Float,
    measureWidthPx: Float,
    gapCount: Int,
    fontPx: Float,
): Float {
    if (inkWidthPx <= 0f || measureWidthPx <= 0f) return 1f
    val room = measureWidthPx - gapCount.coerceAtLeast(0) * MUSHAF_MIN_WORD_GAP_EM * fontPx
    if (room <= 0f) return 1f
    return if (inkWidthPx <= room) 1f else room / inkWidthPx
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
