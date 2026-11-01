package com.beautifulquran.ui.reader

import kotlin.math.roundToLong

/** A first wash ends before a repeat, the next band, or the recitation's end. */
internal data class EnglishWashWindow(
    val startMs: Long,
    val endMs: Long,
    val spoken: List<LongRange>? = null,
    val reading: EnglishReadingWindow? = null,
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
        if (reading != null) {
            // Inverse endpoints are rounded diagnostics. Only an additional
            // explicit voice gate may clip the shared continuous projection.
            if (voiceStartMs <= startMs) return reading.progress(positionMs)
            if (positionMs < start) return 0f
            if (start >= endMs) return 1f
            // Rounded inverse endpoints are metadata, not a new phase origin.
            val from = if (voiceStartMs > startMs) reading.progress(start) else 0f
            if (from >= 1f) return 1f
            return ((reading.progress(positionMs) - from) / (1f - from)).coerceIn(0f, 1f)
        }
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
        if (reading != null) {
            val from = if (voiceStartMs > startMs) reading.progress(start) else 0f
            return reading.mediaAt(from + (1f - from) * fraction, afterGap).coerceIn(start, endMs)
        }
        if (spoken == null) return start + ((endMs - start) * fraction).toLong()
        if (intervals.isEmpty()) return if (fraction == 0f) start else endMs
        val consumed = spokenUntil(start)
        val rawTarget = consumed + (accumulated.last() - consumed) * fraction.toDouble()
        // The media clock is integer milliseconds. Quantize before choosing a
        // pause's side: Float round trips must not select across a long silence.
        val target = rawTarget.roundToLong().toDouble()
        val index = intervals.indices.firstOrNull {
            if (afterGap) target < accumulated[it + 1] else target <= accumulated[it + 1]
        } ?: intervals.lastIndex
        return maxOf(start, intervals[index].first + (target - accumulated[index]).toLong())
    }
}
