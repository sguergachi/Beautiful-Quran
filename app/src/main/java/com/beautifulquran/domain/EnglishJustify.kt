package com.beautifulquran.domain

import kotlin.math.abs
import kotlin.math.min

/**
 * Where the English leaf's lines break, and how each is drawn out to the
 * measure: justified as a fine book is, so the reader never notices it was.
 *
 * Justification was tried here once and taken out (docs/QURAN_TYPOGRAPHY.md
 * §13.5). That version left the breaks to the platform, one line at a time, and
 * had only the word space to stretch, so a line that could not take its next
 * word ran three times the natural space. This one does what TeX's
 * micro-typography does on a narrow measure:
 *
 * - **The whole paragraph is broken at once** (Knuth and Plass). Every setting
 *   of the paragraph is weighed and the one whose lines stretch least, and
 *   most alike, is taken. A word a line cannot quite take moves to share the
 *   gap with the line above, instead of leaving one line loose under a tight
 *   one.
 * - **The glyphs take part of the stretch** (Zapf's hz-program, pdfTeX's font
 *   expansion). A line's letters may widen or narrow by a couple of percent —
 *   under the size anyone sees a change at — so the word spaces carry less of
 *   the slack and stay even.
 * - **Lines that differ sharply in tightness cost extra**, so a loose line is
 *   not set against a tight one, which is the contrast the eye reads as a
 *   river.
 *
 * Pure arithmetic over measured positions; unit-tested on the JVM.
 */
object EnglishJustify {
    /**
     * How a paragraph may be drawn out. Shares are of the ideal word space,
     * and of a line's glyph width for [expansion].
     */
    data class Tuning(
        /** The word space a justified line aims for, as a multiple of the natural one. */
        val idealSpace: Float = 1f,
        /** How far a word space may open past the ideal before a line counts as loose. */
        val stretch: Float = 0.5f,
        /** How far a word space may close under the ideal; 0 keeps every line at least natural. */
        val shrink: Float = 0f,
        /** How far a line's letters may widen, or narrow, as a share of their width. */
        val expansion: Float = 0.02f,
        /**
         * How far each letter's spacing may open or close, in ems — a line's
         * slack shared over its forty letters as well as its seven spaces.
         * Needs [Text] counts to know the letters.
         */
        val tracking: Float = 0f,
        /**
         * What a change in tightness from one line to the next costs, against a
         * line's own badness: the eye reads texture by comparing neighbours,
         * so a loose line beside a tight one shows where two decent ones do not.
         */
        val smoothness: Float = 0f,
        /**
         * The share of [expansion] and [tracking] a line may use to *close*.
         * Letters squeezed together read as cramped well before letters set a
         * little apart read as loose, so a tight line closes mostly in its
         * word spaces.
         */
        val letterShrink: Float = 1f,
        /**
         * Keep the line count greedy wrapping would set, so the ruler that
         * paginates with the platform's own wrapping counts the lines drawn.
         * Off, the paragraph takes however many lines sets it most evenly.
         */
        val keepLineCount: Boolean = true,
    )

    /**
     * What a paragraph's lines hold, by text offset, so a line knows its
     * stretchable gaps and its letters: prefix counts over the text, and the
     * offset of each candidate's word space. Without it every line's gaps are
     * its word spaces and it has no letter count.
     */
    class Text(
        /** Stretchable gaps before each offset: word spaces and bound spaces. */
        val gapsBefore: IntArray,
        /** Letters (anything else that advances) before each offset. */
        val lettersBefore: IntArray,
        val candidateOffset: IntArray,
        /** One em, in px: what [Tuning.tracking] is measured in. */
        val emPx: Float,
    ) {
        val length: Int get() = gapsBefore.size - 1
    }

    /**
     * How the English leaf justifies, chosen by measuring the whole book
     * (`englishJustifyAudit`; docs/QURAN_TYPOGRAPHY.md §13.5): word spaces
     * aim at 1.2 of the natural space and range about 0.19–0.28 em on nine
     * lines in ten; letters widen or narrow up to 3 % and open or close by
     * up to 0.0075 em; neighbouring lines are kept alike.
     */
    val LEAF = Tuning(
        idealSpace = 1.2f,
        stretch = 0.4f,
        shrink = 0.25f,
        expansion = 0.03f,
        tracking = 0.0075f,
        smoothness = 1f,
        keepLineCount = false,
    )

    /**
     * Bump whenever how a paragraph breaks changes in a way [LEAF] does not
     * show: the book's pagination cache is keyed by this and [LEAF].
     */
    const val VERSION = 1

    /** What the book's pagination cache is keyed by for the leaf's justification. */
    val cacheKey: String get() = "|justify$VERSION|$LEAF"

    /** One line of the setting: where its letters end, and how it is drawn out. */
    class Line(
        /** The candidate it ends on, or -1 for the paragraph's last line. */
        val end: Int,
        /** Whether it is drawn out to the measure at all. */
        val justified: Boolean,
        /** How much wider its glyphs are set: 0.01 is 1 %, negative narrower. */
        val expansion: Float,
        /** What each of its gaps gains, in px, past its natural width; negative closes. */
        val spaceExtraPx: Float,
        /** How many stretchable gaps it has. */
        val spaces: Int,
        /** What each letter's spacing gains, in px; negative closes. */
        val trackingPx: Float = 0f,
        /** How many letters it has, where known. */
        val letters: Int = 0,
    )

    /**
     * The paragraph's lines, or null when it cannot be set on [measurePx] at
     * all (a single unbreakable run wider than the line).
     *
     * [spacePx] is one natural word space. [justifyLast] draws the paragraph's
     * last line out as well — for a paragraph a leaf breaks off mid-sentence,
     * whose last line on the leaf is not its last line at all.
     */
    fun set(
        candidates: EnglishRag.Candidates,
        measurePx: Float,
        spacePx: Float,
        justifyLast: Boolean = false,
        slackPx: Float = 1f,
        tuning: Tuning = Tuning(),
        text: Text? = null,
    ): List<Line>? {
        val n = candidates.size
        val fit = measurePx - slackPx
        val lines = IntArray(n + 1)
        val cost = DoubleArray(n + 1)
        val next = IntArray(n + 1)
        val fitness = IntArray(n + 1)
        // The ratio of the first line from each state on: what the line above
        // it is compared with.
        val firstRatio = FloatArray(n + 1) { Float.NaN }
        val settable = BooleanArray(n + 1)

        fun startOf(state: Int) = if (state == 0) 0f else candidates.nextStart[state - 1]
        // A line from [state] to candidate [at] (n: the paragraph's end), by its
        // stretchable gaps and its letters.
        fun fromOffset(state: Int) = if (state == 0) 0 else text!!.candidateOffset[state - 1] + 1
        fun toOffset(at: Int) = if (at >= n) text!!.length else text!!.candidateOffset[at]
        fun gaps(state: Int, at: Int): Int =
            if (text == null) at - state else text.gapsBefore[toOffset(at)] - text.gapsBefore[fromOffset(state)]
        fun letters(state: Int, at: Int): Int =
            if (text == null) 0 else text.lettersBefore[toOffset(at)] - text.lettersBefore[fromOffset(state)]
        val emPx = text?.emPx ?: 0f
        fun ratio(width: Float, state: Int, at: Int) =
            ratioOf(width, gaps(state, at), letters(state, at), fit, spacePx, emPx, tuning)
        fun fits(width: Float, state: Int, at: Int): Boolean =
            width <= fit || (!tuning.keepLineCount && ratio(width, state, at) >= -1f)

        for (state in n downTo 0) {
            val start = startOf(state)
            val restWidth = candidates.textEnd - start
            if (fits(restWidth, state, n)) {
                settable[state] = true
                lines[state] = 1
                next[state] = n
                val r = ratio(restWidth, state, n)
                // A last line the next leaf carries on is a line like any
                // other and costs like one; the paragraph's own last line
                // costs nothing where it stands. The ruler that paginates asks
                // with the same [justifyLast] the leaf draws with, so the two
                // count the same lines.
                if (justifyLast || restWidth > fit) {
                    cost[state] = demerits(r)
                    fitness[state] = fitnessOf(r)
                    firstRatio[state] = r
                } else {
                    cost[state] = 0.0
                    // A last line left short is neither tight nor loose.
                    fitness[state] = FITNESS_ANY
                }
                continue
            }
            var bestLines = Int.MAX_VALUE
            var bestCost = Double.MAX_VALUE
            var bestEnd = -1
            var bestFitness = 0
            var bestRatio = Float.NaN
            for (at in state until n) {
                val width = candidates.contentEnd[at] - start
                if (!fits(width, state, at)) break
                val after = at + 1
                if (!settable[after]) continue
                val r = ratio(width, state, at)
                val own = demerits(r)
                val f = fitnessOf(r)
                val clash = fitness[after] != FITNESS_ANY && abs(f - fitness[after]) > 1
                val below = firstRatio[after]
                val jump = if (below.isNaN() || r.isInfinite() || below.isInfinite()) {
                    0.0
                } else {
                    val d = (r - below).toDouble().coerceIn(-10.0, 10.0)
                    tuning.smoothness * 100.0 * d * d
                }
                val total = cost[after] + own + jump * jump + if (clash) ADJACENT_DEMERITS else 0.0
                val count = lines[after] + 1
                // Fewest lines first when the count is kept; otherwise the
                // count is the optimum's own. Later candidates win ties: the
                // fuller line, as greedy sets it.
                val better = if (tuning.keepLineCount) {
                    count < bestLines || (count == bestLines && total <= bestCost + TIE)
                } else {
                    total <= bestCost + TIE
                }
                if (better) {
                    bestLines = count
                    bestCost = total
                    bestEnd = at
                    bestFitness = f
                    bestRatio = r
                }
            }
            if (bestEnd < 0) continue
            settable[state] = true
            lines[state] = bestLines
            cost[state] = bestCost
            next[state] = bestEnd
            fitness[state] = bestFitness
            firstRatio[state] = bestRatio
        }
        if (!settable[0]) return null

        val out = ArrayList<Line>()
        var state = 0
        while (true) {
            val start = startOf(state)
            val end = next[state]
            val last = end >= n
            val width = if (last) candidates.textEnd - start else candidates.contentEnd[end] - start
            val lineEnd = if (last) n else end
            val spaces = gaps(state, lineEnd)
            val r = ratio(width, state, lineEnd)
            // A last line stands where it ends — unless it was set over the
            // measure and must close, or it is carried on and is drawn out like
            // any other line, but not so short that drawing it out opens holes.
            val leaveShort = last && width <= fit && (!justifyLast || r > LAST_LINE_MAX_RATIO)
            out += if (leaveShort) {
                Line(-1, false, 0f, 0f, spaces)
            } else {
                drawnOut(if (last) -1 else end, width, spaces, fit, spacePx, tuning, letters(state, lineEnd), emPx)
            }
            if (last) break
            state = end + 1
        }
        return out
    }

    /**
     * How a line [width] wide at natural spacing, with [spaces] word spaces, is
     * drawn out (or closed) to [fit]: the letters take their share up to the
     * tuning's expansion, the spaces the rest. A line with no space between
     * words has nothing to stretch evenly, and is left as it stands.
     */
    internal fun drawnOut(
        end: Int,
        width: Float,
        spaces: Int,
        fit: Float,
        spacePx: Float,
        tuning: Tuning = Tuning(),
        letters: Int = 0,
        emPx: Float = 0f,
    ): Line {
        if (spaces == 0 || width == fit) return Line(end, false, 0f, 0f, spaces)
        val r = ratioOf(width, spaces, letters, fit, spacePx, emPx, tuning)
        // In proportion while the line is within its nominal range; past it
        // the letters stop at their limit and only the spaces keep going.
        val share = r.coerceIn(-1f, 1f).let { if (it < 0f) it * tuning.letterShrink else it }
        val expansion = tuning.expansion * share
        val trackingPx = tuning.tracking * emPx * share
        val spaceExtra = (fit - width * (1f + expansion) - letters * trackingPx) / spaces
        return Line(end, true, expansion, spaceExtra, spaces, trackingPx, letters)
    }

    /**
     * How far a line stretches (positive) or shrinks (negative) from its ideal
     * to reach [fit], as a share of how far it nominally can.
     */
    private fun ratioOf(
        width: Float,
        spaces: Int,
        letters: Int,
        fit: Float,
        spacePx: Float,
        emPx: Float,
        tuning: Tuning,
    ): Float {
        val glyphs = (width - spaces * spacePx).coerceAtLeast(0f)
        val ideal = spacePx * tuning.idealSpace
        val idealWidth = glyphs + spaces * ideal
        val letterGive = glyphs * tuning.expansion + letters * tuning.tracking * emPx
        return if (idealWidth <= fit) {
            val stretch = letterGive + spaces * ideal * tuning.stretch
            if (stretch <= 0f) Float.POSITIVE_INFINITY else (fit - idealWidth) / stretch
        } else {
            val give = letterGive * tuning.letterShrink + spaces * ideal * tuning.shrink
            if (give <= 0f) Float.NEGATIVE_INFINITY else (fit - idealWidth) / give
        }
    }

    /**
     * TeX's demerits: a line's badness and a penalty per line, squared.
     *
     * Unlike TeX, badness is not capped at 10 000. TeX caps it because past
     * that it would rather report an overfull box; here every line must set,
     * so between two very loose settings the less loose one should still win.
     */
    private fun demerits(r: Float): Double {
        val badness = if (r.isInfinite()) MAX_BADNESS else min(MAX_BADNESS, 100.0 * abs(r * r * r))
        val d = LINE_PENALTY + badness
        return d * d
    }

    /** TeX's fitness classes. */
    private fun fitnessOf(r: Float): Int = when {
        r > 1f -> 3 // very loose
        r > 0.5f -> 2 // loose
        r >= -0.5f -> 1 // decent
        else -> 0 // tight
    }

    private const val FITNESS_ANY = -1
    private const val MAX_BADNESS = 1e9

    /** Past twice its nominal stretch, a carried last line is left short. */
    private const val LAST_LINE_MAX_RATIO = 2f
    private const val LINE_PENALTY = 10.0

    /** TeX's adjdemerits: a loose line beside a decent one. */
    private const val ADJACENT_DEMERITS = 10_000.0
    private const val TIE = 1e-6
}
