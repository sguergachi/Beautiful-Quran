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
 * 4. **The leaf is not the page.** A Madinah page takes as many leaves as its
 *    English needs at a legible size, which for 71 of the 604 is two. That is
 *    what stops the heaviest page in the book from setting the type for all of
 *    them. See `EnglishBook.kt`.
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
 * Ordinary prose from this translation, repeated to make the reference block
 * the hand is cut against.
 *
 * Prose, not a pangram: what matters is the mix of letters, spaces and word
 * lengths the book is actually set in, because where the *lines break* is what
 * decides how much paper a page takes.
 */
const val ENGLISH_LEAF_SPECIMEN =
    "And it is He who created the heavens and the earth in truth. "

/**
 * A block of exactly the mass a leaf holds, for the hand to be cut against.
 *
 * A little over it — 5% — because a ragged line ends where its last whole word
 * ends, half a word short of the measure on average, and the fullest leaf in
 * the book must not be the one that discovers the difference.
 */
fun englishLeafReferenceBlock(): String {
    val target = (ENGLISH_LEAF_CAPACITY_CHARS * 1.05f).toInt()
    val out = StringBuilder(target + ENGLISH_LEAF_SPECIMEN.length)
    while (out.length < target) out.append(ENGLISH_LEAF_SPECIMEN)
    return out.substring(0, target)
}

/** Where the hand search starts. Any size works; this one converges in two. */
const val ENGLISH_LEAF_PROBE_FONT_PX = 40f

/**
 * The one hand for the whole English book, from a *measurement* rather than a
 * model: the size at which a full leaf's worth of prose exactly fills the well.
 *
 * It used to be a closed form — characters to the line from the face's average
 * advance, lines from the leaf's mass, the two solved against the well. The
 * arithmetic was right and the input was not: measured on device a line held 56
 * characters where the advance model said 42, a third out, and a leaf filled to
 * its capacity would have overflowed by a tenth. Its leading would then have
 * closed, which is the one thing the book's single leading exists to prevent.
 *
 * So nothing is estimated. [measuredHeightPx] is [englishLeafReferenceBlock]
 * laid out at [probePx] exactly as it will be drawn — same face, same measure,
 * same rag, same line-breaking — and a block's height goes as the square of the
 * hand, because it holds `1/k` more characters to the line *and* each line
 * stands `k` taller. So one step lands it, and the caller takes a second for
 * the rounding that discrete line counts leave behind.
 */
fun englishLeafHandPx(
    probePx: Float,
    measuredHeightPx: Float,
    wellHeightPx: Float,
): Float {
    if (probePx <= 0f || measuredHeightPx <= 0f || wellHeightPx <= 0f) {
        return ENGLISH_LEAF_MIN_FONT_PX
    }
    return (probePx * sqrt(wellHeightPx / measuredHeightPx))
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
