package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.TajweedPacing

/**
 * One reading position across a Scroll verse's English glosses, so the ink
 * follows the reciter as one motion instead of a word-by-word stop and start.
 *
 * Each gloss is anchored to its Arabic word (or to every Arabic word it was
 * merged across): it starts as that word starts and is through as the next
 * begins. Inside a word the position follows the reciter's tajweed tempo
 * ([TajweedPacing.Curve.tempo] — slower on a held madd, never parked). A real
 * pause — a gap between timed words or quiet heard in the audio
 * ([EnglishPauses]), [ENGLISH_PAUSE_MS] or more — finishes the gloss as the
 * voice stops and holds, crisply, until it returns.
 * The whole track is then speed-smoothed ([EnglishSmoothTrack]) so no word
 * boundary is a jump. Spaces between glosses take no time.
 */
internal class EnglishScrollClock private constructor(
    private val track: EnglishSmoothTrack,
    private val starts: FloatArray,
    private val lengths: FloatArray,
    /** Each gloss's span on the media clock after levelling; null for a merged-away gloss. */
    private val windows: Array<LongRange?>,
) {
    /**
     * The gloss the English ink is in at [inkMs]: the furthest one already
     * begun. English Scroll makes this the active word, so a gloss that
     * borrows time starts and finishes as the ink does rather than inside its
     * Arabic word's own timing. Null before the verse's first ink.
     */
    fun wordAt(inkMs: Long): Int? {
        val at = track.at(inkMs)
        var found: Int? = null
        for (i in lengths.indices) if (lengths[i] > 0f && at > starts[i] + 0.002f * lengths[i]) found = i
        return found
    }

    /** Where gloss [index] runs, levelled; null if it has no time of its own. */
    fun window(index: Int): LongRange? = windows.getOrNull(index)

    /** How far through word [index]'s gloss the ink is at media [inkMs]; null when it has none. */
    fun progress(index: Int, inkMs: Long): Float? {
        val length = lengths.getOrNull(index)?.takeIf { it > 0f } ?: return null
        return ((track.at(inkMs) - starts[index]) / length).coerceIn(0f, 1f)
    }

    companion object {
        private const val TEMPO_KNOTS = 8
        /**
         * A short Arabic word can carry a long gloss: ذُو in 3:4 lasts 260 ms
         * and its English "All-Able (of)" is 13 letters, 50 a second between
         * neighbours at about 9 — a flick the smoothing cannot absorb. Only a
         * gloss faster than [LEVEL_FAST] letters a second borrows time, and
         * only enough to come down to that speed or to [LEVEL_RATIO] times its
         * neighbour's (whichever needs less), by moving the boundary it shares
         * with the slower neighbour at most [LEVEL_SHIFT_MS]. Ordinary words
         * keep their exact timing; never across a stop, and a gloss holding a
         * stop inside keeps its boundaries.
         */
        private const val LEVEL_RATIO = 1.6
        private const val LEVEL_FAST = 0.014
        private const val LEVEL_SHIFT_MS = 350.0

        private fun levelGlossSpeeds(glosses: List<MutableList<Pair<Long, Float>>>) {
            val count = glosses.size
            if (count < 2) return
            val start = DoubleArray(count) { glosses[it].first().first.toDouble() }
            val end = DoubleArray(count) { glosses[it].last().first.toDouble() }
            val chars = DoubleArray(count) { (glosses[it].last().second - glosses[it].first().second).toDouble() }
            val holds = BooleanArray(count) { g ->
                glosses[g].zipWithNext().any { (a, b) -> b.first - a.first >= ENGLISH_PAUSE_MS && b.second - a.second < 1e-4f }
            }
            val originalStart = start.copyOf()
            val originalEnd = end.copyOf()
            val movable = BooleanArray(count - 1) { k ->
                kotlin.math.abs(end[k] - start[k + 1]) <= 1.0 && !holds[k] && !holds[k + 1] &&
                    chars[k] > 0 && chars[k + 1] > 0
            }
            repeat(40) {
                var moved = false
                for (k in 0 until count - 1) {
                    if (!movable[k]) continue
                    val a = start[k]
                    val c = end[k + 1]
                    val b = end[k]
                    val left = chars[k] / (b - a).coerceAtLeast(1.0)
                    val right = chars[k + 1] / (c - b).coerceAtLeast(1.0)
                    val target = when {
                        left > LEVEL_FAST && left > LEVEL_RATIO * right -> minOf(
                            (chars[k] * c + LEVEL_RATIO * chars[k + 1] * a) / (chars[k] + LEVEL_RATIO * chars[k + 1]),
                            a + chars[k] / LEVEL_FAST,
                        ).coerceAtLeast(b)
                        right > LEVEL_FAST && right > LEVEL_RATIO * left -> maxOf(
                            (LEVEL_RATIO * chars[k] * c + chars[k + 1] * a) / (LEVEL_RATIO * chars[k] + chars[k + 1]),
                            c - chars[k + 1] / LEVEL_FAST,
                        ).coerceAtMost(b)
                        else -> continue
                    }
                    val home = originalEnd[k]
                    val next = target.coerceIn(home - LEVEL_SHIFT_MS, home + LEVEL_SHIFT_MS)
                        .coerceIn(a + (b - a) * 0.4, c - (c - b) * 0.4)
                    if (kotlin.math.abs(next - b) > 0.5) {
                        end[k] = next
                        start[k + 1] = next
                        moved = true
                    }
                }
                if (!moved) return@repeat
            }
            for (g in 0 until count) {
                val s0 = originalStart[g]
                val e0 = originalEnd[g]
                if (s0 == start[g] && e0 == end[g] || e0 <= s0) continue
                val knots = glosses[g]
                for (i in knots.indices) {
                    val (t, v) = knots[i]
                    val u = (t - s0) / (e0 - s0)
                    knots[i] = (start[g] + u * (end[g] - start[g])).toLong() to v
                }
            }
        }

        /** Audible scraps a pause leaves at a word's edge shorter than this are part of the pause. */
        private const val SLIVER_MS = 120L

        /**
         * [glossLengths] per Arabic word, in reading order (0 for a gloss merged
         * into [owner]'s); [segments] the verse's timings. Null when the verse
         * has nothing to pace.
         */
        fun of(
            glossLengths: IntArray,
            positions: IntArray,
            owner: (Int) -> Int?,
            segments: List<Segment>,
            sigmaMs: Float,
            /** The reciter's real pauses ([EnglishPauses]) on the media clock. */
            pauses: List<com.beautifulquran.domain.AudibleSilence> = emptyList(),
            /** The English reading lead: a pause releases when the voice does. */
            leadMs: Float = 0f,
            /** Ease into and out of every stop over this long ([EnglishSmoothTrack]); half of it anticipates the voice's return. */
            easeMs: Float = 0f,
            tempo: (Int) -> TajweedPacing.Curve?,
        ): EnglishScrollClock? {
            val starts = FloatArray(glossLengths.size)
            var total = 0f
            for (i in glossLengths.indices) {
                starts[i] = total
                total += glossLengths[i]
            }
            if (total <= 0f) return null
            // First pass only: a re-say's own ink is the orange repeat.
            var highWater = Int.MIN_VALUE
            val first = segments.filter { s -> (s.position > highWater).also { if (it) highWater = s.position } }
            val indexOf = HashMap<Int, Int>().apply { positions.forEachIndexed { i, p -> put(p, i) } }
            // Each timed Arabic word inks its owner's gloss; a run of words sharing
            // one gloss divides it by duration.
            val runs = ArrayList<MutableList<Pair<Int, Segment>>>()
            for (segment in first) {
                val index = indexOf[segment.position] ?: continue
                val own = if (glossLengths[index] > 0) index else owner(index) ?: continue
                if (runs.lastOrNull()?.first()?.first == own) runs.last() += own to segment
                else runs += mutableListOf(own to segment)
            }
            // Audible time only: the reciter's pauses ([pauses], and gaps between
            // timed words) are taken out of every word, so its gloss lands as the
            // voice stops and stands still until it returns. The ink leads the
            // voice, so a pause holds on [leadMs] more (at most 60% of the speech
            // after it): the next word leaves with the voice, not before it.
            val quiet = pauses.filter { it.endMs - it.startMs >= ENGLISH_PAUSE_MS }.map { it.startMs..it.endMs }
            fun audible(from: Long, to: Long): List<LongRange> {
                var pieces = listOf(from..to)
                for (q in quiet) pieces = pieces.flatMap { p ->
                    if (q.last <= p.first || q.first >= p.last) listOf(p)
                    else listOfNotNull((p.first..q.first).takeIf { q.first > p.first },
                        (q.last..p.last).takeIf { q.last < p.last })
                }
                // A word timed to start a breath before the silence would take a
                // sliver of ink and then hold — read as flowing into it. Cut
                // slivers go to the pause (unless nothing else of the word is heard).
                val heard = pieces.filter { it.last > it.first }
                if (heard.size == 1 && heard[0] == from..to) return heard
                return heard.filter { it.last - it.first >= SLIVER_MS }.ifEmpty { heard }
            }
            // Each gloss's own knots, in order; levelled below, then joined.
            val glossKnots = ArrayList<MutableList<Pair<Long, Float>>>()
            val glossOwners = ArrayList<Int>()
            var previousEnd = Long.MIN_VALUE
            runs.forEachIndexed { r, run ->
                val own = run.first().first
                val nextOnset = runs.getOrNull(r + 1)?.first()?.second?.startMs
                fun endOf(k: Int): Long {
                    val segment = run[k].second
                    val next = run.getOrNull(k + 1)?.second?.startMs ?: nextOnset ?: return segment.endMs
                    return if (next - segment.endMs >= ENGLISH_PAUSE_MS) segment.endMs else next
                }
                val pieces = run.indices.map { k ->
                    audible(run[k].second.startMs, maxOf(endOf(k), run[k].second.startMs + 1)).map { piece ->
                        val hold = englishPauseHoldMs(leadMs, easeMs)
                        if (previousEnd != Long.MIN_VALUE && piece.first - previousEnd >= ENGLISH_PAUSE_MS && hold > 0) {
                            (piece.first + minOf(hold, (piece.last - piece.first) * 6 / 10))..piece.last
                        } else piece
                    }.also { if (it.isNotEmpty()) previousEnd = it.last().last }
                }
                val voiced = pieces.sumOf { list -> list.sumOf { it.last - it.first } }.toFloat()
                if (voiced <= 0f) return@forEachIndexed
                var from = starts[own]
                val previous = glossKnots.lastOrNull()?.lastOrNull()
                if (previous != null && previous.second > from) from = previous.second
                val knots = ArrayList<Pair<Long, Float>>().also { glossKnots += it; glossOwners += own }
                run.forEachIndexed { k, (index, _) ->
                    val list = pieces[k]
                    val time = list.sumOf { it.last - it.first }.toFloat()
                    if (time <= 0f) return@forEachIndexed
                    val share = glossLengths[own] * time / voiced
                    val curve = tempo(index)
                    var spent = 0L
                    for (piece in list) {
                        for (j in 0..TEMPO_KNOTS) {
                            val at = piece.first + (piece.last - piece.first) * j / TEMPO_KNOTS
                            val u = (spent + (at - piece.first)) / time
                            knots += at to from + share * (curve?.at(u) ?: u)
                        }
                        spent += piece.last - piece.first
                    }
                    from += share
                }
            }
            levelGlossSpeeds(glossKnots)
            val windows = arrayOfNulls<LongRange>(glossLengths.size)
            glossKnots.forEachIndexed { g, list ->
                if (list.isNotEmpty()) windows[glossOwners[g]] = list.first().first..list.last().first
            }
            val knots = glossKnots.flatten()
            // Holds the blur must not cross: every stretch where the ink stands still.
            val ordered = knots.sortedBy { it.first }
            val breaks = ordered.zipWithNext().mapNotNull { (a, b) ->
                (a.first..b.first).takeIf { b.first - a.first >= ENGLISH_PAUSE_MS && b.second - a.second < 1e-4f }
            }
            val sorted = knots.sortedBy { it.first }.let { list ->
                // Monotone in both coordinates; ties in time keep the furthest.
                val out = ArrayList<Pair<Long, Float>>(list.size)
                for (k in list) {
                    val last = out.lastOrNull()
                    when {
                        last == null -> out += k
                        k.first == last.first -> out[out.lastIndex] = k.first to maxOf(last.second, k.second)
                        else -> out += k.first to maxOf(last.second, k.second)
                    }
                }
                out
            }
            val track = EnglishSmoothTrack.through(sorted, maxOf(sigmaMs, EnglishSmoothTrack.STEP_MS.toFloat()), breaks, easeMs)
                ?: return null
            return EnglishScrollClock(track, starts, FloatArray(glossLengths.size) { glossLengths[it].toFloat() }, windows)
        }
    }
}
