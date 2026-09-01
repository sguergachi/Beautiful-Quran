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
 *    English needs at a legible size — two or three for nearly all of the 604,
 *    about 1,250 leaves in all. That is what stops the heaviest page in the
 *    book from setting the type for every other one. See `EnglishBook.kt`.
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
 * So it is the foot, and then rule 4 took most of the range away from it: a
 * page too heavy for one leaf is now two leaves rather than one crushed one.
 * What is left for the foot to absorb is the remainder — 81% of the well at the
 * median, 58% at the tenth percentile, all but full at the ninetieth. That is
 * what a printed parallel translation looks like on its English side, and it is the
 * only one of the three that a reader reads as meaning something: a page that
 * ends early ends something.
 *
 * The hand is therefore cut for a full leaf at that one leading
 * ([englishLeafReferenceBlock]), and `MushafEnglishSheet` still measures each
 * leaf as it will be drawn — but only as a guarantee against clipping, never as
 * the thing that sets the page.
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
/**
 * How tight the book will ever be set.
 *
 * The fitted leading closes a leaf that would run past the foot, and left
 * unbounded it will close as far as the arithmetic asks — which on the leaf
 * that carries 2:282 is far enough that the lines run into one another. A page
 * whose ascenders touch the descenders above them is not a tight page, it is an
 * unreadable one. So the leading stops here, and [englishLeafOverflowHandPx]
 * takes up whatever is left.
 */
const val ENGLISH_LEAF_MIN_LEADING_EM = 1.15f

const val ENGLISH_LEAF_MIN_FONT_PX = 18f
const val ENGLISH_LEAF_MAX_FONT_PX = 140f

/**
 * The type specimen the hand is cut against: real prose from this translation.
 *
 * It used to be one sentence repeated — "And it is He who created the heavens
 * and the earth in truth." — chosen for being ordinary. It is not ordinary. Its
 * words average 3.7 letters where the translation's average 4.4, and short
 * words pack tighter and waste less at the end of a ragged line: measured on
 * device it set **43 characters to the line where the book really sets 40.3**.
 * The hand was therefore cut 6% too small for the text it had to hold, which
 * put 108 leaves past their well and closed their leading.
 *
 * So the specimen is a passage of the book itself, 74:29–74:36, picked because
 * its characters per word (5.372) and its spread of word lengths (sd 2.27) are
 * the closest of any run in the Qur'an to the whole translation's (5.382, 2.28).
 * Where the lines break is what decides how much paper a page takes, and only
 * real prose breaks lines where real prose breaks them.
 */
const val ENGLISH_LEAF_SPECIMEN =
    "Blackening the skins Over it are nineteen [angels] And We have not made " +
        "the keepers of the Fire except angels. And We have not made their " +
        "number except as a trial for those who disbelieve - that those who " +
        "were given the Scripture will be convinced and those who have " +
        "believed will increase in faith and those who were given the " +
        "Scripture and the believers will not doubt and that those in whose " +
        "hearts is hypocrisy and the disbelievers will say, \"What does Allah " +
        "intend by this as an example?\" Thus does Allah leave astray whom He " +
        "wills and guides whom He wills. And none knows the soldiers of your " +
        "Lord except Him. And mention of the Fire is not but a reminder to " +
        "humanity No! By the moon And [by] the night when it departs And [by] " +
        "the morning when it brightens Indeed, the Fire is of the greatest " +
        "[afflictions] As a warning to humanity "

/**
 * How much more than a leaf's worth the hand is cut against.
 *
 * Not a safety margin: a measured conversion between the block the hand is cut
 * against and the leaf it stands for. The specimen is unbroken prose set as one
 * long run; a leaf is the same translation broken by verse marks, brackets and
 * quotation, and it does not pack as tightly. The margin is the difference, and
 * it is the *only* knob that changes it — the capacity cancels, because the
 * reference block is itself the capacity, so a smaller capacity simply buys a
 * larger hand and the leaf holds the same share of the well.
 *
 * It was 1.01, and 1.01 is not enough. Measured on device across five leaves of
 * Ar-Rahman and As-Saffat, with the well at 1,849 px and the book's leading at
 * 80 px — 23.1 lines — a leaf's 940 charged characters wanted **24 and
 * sometimes 25** lines. The observed density ran 39.2 to 40.9 charged
 * characters to the line against the 41.1 the margin assumed: optimistic by
 * three percent typically and by five at the worst.
 *
 * A leaf that asks for more lines than the well has does not spill. It closes
 * its leading — measurably, from 80 px to 77 and to 74 on the leaf that wanted
 * twenty-five — so leaves set beside each other in the same book were set at
 * three different leadings, which is the one thing a single leading exists to
 * prevent. The hand itself never moved: the verse mark's gold inks 80 to 83 px
 * on every one of those leaves.
 *
 * The figure is swept on device, three builds, eight leaves each:
 *
 * ```
 *     1.01   24-25 lines wanted of 23.1   leading closes to 77, and to 74
 *     1.04   23 lines of 23.4, pitch 79   one leaf in six still closes it
 *     1.05   22 lines of 23.7, pitch 78   none close it
 *     1.06   22 lines of 23.7, pitch 78   none close it
 * ```
 *
 * 1.04, because the leaf a reader complains about is the one with white on it,
 * and above 1.04 every leaf gives up most of a line to buy out the last leaf in
 * six. What is left at 1.04 is a leading 2.5% tight on that sixth leaf, which is
 * the residue of a character estimate and does not come out with a constant.
 * It costs 1.5% of the hand — the block goes as the square of it.
 *
 * Re-measure with a device capture after changing the face, the measure or the
 * mark. A leaf whose leading has closed is the symptom: photograph two leaves
 * and compare their line pitch.
 */
const val ENGLISH_LEAF_REFERENCE_MARGIN = 1.04f

/**
 * A block of the mass a leaf holds, for the hand to be cut against.
 */
fun englishLeafReferenceBlock(): String {
    val target = (ENGLISH_LEAF_CAPACITY_CHARS * ENGLISH_LEAF_REFERENCE_MARGIN).toInt()
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
 * It should almost never have to. The hand is cut for a full leaf with five
 * percent to spare ([englishLeafReferenceBlock]) and `englishPageParts` fills
 * leaves rather than evening them, so it cannot hand out one over that
 * capacity: every leaf fits by construction — every leaf but one. 2:282 is a
 * single sentence of 1,333 characters, half as long again as a leaf holds, and
 * no rule splits a sentence; that leaf is set tight, and this is what sets it.
 *
 * It is also the guarantee behind the estimate. The hand is solved from
 * *characters*, not from a layout, and a character count can be wrong on some
 * page nobody looked at — being wrong there would mean revelation clipped off
 * the bottom of it. So the leaf is measured as it will be drawn, and if it
 * still stands past the foot the leading closes by exactly the overflow.
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
    return (leadingEm - (measuredHeightPx - wellHeightPx) / pitchesPx)
        .coerceAtLeast(ENGLISH_LEAF_MIN_LEADING_EM)
}

/**
 * The hand a leaf falls back to when even [ENGLISH_LEAF_MIN_LEADING_EM] will
 * not bring it inside the well.
 *
 * One leaf in the Qur'an needs this and it will always be the same one: 2:282
 * is a single sentence of 1,333 characters, half as long again as a leaf holds,
 * and no pagination splits a sentence. Rule 1 says one hand for the whole book
 * and this breaks it — knowingly, because the alternatives on that one leaf are
 * lines that overlap or revelation clipped off the foot, and a page set a few
 * percent small is the only one of the three a reader can still read.
 *
 * [standsPx] is what the block measures at the floored leading; height goes as
 * the square of the hand, so one step lands it and the caller re-measures.
 */
fun englishLeafOverflowHandPx(
    handPx: Float,
    standsPx: Float,
    wellHeightPx: Float,
): Float {
    if (standsPx <= wellHeightPx || standsPx <= 0f || wellHeightPx <= 0f) return handPx
    return (handPx * sqrt(wellHeightPx / standsPx))
        .coerceIn(ENGLISH_LEAF_MIN_FONT_PX, ENGLISH_LEAF_MAX_FONT_PX)
}
