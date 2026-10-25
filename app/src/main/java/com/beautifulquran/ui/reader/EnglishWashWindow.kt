package com.beautifulquran.ui.reader

/** A first wash ends before a repeat, the next band, or the recitation's end. */
internal data class EnglishWashWindow(
    val startMs: Long,
    val endMs: Long,
    val spoken: List<LongRange>? = null,
) {
    private val intervals = spoken.orEmpty()
    private val accumulated = LongArray(intervals.size + 1).also { sums ->
        intervals.forEachIndexed { index, range -> sums[index + 1] = sums[index] + range.last - range.first }
    }

    private fun spokenUntil(positionMs: Long): Long {
        var low = 0
        var high = intervals.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (intervals[mid].first <= positionMs) low = mid + 1 else high = mid
        }
        val index = low - 1
        if (index < 0) return 0
        return accumulated[index] + (positionMs - intervals[index].first).coerceIn(0, intervals[index].last - intervals[index].first)
    }

    fun progress(positionMs: Long, voiceStartMs: Long = startMs): Float {
        val start = maxOf(startMs, voiceStartMs).coerceAtMost(endMs)
        if (positionMs < start) return 0f
        if (positionMs >= endMs) return 1f
        if (spoken == null) return (positionMs - start).toFloat() / (endMs - start)
        val from = spokenUntil(start)
        val duration = spokenUntil(endMs) - from
        return if (duration > 0) (spokenUntil(positionMs) - from).toFloat() / duration else 0f
    }

    /** Inverse of the same voiced clock, for printed-word exposure diagnostics. */
    fun mediaAt(progress: Float, afterGap: Boolean = false, voiceStartMs: Long = startMs): Long {
        val fraction = progress.coerceIn(0f, 1f)
        val start = maxOf(startMs, voiceStartMs).coerceAtMost(endMs)
        if (spoken == null) return start + ((endMs - start) * fraction).toLong()
        if (intervals.isEmpty()) return if (fraction == 0f) start else endMs
        val consumed = spokenUntil(start)
        val target = consumed + (accumulated.last() - consumed) * fraction
        val index = intervals.indices.firstOrNull {
            if (afterGap) target < accumulated[it + 1] else target <= accumulated[it + 1]
        } ?: intervals.lastIndex
        return maxOf(start, intervals[index].first + (target - accumulated[index]).toLong())
    }
}
