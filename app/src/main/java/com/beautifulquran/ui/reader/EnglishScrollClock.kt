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
 * pause ([ENGLISH_PAUSE_MS]) finishes the gloss at the voiced end and holds.
 * The whole track is then speed-smoothed ([EnglishSmoothTrack]) so no word
 * boundary is a jump. Spaces between glosses take no time.
 */
internal class EnglishScrollClock private constructor(
    private val track: EnglishSmoothTrack,
    private val starts: FloatArray,
    private val lengths: FloatArray,
) {
    /** How far through word [index]'s gloss the ink is at media [inkMs]; null when it has none. */
    fun progress(index: Int, inkMs: Long): Float? {
        val length = lengths.getOrNull(index)?.takeIf { it > 0f } ?: return null
        return ((track.at(inkMs) - starts[index]) / length).coerceIn(0f, 1f)
    }

    companion object {
        private const val TEMPO_KNOTS = 8

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
            val knots = ArrayList<Pair<Long, Float>>()
            runs.forEachIndexed { r, run ->
                val own = run.first().first
                val nextOnset = runs.getOrNull(r + 1)?.first()?.second?.startMs
                fun endOf(k: Int): Long {
                    val segment = run[k].second
                    val next = run.getOrNull(k + 1)?.second?.startMs ?: nextOnset ?: return segment.endMs
                    return if (next - segment.endMs >= ENGLISH_PAUSE_MS) segment.endMs else next
                }
                val spans = run.indices.map { k -> run[k].second.startMs to maxOf(endOf(k), run[k].second.startMs + 1) }
                val voiced = spans.sumOf { it.second - it.first }.toFloat()
                var from = starts[own]
                if (knots.isNotEmpty() && knots.last().second > from) from = knots.last().second
                run.forEachIndexed { k, (index, _) ->
                    val (a, b) = spans[k]
                    val share = glossLengths[own] * (b - a) / voiced
                    val curve = tempo(index)
                    for (j in 0..TEMPO_KNOTS) {
                        val u = j.toFloat() / TEMPO_KNOTS
                        val at = a + ((b - a) * u).toLong()
                        knots += at to from + share * (curve?.at(u) ?: u)
                    }
                    from += share
                }
                // Hold through a pause, to the next onset.
                if (nextOnset != null && nextOnset > spans.last().second) knots += nextOnset to from
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
            val track = EnglishSmoothTrack.through(sorted, maxOf(sigmaMs, EnglishSmoothTrack.STEP_MS.toFloat()))
                ?: return null
            return EnglishScrollClock(track, starts, FloatArray(glossLengths.size) { glossLengths[it].toFloat() })
        }
    }
}
