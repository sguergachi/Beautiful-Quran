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
 * 3. **One leading for the whole book, and the foot falls where it falls.**
 *    A book is set on one leading. It does not respace its lines because a
 *    page happens to be full, and a reader turning pages sees a change in line
 *    spacing at once — far sooner than they notice a page that ends early.
 *
 * Rule 3 is the third answer this setting has given to the same question, and
 * the question is unavoidable: the page boundary comes from the Arabic leaf, so
 * the mass of a page is fixed at somewhere between 1,055 and 1,997 characters
 * and *something* has to absorb a range of nearly two to one. It can be the
 * type, the leading, or the foot; it cannot be nothing.
 *
 * It was the type first — a few percent off the heaviest leaves. That is the
 * one thing rule 1 forbids and the first thing a reader notices, so it went.
 *
 * Then it was the leading, opening and closing between 1.20 and 2.00 em to
 * bring each block down to the foot. Every leaf filled, and the price was that
 * a page set at 2.00 em turned into one set at 1.20: the same book in two
 * different hands' worth of air, which reads as a fault however full the page.
 *
 * So it is the foot. One hand, one leading, and a leaf ends where its content
 * ends — 71% of the well at the median, 63% at the tenth percentile, all of it
 * on the heaviest. That is what a printed parallel translation looks like on
 * its English side, and it is the only one of the three that a reader reads as
 * meaning something: a page that ends early ends something.
 *
 * The hand is therefore cut for the heaviest leaf in the book at that one
 * leading ([ENGLISH_LEAF_REFERENCE_PROSE]), and `MushafEnglishSheet` still
 * measures each leaf as it will be drawn — but only as a guarantee against
 * clipping, never as the thing that sets the page.
 */

/**
 * The page the hand is cut for, in characters of set prose.
 *
 * With one leading for the whole book (rule 3), the heaviest leaf is what sets
 * the type: it has to fit its well, and every leaf lighter than it then ends
 * short of the foot by exactly as much as it is lighter.
 *
 * Measured over all 604 leaves of `data/quran.db`
 * (`tools/measure_english_leaves.py`, verse translations with their marks and
 * joining spaces) the book runs 1,055 characters at the 1st percentile, 1,286
 * at the 10th, 1,469 at the median, 1,663 at the 90th and 1,997 at the worst,
 * which is page 579. So the anchor is 1,997 and a little over — 3%, because
 * the hand is solved from an *estimate* of how many characters go to a line
 * and the heaviest leaf must not be the one the estimate is wrong about.
 *
 * Leaves therefore fill 71% of the well at the median, 63% at the tenth
 * percentile, and all of it at the worst. That white at the foot is the price
 * of the other two rules, and it is the right one to pay: a reader sees a
 * change of leading between two pages immediately and reads a short page as
 * the end of something.
 */
const val ENGLISH_LEAF_REFERENCE_PROSE = 2060f

/**
 * The leading the whole book is set on.
 *
 * One number, for every leaf. EB Garamond runs a small x-height and would take
 * more air on a long measure; this one is about fifty characters, which wants
 * less. 1.40 em is where a serif book of this measure sits, and every 0.05 em
 * added to it comes straight off the type — the heaviest leaf has to fit
 * either way, so `hand² × leading` is a constant of the leaf.
 */
const val ENGLISH_LEAF_LEADING_EM = 1.40f

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
        ENGLISH_LEAF_LEADING_EM *
        ENGLISH_LEAF_REFERENCE_PROSE
    return sqrt(wellHeightPx * measureWidthPx / denominator)
        .coerceIn(ENGLISH_LEAF_MIN_FONT_PX, ENGLISH_LEAF_MAX_FONT_PX)
}

/**
 * The leading a leaf is actually drawn on: the book's, unless that would put
 * the block past the foot of the well.
 *
 * It should never have to. The hand is cut for the heaviest leaf in the book
 * with three percent to spare ([ENGLISH_LEAF_REFERENCE_PROSE]), so every leaf
 * fits by construction. But the hand is solved from an estimate of how many
 * characters go to a line, and an estimate can be wrong on some page nobody
 * looked at — and being wrong there means revelation clipped off the bottom of
 * it. So the leaf is measured as it will be drawn, and if it still stands past
 * the foot the leading closes by exactly the overflow.
 *
 * Only closes, and only that leaf. A page set a hair tighter than its
 * neighbours is a page nobody notices; a page missing its last line is not.
 */
fun englishLeafFittedLeadingEm(
    leadingEm: Float,
    measuredHeightPx: Float,
    wellHeightPx: Float,
    pitchesPx: Float,
): Float {
    if (measuredHeightPx <= wellHeightPx || pitchesPx <= 0f) return leadingEm
    return leadingEm - (measuredHeightPx - wellHeightPx) / pitchesPx
}
