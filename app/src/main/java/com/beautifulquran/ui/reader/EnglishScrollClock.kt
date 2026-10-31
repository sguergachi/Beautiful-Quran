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
            /** The reciter's real pauses ([EnglishPauses]) on the media clock. */
            pauses: List<com.beautifulquran.domain.AudibleSilence> = emptyList(),
            /** The English reading lead: a pause releases when the voice does. */
            leadMs: Float = 0f,
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
                return pieces.filter { it.last > it.first }
            }
            val knots = ArrayList<Pair<Long, Float>>()
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
                        if (previousEnd != Long.MIN_VALUE && piece.first - previousEnd >= ENGLISH_PAUSE_MS && leadMs > 0f) {
                            (piece.first + minOf(leadMs.toLong(), (piece.last - piece.first) * 6 / 10))..piece.last
                        } else piece
                    }.also { if (it.isNotEmpty()) previousEnd = it.last().last }
                }
                val voiced = pieces.sumOf { list -> list.sumOf { it.last - it.first } }.toFloat()
                if (voiced <= 0f) return@forEachIndexed
                var from = starts[own]
                if (knots.isNotEmpty() && knots.last().second > from) from = knots.last().second
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
            val track = EnglishSmoothTrack.through(sorted, maxOf(sigmaMs, EnglishSmoothTrack.STEP_MS.toFloat()), breaks)
                ?: return null
            return EnglishScrollClock(track, starts, FloatArray(glossLengths.size) { glossLengths[it].toFloat() })
        }
    }
}
