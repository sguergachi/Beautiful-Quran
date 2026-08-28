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
 * 3. **The leading gives, within bounds.** The page's content is fixed by
 *    the Arabic leaf, so the block is brought to the foot by opening or
 *    closing the leading — never by resizing the type. Past those bounds the
 *    foot is left ragged, as a printed parallel translation leaves it.
 */

/**
 * The reference page, in characters of set prose.
 *
 * The hand is fitted so that a leaf of this mass exactly fills the well at the
 * nominal leading. Measured over all 604 leaves of `data/quran.db`
 * (`tools/measure_english_leaves.py`, verse translations with their marks and
 * joining spaces) the book runs 1,055 characters at the 1st percentile, 1,286
 * at the 10th, 1,469 at the median, 1,663 at the 90th and 1,997 at the worst.
 *
 * The anchor sits a little under the median rather than on it because the two
 * ends of that distribution cost different things. A page heavier than the
 * reference closes its leading, and there is little room below the nominal
 * before the lines crowd; a page lighter than it opens the leading, which has
 * more room and, at the end of it, only leaves the foot short. So the anchor
 * is placed where the band [ENGLISH_LEAF_MIN_LEADING_EM]..
 * [ENGLISH_LEAF_MAX_LEADING_EM] covers the widest slice of the book. Swept
 * over every candidate, 1,440 is the best there is: 90.2% of leaves fill their
 * well outright, 35 stand a little short at the foot, and 24 ask the hand to
 * give — the heaviest of them (page 579) by 7%.
 */
const val ENGLISH_LEAF_REFERENCE_PROSE = 1440f

/** The leading the reference page is set on. */
const val ENGLISH_LEAF_NOMINAL_LEADING_EM = 1.55f

/**
 * The tightest the leading may close before the type is asked to give.
 *
 * EB Garamond's ascenders and descenders reach about 0.95 em and 0.28 em from
 * the baseline, so 1.30 em still leaves a fifteenth of an em between the
 * deepest descender and the tallest ascender below it. Under that they touch.
 */
const val ENGLISH_LEAF_MIN_LEADING_EM = 1.30f

/**
 * The most it may open before the foot is simply left short.
 *
 * A book set past about 1.8 em stops reading as prose and starts reading as
 * a list; a page that is short at the foot reads as the end of something,
 * which on a leaf whose content was fixed by another book is exactly true.
 */
const val ENGLISH_LEAF_MAX_LEADING_EM = 1.80f

/**
 * How far the hand may narrow on the heaviest leaves in the book, once the
 * leading has closed as far as it may.
 *
 * This is the English counterpart of `MUSHAF_MIN_LINE_CONDENSE`: a measured
 * expectation, not a taste. Over the book 24 leaves ask the hand to give at
 * all, and the heaviest of them (page 579, 1,997 characters) asks for 0.927 —
 * so this floor is never reached. It survives as the guarantee that the leaf
 * *fits*, because a page that overflows its well is a page with revelation
 * cut off the bottom of it, and as the threshold a data change that made some
 * leaf absurdly heavy would fail a test against rather than quietly shrink.
 */
const val ENGLISH_LEAF_MIN_HAND = 0.90f

/**
 * Smallest / largest hand, in px, so an odd viewport cannot produce nonsense.
 *
 * Wide, deliberately: these are device pixels, and every real leaf lands far
 * inside them (a 3x phone comes out near 50 px, a 2x tablet near 50 px too —
 * about 16 dp and 25 dp of type). A clamp that bit would be silently setting
 * the book to something other than its measure. Same shape as
 * `MUSHAF_MIN_FONT_PX` / `MUSHAF_MAX_FONT_PX`.
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
 * The leading this leaf is set on: what brings [lines] down to the foot of
 * the well, held inside the band so the book keeps one colour whatever the
 * page happens to hold.
 *
 * [lines] is fractional because a chapter's panel and its basmalah are
 * measured in line pitches too (`EnglishLeafPanelLines`,
 * `EnglishLeafBasmalahLines`) rather than in dp of their own. Everything on
 * the leaf then rides one rhythm, the way `MushafGrid` puts the Arabic leaf's
 * head, well, tail and folio on one unit.
 */
fun englishLeafLeadingEm(lines: Float, fontPx: Float, wellHeightPx: Float): Float {
    if (lines <= 0f || fontPx <= 0f) return ENGLISH_LEAF_NOMINAL_LEADING_EM
    return (wellHeightPx / (lines * fontPx))
        .coerceIn(ENGLISH_LEAF_MIN_LEADING_EM, ENGLISH_LEAF_MAX_LEADING_EM)
}

/**
 * How far the hand must narrow for a leaf that still overflows once its
 * leading has closed all the way — 1.0 for all but the heaviest leaves.
 *
 * The square root, not the ratio: narrowing the hand by `g` takes the block's
 * height by `g²`, because the same prose fits `1/g` more characters to the
 * line *and* each line stands `g` shorter. Asking for the ratio itself
 * shrank the type about twice as far as the overflow needed.
 *
 * Never clamped past [ENGLISH_LEAF_MIN_HAND] silently: the caller re-sets the
 * leaf at the narrowed hand, which takes fewer lines, so the second pass
 * clears. Fit is the guarantee; how far the hand had to give is a property of
 * the corpus and is asserted over the whole book by test.
 */
fun englishLeafHandGive(lines: Float, fontPx: Float, wellHeightPx: Float): Float {
    if (lines <= 0f || fontPx <= 0f) return 1f
    val tightest = lines * fontPx * ENGLISH_LEAF_MIN_LEADING_EM
    if (tightest <= wellHeightPx) return 1f
    return sqrt(wellHeightPx / tightest).coerceAtLeast(ENGLISH_LEAF_MIN_HAND)
}
