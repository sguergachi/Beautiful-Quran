package com.beautifulquran.domain

import kotlin.math.sqrt

/**
 * The English book's type size and page leading.
 *
 * One hand is calibrated from the reference specimen for the selected face,
 * measure and well. A page's text never enters that choice. The measured
 * ruler paginates at the same size and carries long verses across leaves.
 *
 * Within a page, leading may close to absorb rounding or open a little to
 * card a nearly-full leaf to its foot. A chapter ending stays short. Neither
 * adjustment changes font size, and the lower bound keeps lines apart.
 */

/**
 * The leading the whole book is set on.
 *
 * One number, for every leaf. A small-x-height face would take
 * more air on a long measure; this one is about fifty characters, which wants
 * less. 1.40 em is where a serif book of this measure sits, and every 0.05 em
 * added to it comes straight off the type — the heaviest leaf has to fit
 * either way, so `hand² × leading` is a constant of the leaf.
 */
const val ENGLISH_LEAF_LEADING_EM = 1.40f

/**
 * How tight the book will ever be set.
 *
 * The fitted leading closes a leaf that would run past the foot, and left
 * unbounded it will close as far as the arithmetic asks. A page
 * whose ascenders touch the descenders above them is not a tight page, it is an
 * unreadable one. So the leading stops here. Pagination carries long verses
 * across leaves at the book's fixed type size instead of shrinking a page.
 */
const val ENGLISH_LEAF_MIN_LEADING_EM = 1.15f

/**
 * How loose the book will ever be set — the other end of the same rescue.
 *
 * A leaf whose text stops a line and a half above the foot is the fault a
 * reader actually reports, and no constant removes it: the pagination counts
 * characters, the margin has to cover the *worst* leaf or that leaf overflows,
 * and so the typical leaf comes out short by the spread between them. Swept on
 * device the spread is about a line and a half of twenty-three.
 *
 * A compositor setting a page short of the foot cards it out — adds a little
 * lead between the lines until the block sits flush. That is what this is, and
 * it is the same lever as [ENGLISH_LEAF_MIN_LEADING_EM] pushed the other way.
 * 1.50 against the book's 1.40 covers seven percent, which covers the spread
 * with room; past it the leaf is not a nearly-full page that wants carding but
 * a chapter ending, and a chapter ending is *supposed* to stop short. Those are
 * left on the book's own leading — see [englishLeafFittedLeadingEm].
 */
const val ENGLISH_LEAF_MAX_LEADING_EM = 1.50f

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
 * 80 px — 23.1 lines — a leaf's 940 charged characters (the capacity then)
 * wanted **24 and
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
 *
 * The sweep above was taken at a capacity of 940. Re-checked by that same
 * symptom at 1,400 (Ta-Ha, Ad-Dukhan, Baqarah and Al-Kahf captured on device,
 * every line of every leaf at a pitch of 62 px) and again at the 1,250 the book
 * is set to (Ta-Ha and Baqarah, every line at 67 px). No leaf asked for more
 * lines than its well had, so 1.04 still covers the density estimate at the
 * longer line.
 */
const val ENGLISH_LEAF_REFERENCE_MARGIN = 1.04f

/**
 * A block of the mass a leaf holds, for the hand to be cut against.
 */
fun englishLeafReferenceBlock(capacityChars: Int = ENGLISH_LEAF_CAPACITY_CHARS): String {
    val target = (capacityChars * ENGLISH_LEAF_REFERENCE_MARGIN).toInt()
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
 * The page's leading at the book's fixed font size.
 *
 * The measured ruler carries oversized verses across leaves. This fit absorbs
 * residual layout rounding, closing overflows down to the minimum leading or
 * carding a nearly-full page out to its foot. A chapter ending that would need
 * more than the maximum keeps its ordinary leading and ends short.
 *
 * The renderer remeasures a fitted page to account for integer paragraph
 * heights beside fractional chapter furniture, then uses this same correction
 * for any remaining overrun. Font size never participates in that correction.
 */
fun englishLeafFittedLeadingEm(
    leadingEm: Float,
    measuredHeightPx: Float,
    wellHeightPx: Float,
    pitchesPx: Float,
): Float {
    if (pitchesPx <= 0f) return leadingEm
    val flush = leadingEm + (wellHeightPx - measuredHeightPx) / pitchesPx
    return when {
        // Over the foot: close, as far as the floor.
        flush < leadingEm -> flush.coerceAtLeast(ENGLISH_LEAF_MIN_LEADING_EM)
        // Short of it, but within carding distance: card it out flush.
        flush <= ENGLISH_LEAF_MAX_LEADING_EM -> flush
        // Short by more than that: a chapter ends here. Leave it short.
        else -> leadingEm
    }
}

/**
 * Closes a measured overrun by at least one pixel of baseline pitch.
 * Android rounds that pitch per line; a fractional correction can move only
 * chapter furniture while leaving the prose height unchanged. Type size stays
 * fixed and the minimum leading still prevents overlapping lines.
 */
fun englishLeafClosingLeadingEm(
    leadingEm: Float,
    measuredHeightPx: Float,
    wellHeightPx: Float,
    pitchesPx: Float,
    handPx: Float,
): Float {
    if (measuredHeightPx <= wellHeightPx || pitchesPx <= 0f || handPx <= 0f) return leadingEm
    val fitted = englishLeafFittedLeadingEm(leadingEm, measuredHeightPx, wellHeightPx, pitchesPx)
    return minOf(fitted, leadingEm - 1f / handPx).coerceAtLeast(ENGLISH_LEAF_MIN_LEADING_EM)
}
