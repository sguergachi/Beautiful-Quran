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
 *  0.3  running head — no more paper than its own line box: the leaf
 *       begins under the status bar, so the phone's forehead is already
 *       the margin above it
 *    1  head gutter — a whole line's pitch, the figure the grid names
 *  15   the revelation — the Madinah page's own grid
 * 0.55  foot — paper under the last line, before the folio's band
 *  ---
 * 16.85
 * ```
 *
 * The tail and the folio used to close that list, and the leaf paid for both:
 * 0.35 of a unit of paper and 0.40 for the figure, three quarters of a line
 * spent under the text. They have moved off the leaf and into the air the dial
 * already kept above its rule ([MushafDialHeadAir]) — air that was padding and
 * nothing else. The folio sits lower on the screen for it, closer to the
 * transport it now shares a band with, and the leaf keeps the three quarters
 * of a line: on the English hand, whose type is solved from the well, that is
 * a twenty-third line of prose where there were twenty-two.
 *
 * The furniture is trimmed to what it actually needs to read as furniture,
 * because the leaf's height is what caps the type: measured on the reference
 * page the measure would carry 97px of glyph and the well only 57px, so every
 * unit the chrome does not use is type size. The horizontal margins are already
 * a bare 10dp; fourteen percent of the leaf on vertical chrome was the odd one
 * out.
 *
 * These constants remain the canonical fifteen-row fit and the total 17.05
 * unit budget. The pager's larger display hand reassigns one unit of that same
 * budget from head/tail furniture to a sixteenth visual row; the leaf itself
 * does not grow and its 604 page boundaries do not move.
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
     *
     * It is the label's own line box and nothing more, set hard against the
     * top of the leaf. Centred in a taller band it carried a strip of air
     * above it, and the leaf already begins below the status bar — the phone's
     * forehead is the margin, and buying a second one came out of the text.
     * The figure tracks [MushafType.HEAD]: at that hand the label inks about
     * 19 px against an 81 px unit, and a band under 0.30 clips its descenders.
     */
    const val RUNNING_HEAD = 0.30f

    /**
     * Paper between the head and the first line of revelation.
     *
     * A head that sits closer than about a line's pitch reads as part of the
     * block instead of standing off it — and the first line's ink reaches high
     * into its own slot (the faces ink 1.37 em above the baseline), so the
     * visible gap is the gutter *less* that reach. At most of a pitch the
     * head still read as the block's first line once that reach was taken off
     * it. A whole pitch is the figure the grid already names as the threshold,
     * so the gutter is that and not a fraction chosen beside it.
     */
    const val HEAD_GUTTER = 1.00f
    const val TEXT_LINES = MUSHAF_LINES_PER_PAGE
    /**
     * Paper under the last line of the leaf.
     *
     * It was taken out with the folio, on the reasoning that nothing stands
     * under the text any more — and that was true only for as long as the text
     * did not reach the foot. The pagination counted characters into the leaf
     * and always left a line or so unspent, and that unspent line was doing the
     * work of a foot margin without anybody having asked it to. Measuring the
     * leaf instead of counting it ([EnglishLeafRuler]) took the accident away:
     * the last line's descenders came down to 2,076 px on a device where the
     * folio's own ink begins at 2,064, so the page number was being set *into*
     * the text.
     *
     * A page has a foot. This is it, and it is a real band of the grid rather
     * than a slack the arithmetic happens to leave: the leaf grows by it, so
     * the type comes down about three percent and the well holds the same
     * number of lines, each a little longer. It is not the head's 1.30 — the
     * folio and the dial stand below it and carry their own air — but it is
     * enough that the last line and the page number are two things.
     */
    const val TAIL = 0.55f

    /**
     * The folio's band — no longer a band *of the leaf*. The figure stands in
     * the air above the dial's rule, so the leaf's own height ends with the
     * last line of text and the folio is furniture of the sheet, sitting with
     * the dial and the transport rather than on the paper.
     *
     * It is still a unit of the leaf's grid, because it is still the same
     * book: the figure keeps the pitch of the lines it numbers.
     */
    const val FOLIO = 0.40f

    /** The whole leaf, in units. */
    const val SLOTS = RUNNING_HEAD + HEAD_GUTTER + TEXT_LINES + TAIL

    /** One line's pitch: the leaf divided by its slots. */
    fun unitPx(leafHeightPx: Float): Float =
        (leafHeightPx / SLOTS).coerceAtLeast(1f)

    /** The height of the text well: fifteen lines of the same unit. */
    fun textWellPx(leafHeightPx: Float): Float = unitPx(leafHeightPx) * TEXT_LINES
}

/**
 * How a leaf divides its height, in units of [MushafGrid].
 *
 * The three bands always sum to [MushafGrid.SLOTS] — that is the whole point of
 * a grid, and the one thing a test can hold: a leaf whose bands sum to more
 * than its height runs its last line off the paper, and one that sums to less
 * leaves a strip of dead paper at the foot that nothing accounts for.
 *
 * The two settings spend the budget differently because their ink does.
 *
 * The Arabic leaf spends almost nothing on the gutter and the tail and buys a
 * sixteenth row of revelation with it. It can: the QCF faces mark 1.37 em above
 * the baseline and 0.75 below, so a nominal band of nearly nothing still leaves
 * visible air, and every unit not spent on furniture is type size (see
 * `MUSHAF_DESIGN_LINE_EM`).
 *
 * The English leaf has no sixteenth row to buy — its well is continuous prose —
 * and its ink stops exactly at the ascent and the descender, because the block
 * is set `Trim.Both`. A band of nothing there is nothing. So it keeps the
 * canonical gutter, which was sized for exactly this: "a head that sits closer
 * than about a line's pitch reads as part of the block".
 *
 * Neither carries a folio any more: it stood under the text and has gone to the
 * dial's head air. The tail stayed behind it, because a page has a foot and the
 * text now reaches it. The two settings still do not sum to the same figure —
 * the
 * English gutter is half a unit wider than the Arabic one — so each divides
 * the leaf by its own [slots] rather than by one shared total. That total was
 * only ever a convenience: what a leaf must not do is spend more height than
 * it has, and its own sum is what says whether it does.
 */
data class MushafLeafBands(
    val runningHead: Float,
    val headGutter: Float,
    val well: Float,
    /** Paper under the last line — see [MushafGrid.TAIL]. */
    val tail: Float,
) {
    val slots: Float get() = runningHead + headGutter + well + tail

    /** One line's pitch on this leaf: its height divided by its own slots. */
    fun unitPx(leafHeightPx: Float): Float = (leafHeightPx / slots).coerceAtLeast(1f)
}

/** The canonical bands: wide gutters, fifteen units of well. */
val MUSHAF_ENGLISH_BANDS = MushafLeafBands(
    runningHead = MushafGrid.RUNNING_HEAD,
    headGutter = MushafGrid.HEAD_GUTTER,
    well = MushafGrid.TEXT_LINES.toFloat(),
    tail = MushafGrid.TAIL,
)

/** The display bands: the furniture gives a unit up for a sixteenth row. */
val MUSHAF_ARABIC_BANDS = MushafLeafBands(
    runningHead = MushafGrid.RUNNING_HEAD,
    // The sixteenth row is bought out of the gutter, so this stays the tighter
    // of the two: the QCF faces mark 1.37 em above the baseline, so the head
    // stands off the revelation on half a unit that would read as crowded
    // under an English block.
    headGutter = 0.50f,
    well = MUSHAF_DISPLAY_LINES_PER_PAGE.toFloat(),
    tail = MushafGrid.TAIL,
)

fun mushafLeafBands(english: Boolean): MushafLeafBands =
    if (english) MUSHAF_ENGLISH_BANDS else MUSHAF_ARABIC_BANDS

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
 *  -3   the running head, and the folio's own Arabic figure
 *  -4   the Latin numeral glossing the folio
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
     * The running head.
     *
     * It stood two steps under the revelation once, which made wayfinding the
     * second loudest thing on the page, and it was brought down four — to the
     * leaf's smallest hand, shared with the folio's Latin gloss. Four is a
     * rung too far: at that size the head reads as a caption mislaid at the
     * top of the leaf rather than as the leaf's own heading, and it no longer
     * sits on the scale with anything. Three is the rung the folio's Arabic
     * figure already stands on, which is the company a running head keeps.
     */
    const val HEAD = -3

    /**
     * The Latin numeral glossing the folio — the leaf's smallest hand, and the
     * only thing left on it. It stays a step under the Arabic figure beside
     * it for the reason those two are a step apart at all: a Latin numeral set
     * at a Hafs numeral's size reads larger than it.
     */
    const val FOLIO_GLOSS = -4
}
