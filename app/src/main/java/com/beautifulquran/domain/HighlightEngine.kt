package com.beautifulquran.domain

import com.beautifulquran.data.model.Segment

/**
 * Pure mapping from a playback position to the word that should be lit.
 *
 * Karaoke behaviour: a word stays lit from its start until the next word
 * starts (gaps between words hold the previous word), nothing is lit before
 * the first word (e.g. during the basmalah on first ayahs) and nothing after
 * the last word ends.
 */
object HighlightEngine {

    /**
     * The lit word plus the context the reader needs to render repeats.
     *
     * When a reciter repeats a phrase, later segments point back at an
     * earlier [Segment.position] (the timing data preserves the backtrack).
     * [isRepeat] flags such a re-recited word so it can carry a second, orange
     * fade; [highWater] is the furthest word reached so far in this ayah, so
     * words already recited on an earlier pass keep their full ink rather than
     * dimming back to "upcoming" when the recitation jumps backward.
     */
    data class ActiveInfo(
        val position: Int,
        val startMs: Long,
        val endMs: Long,
        /**
         * When the karaoke hold ends: the next segment's [Segment.startMs], or
         * [endMs] for the last word. The letter sweep must finish by this time
         * — not merely by [endMs] — so a min-sweep clamp cannot leave the wash
         * running into the next word (Arabic-only paper-cover flicker).
         */
        val holdEndMs: Long,
        /** Position owned by the next timing segment, including a repeat
         * backtrack, or null when this is the final segment in the ayah. */
        val nextPosition: Int?,
        val isRepeat: Boolean,
        val highWater: Int,
        /** First word of the current repeat chain: while the reciter is
         * repeating, every word in [repeatStart]..[position] holds the orange
         * fade until the chain completes (playback advances past [highWater]).
         * Equals [position] when not repeating. */
        val repeatStart: Int,
    )

    /**
     * Per-ayah timing table with repeat / high-water metadata precomputed once
     * at load. The reader's 33 ms poll then does a binary search + O(1) lookup
     * with **zero allocations** on the hot path (see docs/PERFORMANCE.md).
     */
    class PreparedTimings private constructor(
        val segments: List<Segment>,
        private val infoByIndex: Array<ActiveInfo>,
    ) {
        fun activeInfo(positionMs: Long): ActiveInfo? {
            val idx = activeIndex(segments, positionMs) ?: return null
            return infoByIndex[idx]
        }

        companion object {
            fun prepare(segments: List<Segment>): PreparedTimings {
                val n = segments.size
                val maxBefore = IntArray(n)
                val repeatStart = IntArray(n)
                var runningMax = -1
                for (i in 0 until n) {
                    maxBefore[i] = runningMax
                    val pos = segments[i].position
                    val isRepeat = pos <= runningMax
                    repeatStart[i] = if (isRepeat) {
                        var startIndex = i
                        while (
                            startIndex > 0 &&
                            segments[startIndex - 1].position <= maxBefore[startIndex - 1]
                        ) {
                            startIndex--
                        }
                        var minPos = segments[startIndex].position
                        for (j in startIndex + 1..i) {
                            minPos = minOf(minPos, segments[j].position)
                        }
                        minPos
                    } else {
                        pos
                    }
                    runningMax = maxOf(runningMax, pos)
                }
                val info = Array(n) { i ->
                    val seg = segments[i]
                    val holdEndMs = segments.getOrNull(i + 1)?.startMs ?: seg.endMs
                    ActiveInfo(
                        position = seg.position,
                        startMs = seg.startMs,
                        endMs = seg.endMs,
                        holdEndMs = holdEndMs.coerceAtLeast(seg.startMs),
                        nextPosition = segments.getOrNull(i + 1)?.position,
                        isRepeat = seg.position <= maxBefore[i],
                        highWater = maxOf(maxBefore[i], seg.position),
                        repeatStart = repeatStart[i],
                    )
                }
                return PreparedTimings(segments, info)
            }
        }
    }

    fun activeWord(segments: List<Segment>, positionMs: Long): Int? =
        activeSegment(segments, positionMs)?.position

    /** The full segment behind [activeWord] — its start/end also time the
     * letter-by-letter fade of the lit word. */
    fun activeSegment(segments: List<Segment>, positionMs: Long): Segment? =
        activeIndex(segments, positionMs)?.let { segments[it] }

    /** [activeSegment] enriched with repeat / high-water context.
     *
     * Prefer [PreparedTimings.activeInfo] on the hot path — this convenience
     * rebuilds the repeat tables on every call (fine for tests / one-shots). */
    fun activeInfo(segments: List<Segment>, positionMs: Long): ActiveInfo? =
        PreparedTimings.prepare(segments).activeInfo(positionMs)

    /** Index of the last segment whose start <= [positionMs], or null when
     * nothing should be lit (before the first word or after the last ends). */
    internal fun activeIndex(segments: List<Segment>, positionMs: Long): Int? {
        if (segments.isEmpty()) return null
        if (positionMs < segments.first().startMs) return null
        if (positionMs >= segments.last().endMs) return null

        // Binary search: last segment whose start <= position.
        var lo = 0
        var hi = segments.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (segments[mid].startMs <= positionMs) lo = mid else hi = mid - 1
        }
        return lo
    }
}
