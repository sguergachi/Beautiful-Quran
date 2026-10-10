package com.beautifulquran.domain

/**
 * Where the English leaf's lines break: as a compositor sets a ragged page,
 * not as a typewriter does.
 *
 * The leaf used to break greedily (`LineBreak.Strategy.Simple`): fill each line
 * as far as the next word allows and take what is left. That keeps the rag
 * *active* — lines reach the margin and the edge moves — which an evened rag
 * does not (see `docs/QURAN_TYPOGRAPHY.md` §13.5: Balanced drew a phantom
 * second margin a word inside the true one). But greedy cannot see one line
 * ahead, so a long word or a word bound to its verse mark that just misses
 * leaves a hole a quarter of the measure deep. Ar-Rahman opened on three of
 * them in its first three lines.
 *
 * This keeps greedy's activity and refuses only its holes:
 *
 * - **Fewest lines first.** The paragraph sets in exactly as many lines as it
 *   can, so the pagination's arithmetic — and the bisection that rests on line
 *   count rising with the text — is untouched.
 * - **A hole costs, a rag does not.** A line short of the measure by up to
 *   [HOLE_EM] is free; past that it costs the square of the excess. So the
 *   breaker moves a word only to fill a real hole, never to even out lines
 *   that were already fine — which is what kept Balanced's band from forming.
 * - **A hyphen costs about a hole.** It is taken where it fills one, not
 *   wherever it fits ([HYPHEN_COST]).
 * - **Ties go to the fuller line,** line by line from the top: among the
 *   settings that cost the same, the one greedy would have chosen.
 *
 * Every break it chooses the caller writes into the text as a line break, so
 * the platform only ever sets lines that already fit: what this decides is
 * what is drawn. (Leaving a hyphen to the platform's own breaker was tried;
 * it would not take one inside a word, and the line overflowed.)
 *
 * Pure arithmetic over measured positions; unit-tested on the JVM.
 */
object EnglishRag {
    /** How far short of the measure a line may fall for free, in ems. */
    const val HOLE_EM = 1.65f

    /** What a hyphenated line costs, in ems squared: about a two-em hole. */
    const val HYPHEN_COST = 4.2f

    /**
     * The candidate line ends of one paragraph, in reading order.
     *
     * [contentEnd] is where a line ending at the candidate stops, without any
     * hyphen; [hyphenPx] is the hyphen written there besides (0 for a word
     * space). [nextStart] is where the following line begins in the same
     * unbroken coordinates.
     */
    class Candidates(
        val contentEnd: FloatArray,
        val nextStart: FloatArray,
        val hyphenPx: FloatArray,
        /** Where the paragraph's last line ends. */
        val textEnd: Float,
    ) {
        val size: Int get() = contentEnd.size
    }

    /**
     * The chosen candidates, by index, in order — or null when the paragraph
     * cannot be set on [measurePx] at all (a single unbreakable run wider than
     * the line), in which case the caller should leave the breaking alone.
     *
     * [slackPx] is held back from the measure so a line the platform measures
     * a hair wider than this one still fits.
     */
    fun breaks(
        candidates: Candidates,
        measurePx: Float,
        emPx: Float,
        slackPx: Float = 1f,
    ): IntArray? {
        val n = candidates.size
        val fit = measurePx - slackPx
        val hole = HOLE_EM * emPx
        val hyphenCost = HYPHEN_COST * emPx * emPx
        // States: 0 is the paragraph's start, s + 1 the line after candidate s.
        val settable = BooleanArray(n + 1)
        val lines = IntArray(n + 1)
        val cost = DoubleArray(n + 1)
        val next = IntArray(n + 1)

        fun startOf(state: Int) = if (state == 0) 0f else candidates.nextStart[state - 1]
        fun width(start: Float, at: Int) =
            candidates.contentEnd[at] + candidates.hyphenPx[at] - start

        for (state in n downTo 0) {
            val start = startOf(state)
            // The rest fits: it is the paragraph's last line, and costs nothing.
            if (candidates.textEnd - start <= fit) {
                settable[state] = true
                lines[state] = 1
                cost[state] = 0.0
                next[state] = n
                continue
            }
            var bestLines = Int.MAX_VALUE
            var bestCost = Double.MAX_VALUE
            var bestEnd = -1
            for (at in state until n) {
                if (candidates.contentEnd[at] - start > fit) break
                val width = width(start, at)
                if (width > fit) continue
                val after = at + 1
                if (!settable[after]) continue
                val short = measurePx - width - hole
                val own = (if (short > 0f) short.toDouble() * short else 0.0) +
                    if (candidates.hyphenPx[at] > 0f) hyphenCost.toDouble() else 0.0
                val count = lines[after] + 1
                val total = cost[after] + own
                // Later candidates win ties: the fuller line, as greedy sets it.
                if (count < bestLines || (count == bestLines && total <= bestCost + TIE)) {
                    bestLines = count
                    bestCost = total
                    bestEnd = at
                }
            }
            if (bestEnd < 0) continue
            settable[state] = true
            lines[state] = bestLines
            cost[state] = bestCost
            next[state] = bestEnd
        }
        if (!settable[0]) return null
        val out = ArrayList<Int>()
        var state = 0
        while (next[state] < n) {
            out += next[state]
            state = next[state] + 1
        }
        return out.toIntArray()
    }

    private const val TIE = 1e-3
}
