package com.beautifulquran.domain

/** Line-height multiple of the Hafs face used to pack a mushaf page. */
const val MUSHAF_LINE_EM = 2.2f

/**
 * Line pitch as a multiple of the fitted glyph size.
 *
 * Set to the printed page's own leading, 1.85, this collided. The print can
 * afford that leading because the calligrapher composed each page: a deep
 * descender is never written directly above a tall ascender. We cannot
 * rearrange a line, so the pitch has to clear the worst pair the page can
 * throw up.
 *
 * Measured over 817 adjacent line pairs, the pitch each needs before its ink
 * touches the ink below is 1.63 em at the median, 1.89 at the 95th and 2.12 at
 * the worst — so 1.85 left 8% of pairs, about one a page, overlapping. At 2.15
 * none of them does.
 *
 * It is free. The type is bound by the page's width, so a taller pitch costs no
 * type size (from the height the well would still allow 63.6px against the
 * 60px the measure gives), and fifteen lines at this pitch take 1935px of a
 * 2050px well — paper that was previously sitting unused as margin.
 */
const val MUSHAF_LINE_PITCH_EM = 2.15f

/**
 * The tightest leading the book may be set on, and so the one the type size is
 * guaranteed against.
 *
 * The type is sized from the measure and must never shrink to buy leading: a
 * shorter well takes its lines closer together instead, down to the printed
 * page's own 1.85. Sizing the type against the fuller pitch would have made a
 * short screen set the whole book smaller.
 */
const val MUSHAF_MIN_LINE_PITCH_EM = 1.85f

/**
 * How tall a line's ink actually stands, in multiples of the type size.
 *
 * Measured across the page faces: they mark up to 1.368 em above the baseline
 * and 0.747 em below, so a line of this book occupies 2.12 em of paper whatever
 * the leading says. A slot shorter than that is a descender written into the
 * line beneath — and over 817 adjacent line pairs, 8% of them needed more room
 * than the printed page's own 1.85 em leading gives.
 *
 * The type is therefore never sized past what its own ink will fit, and this is
 * the number that decides it. Set at the 99th percentile of what adjacent pairs
 * actually ask for (1.978 em) rather than at the single worst pair in the book
 * (2.118): guarding the absolute worst cost a tenth of the type size across all
 * 604 pages, and a smaller type then needs more stretching to fill its line —
 * one fault traded for two. At this leading the type gives up about 3% and
 * better than 99% of line pairs clear.
 *
 * It reads 2.02 rather than the 2.05 it carried for a while because it is now
 * the pitch itself. The type used to be sized against the whole well while the
 * lines were laid out in the well less an edge gutter, so a nominal 2.05 was
 * 2.018 of real leading and the difference was type size lost to an inset that
 * was only ever meant to hold a circled ayah mark's overhang at the *line end*.
 * The number here is what the page is actually set on, and it now sits where
 * the measurement put it rather than a hedge above: 1.99 against a 99th
 * percentile of 1.978. This is the floor. Below it the descenders that 817
 * measured line pairs asked to clear stop clearing.
 */
const val MUSHAF_LINE_INK_EM = 1.99f

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
 * renderer must supply. Measured with HarfBuzz over all 8,820 lines
 * (tools/measure_mushaf_lines.py), a line's glyph run spans 15.40 em (p10)
 * through 15.57 (p50) to 16.54 (p99), longest 18.17 — a printed page is
 * composed to one measure, so the run is nearly constant and what varies is
 * how much space the compositor had to give away.
 *
 * So the composition is the classic one:
 *
 * ```
 *   line = Σ glyph advances + (words − 1) × gap
 * ```
 *
 * with one size for the whole book and the gap solved per line. The size is
 * anchored where the two costs balance, which is measurable rather than a
 * matter of taste. At 16.4 em: 96% of lines fill by gap alone at a median
 * 0.11 em — an ordinary word space — and the remaining 4% are condensed
 * horizontally, half of them by under 0.6% and not one line in the book by
 * more than 12%. Anchoring on the longest line instead (18.2) gaps every line
 * at a median 0.34 em, which is the "spread out" leaf; anchoring on the median
 * (15.6) throws 39% of lines into condensing.
 *
 * These figures are from the corrected layout: until quran-v48 the line data
 * came from a mirror that broke lines in the wrong places, which spread the
 * same measurement over 14.2–18.7 em and made the page look composed to a
 * measure it was never set to.
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
    val scale = fontScale.coerceIn(0.88f, 1.12f)
    val fromMeasure = measureWidthPx / MUSHAF_DESIGN_LINE_EM * scale
    // The reader's own size nudge is inside this, not outside it: applied after
    // the well had spoken, a larger text size grew the ink by a tenth while the
    // slot it sits in stayed exactly as tall, and the descenders went into the
    // line below. The type stops growing where its ink would stop fitting.
    val fromWell = wellHeightPx / (slots * MUSHAF_LINE_INK_EM)
    return minOf(fromMeasure, fromWell)
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

/**
 * As far as the space may open once the letterforms have given all they may,
 * rather than let a full line stand short of its margin.
 *
 * A full line must reach both margins — the print keeps that by elongating
 * letters, and where we cannot elongate enough, the space carries the rest.
 * Measured: allowing this leaves 1.2% of lines short, against 2.3% when the
 * space stopped at its usual maximum. Past 0.45 em the return falls away (0.9%
 * at 0.75 em) while the line starts to read as a row of islands.
 */
const val MUSHAF_STRETCH_WORD_GAP_EM = 0.45f

/** How far letterforms may be narrowed, and stretched, to hold that space. */
const val MUSHAF_MIN_LINE_SCALE = 0.80f
const val MUSHAF_MAX_LINE_SCALE = 1.15f

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
 A compositor chooses the space and makes the line fit around it, which is
 * what this does — and the space is what gives first, in both directions,
 * because the letterform is the one thing on the page that is not ours. The
 * page's space is [MUSHAF_WORD_GAP_EM]. A line too wide closes it as far as
 * [MUSHAF_FLOOR_WORD_GAP_EM] before any letter is touched; a line too narrow
 * opens it as far as [MUSHAF_MAX_WORD_GAP_EM] before any letter is stretched.
 * Only past those does the type give. A full line is always flush: that is the
 * mushaf's own rule, and the print keeps it by elongating letters, which is why
 * the letters here stretch as far as [MUSHAF_MAX_LINE_SCALE] rather than the
 * line falling short. Only a line that cannot reach the measure even then — a
 * chapter's last, four or five words standing alone — is set at the page's own
 * space and centred. Measured, that is 2.3% of lines; at the old bound of 1.06
 * it was 6.2%, and two lines in every three of those were full lines wrongly
 * left short.
 *
 * Measured over 738 lines: 52% of the page keeps its letterforms exactly as
 * drawn, the median line is untouched, and the fifth percentile sits at 0.879.
 * Holding the space fixed instead and making the letters give left only 28%
 * untouched for the same worst case.
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
        // The space gives before the letters do. A word set narrower than it
        // was drawn is a word altered; a space a little tighter is still a
        // space, and the eye reads the line either way. Measured over 738
        // lines, letting the space close to its floor first leaves 52% of the
        // page with its letterforms untouched, against 28% when the space is
        // held at the page's own and the letters made to give — for the same
        // worst case on the densest lines.
        val closed = (measureWidthPx - inkWidthPx) / gaps
        val floorGap = MUSHAF_FLOOR_WORD_GAP_EM * fontPx
        if (closed >= floorGap) return MushafLineFit(scale = 1f, gapPx = closed, flush = true)
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
    if (scale <= MUSHAF_MAX_LINE_SCALE) return MushafLineFit(scale, capped, flush = true)
    // Neither alone reaches the margin. Hold the letters at their bound and let
    // the space carry what is left: a full line standing short is the one thing
    // a mushaf page never does, and this is the last move before giving up on
    // it. Without this step a line that needed a 1.19 stretch was set short
    // even though 1.15 and a wider space would have filled it.
    val stretched = (measureWidthPx - MUSHAF_MAX_LINE_SCALE * inkWidthPx) / gaps
    if (stretched <= MUSHAF_STRETCH_WORD_GAP_EM * fontPx) {
        return MushafLineFit(MUSHAF_MAX_LINE_SCALE, stretched, flush = true)
    }
    // Not a full line: a chapter's last, four or five words standing alone.
    return MushafLineFit(scale = 1f, gapPx = ideal, flush = false)
}

/** Never stretch a word gap past this fraction of the page font. */
const val MUSHAF_MAX_GAP_EM = 0.55f

/**
 * The Quran's own rule: every full line of the page is justified end to end —
 * the printed page stretches the name-list lines (few, long words) the same
 * as any other. Two words is the floor: one gap is all a two-word line has
 * to give, and the fit's own stretch caps keep it inside the page's taste.
 * A line that genuinely cannot fill falls back to short-and-centred inside
 * the fit.
 */
const val MUSHAF_JUSTIFY_MIN_WORDS = 2

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
