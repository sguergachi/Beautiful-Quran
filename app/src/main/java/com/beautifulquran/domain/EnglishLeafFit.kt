package com.beautifulquran.domain

import kotlin.math.sqrt

/**
 * Setting the English leaf: what size the book is written in, and how the
 * page fills its well.
 *
 * The Arabic leaf and this one solve the same problem in opposite orders,
 * because the two scripts justify by opposite means. Arabic fills a *line*
 * by the letterform and keeps one leading for the whole book
 * (`QURAN_TYPOGRAPHY.md` §§4, 9). Latin fills a line by the word space —
 * which the renderer already does — and fills the *page* by leading, which
 * is the compositor's classical lever and the one thing here that varies.
 *
 * So the English leaf is set under three rules:
 *
 * 1. **One hand for the whole book.** The type size depends on the leaf's
 *    geometry and nothing else — never on how much this page happens to
 *    carry. A page whose type grew or shrank against its neighbour reads as
 *    a fault. Same law as §2.
 * 2. **The measure sets the type.** A book line holds about fifty
 *    characters; that is what makes it readable, and it is the Latin
 *    counterpart of the mushaf's fixed 16.4 em measure. The hand is solved
 *    from the well and the measure together so both come out right at once.
 * 3. **The leading gives; the type never does.** The page's content is fixed
 *    by the Arabic leaf, so the block is brought down to the foot by opening
 *    or closing the leading. A leaf lighter than the hand was cut for opens
 *    its leading and, past the top of the band, stands short at the foot —
 *    which is what a printed parallel translation does. A leaf heavier than it
 *    closes past the floor, which six of the book's 604 leaves do
 *    ([ENGLISH_LEAF_REFERENCE_PROSE]).
 *
 * Rule 3 used to let the type give a few percent on the heaviest leaves
 * rather than set the whole book smaller. That was the wrong trade twice
 * over: type that changes from leaf to leaf is the one thing rule 1 forbids
 * and the first thing a reader notices, and a fit resting on a *model* of how
 * long a page will run is a fit that can be wrong — which is a page with
 * revelation cut off the bottom of it. The hand is cut once, for the whole
 * book; and `MushafEnglishSheet` then steps the leading against the leaf's own
 * measured height, so that fitting is a guarantee and not an estimate.
 */

/**
 * The page the hand is cut for, in characters of set prose.
 *
 * The book's one size is fitted so that a leaf of this mass fills the well at
 * the nominal leading. Measured over all 604 leaves of `data/quran.db`
 * (`tools/measure_english_leaves.py`, verse translations with their marks and
 * joining spaces) the book runs 1,055 characters at the 1st percentile, 1,286
 * at the 10th, 1,469 at the median, 1,663 at the 90th and 1,997 at the worst,
 * which is page 579.
 *
 * The anchor was the worst page carried at the tightest leading —
 * `1997 × 1.30 / 1.55 = 1675` — so that no leaf in the book ever asked for a
 * leading below the floor. That is the safe reading of it, and it set the book
 * a size smaller than it wanted to be read at.
 *
 * It is now 1,548, which is the same worst page carried one line further down
 * the leaf. The hand comes up about 4%, the median leaf takes one more line of
 * type than it used to, and the whole book reads a size larger.
 *
 * What that costs is the floor, on six leaves out of 604. Above about 1,850
 * characters a leaf now asks for less than 1.30 em, and the heaviest — page
 * 579, at 1,997 — sets at 1.20. It is close-set there rather than crowded:
 * measured on device the face's line box is 1.24 em and its *ink* fills 0.89
 * of one, so a line at 1.20 em still leaves a third of an em of air between
 * what is actually drawn, and only a descender standing directly over an
 * ascender comes near. Six leaves buying the other 598 a legible size is the
 * trade, and it is the compositor's usual one.
 *
 * The rest of the distribution moves the right way with it. 96% of leaves now
 * reach their foot inside the band against 88% before — the same page mass
 * over a slightly smaller measure needs less of the leading's help — so there
 * are fewer ragged feet as well as larger type. The leading runs 1.20 em on
 * the heaviest leaf, 1.63 at the median, and holds at the 2.00 em cap for the
 * lightest twentieth.
 */
const val ENGLISH_LEAF_REFERENCE_PROSE = 1548f

/** The leading the reference page is set on. */
const val ENGLISH_LEAF_NOMINAL_LEADING_EM = 1.55f

/**
 * The tightest the leading may close.
 *
 * EB Garamond's ascenders and descenders reach about 0.95 em and 0.28 em from
 * the baseline, so 1.30 em still leaves a fifteenth of an em between the
 * deepest descender and the tallest ascender below it. Under that they touch.
 *
 * A floor on the *fitting*, not on the drawing. A leaf that would still
 * overflow closes past it rather than lose a line off the foot — fit outranks
 * leading, always — and since [ENGLISH_LEAF_REFERENCE_PROSE] was moved to buy
 * the book a legible size, six leaves of 604 do exactly that. The heaviest
 * sets at 1.20 em. See that constant for why the trade is worth taking.
 */
const val ENGLISH_LEAF_MIN_LEADING_EM = 1.30f

/**
 * The most it may open before the foot is simply left short.
 *
 * Past this a page stops reading as prose and starts reading as a list; short
 * of the foot it reads as the end of something, which on a leaf whose content
 * was fixed by another book is exactly true. At 2.00 em 96% of leaves reach
 * their foot; at 1.80 far fewer would, and the rest would stand short for the
 * sake of a distinction the eye does not draw at this size.
 */
const val ENGLISH_LEAF_MAX_LEADING_EM = 2.00f

/**
 * Smallest / largest hand, in px, so an odd viewport cannot produce nonsense.
 *
 * Wide, deliberately: these are device pixels, and every real leaf lands far
 * inside them — a phone comes out around 15 dp of type, a tablet around 23.
 * A clamp that bit would be silently setting the book to something other than
 * its measure. Same shape as `MUSHAF_MIN_FONT_PX` / `MUSHAF_MAX_FONT_PX`.
 */
const val ENGLISH_LEAF_MIN_FONT_PX = 18f
const val ENGLISH_LEAF_MAX_FONT_PX = 140f

/**
 * A line of ordinary English from this translation, used to measure the
 * face's average character advance. Prose, not a pangram: what matters is
 * the mix of letters and spaces the book is actually set in.
 */
const val ENGLISH_LEAF_SPECIMEN =
    "And it is He who created the heavens and the earth in truth."

/**
 * The one hand for the whole English book.
 *
 * Solved from rules 1 and 2 together. With `c` the face's average character
 * advance in ems ([charAdvanceEm]), a line of the measure holds
 * `measure / (c · H)` characters and the well holds `well / (ℓ · H)` lines,
 * so a page of `R` characters asks for
 *
 * ```
 *   R = well · measure / (c · ℓ · H²)      →      H = √(well · measure / (c · ℓ · R))
 * ```
 *
 * which depends only on the leaf, never on the page — and scales the way a
 * book does when it is printed larger: a wider measure takes both more type
 * and more characters to the line.
 */
fun englishLeafHandPx(
    wellHeightPx: Float,
    measureWidthPx: Float,
    charAdvanceEm: Float,
): Float {
    if (wellHeightPx <= 0f || measureWidthPx <= 0f || charAdvanceEm <= 0f) {
        return ENGLISH_LEAF_MIN_FONT_PX
    }
    val denominator = charAdvanceEm *
        ENGLISH_LEAF_NOMINAL_LEADING_EM *
        ENGLISH_LEAF_REFERENCE_PROSE
    return sqrt(wellHeightPx * measureWidthPx / denominator)
        .coerceIn(ENGLISH_LEAF_MIN_FONT_PX, ENGLISH_LEAF_MAX_FONT_PX)
}

/**
 * The leading this leaf is set on: what brings [lines] baseline steps down to
 * the foot of the well, held inside the band so the book keeps one colour
 * whatever the page happens to hold.
 *
 * [lines] counts the *steps between baselines*, not the lines — a block of `n`
 * lines takes `n − 1` of them plus one line's own ink — and [wellHeightPx] is
 * what is left of the well once that ink and the leaf's chapter panels have
 * taken their paper. Both of those are fixed by the hand and do not move with
 * the leading, which is what makes this one division rather than a search.
 */
fun englishLeafLeadingEm(lines: Float, fontPx: Float, wellHeightPx: Float): Float {
    if (lines <= 0f || fontPx <= 0f) return ENGLISH_LEAF_NOMINAL_LEADING_EM
    return (wellHeightPx / (lines * fontPx))
        .coerceIn(ENGLISH_LEAF_MIN_LEADING_EM, ENGLISH_LEAF_MAX_LEADING_EM)
}

/**
 * The leading a leaf is actually set on, once its block has been measured.
 *
 * [englishLeafLeadingEm] chooses from a model of the page; this is the page.
 * The block's height moves by one pitch for every baseline step it holds
 * ([pitchesPx] is `steps × hand`), so the leftover paper converts to leading in
 * one step and lands the foot exactly on the foot of the well.
 *
 * It may close below [ENGLISH_LEAF_MIN_LEADING_EM] to do it, and it must: a
 * line crowded by a fortieth of an em is a page set a little tight, and a line
 * past the foot is revelation the reader cannot see. Opening, it stops at
 * [ENGLISH_LEAF_MAX_LEADING_EM] like any other leaf — a page that will not
 * reach its foot stands short of it.
 */
fun englishLeafFittedLeadingEm(
    leadingEm: Float,
    measuredHeightPx: Float,
    wellHeightPx: Float,
    pitchesPx: Float,
): Float {
    if (measuredHeightPx <= 0f || pitchesPx <= 0f) return leadingEm
    val step = (wellHeightPx - measuredHeightPx) / pitchesPx
    val fitted = leadingEm + step
    return if (step > 0f) fitted.coerceAtMost(ENGLISH_LEAF_MAX_LEADING_EM) else fitted
}
