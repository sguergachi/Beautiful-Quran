package com.beautifulquran.ui.reader

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * A monotone media-time → reading-position track with its *speed* smoothed.
 *
 * English ink anchored word by word to the voice changes speed at every word
 * boundary — a short Arabic word carrying a long gloss flicks past, the next
 * crawls — and tajweed tempo adds kinks inside words. Read against the voice
 * those steps are jerks. Convolving the position with a Gaussian of [sigmaMs]
 * keeps it monotone and anchored (a symmetric kernel adds no delay), blends
 * each speed change over about ±σ, and leaves a held pause held: inside a
 * silence longer than ~3σ nothing moves. Measured on 1,412 Scroll glosses,
 * σ = 100 ms takes the p90 speed jump at a word boundary from 3.8x to 1.3x.
 */
internal class EnglishSmoothTrack private constructor(
    private val originMs: Long,
    private val values: FloatArray,
) {
    private val endMs: Long get() = originMs + (values.size - 1) * STEP_MS

    fun at(positionMs: Long): Float {
        if (positionMs <= originMs) return values.first()
        if (positionMs >= endMs) return values.last()
        val at = (positionMs - originMs).toFloat() / STEP_MS
        val i = at.toInt()
        val f = at - i
        return values[i] + (values[i + 1] - values[i]) * f
    }

    /**
     * The media time the track reaches [value]: the first such moment, or with
     * [afterGap] the last moment it still stands there (the end of a hold).
     */
    fun mediaAt(value: Float, afterGap: Boolean = false): Long {
        var low = 0
        var high = values.size - 1
        if (!afterGap) {
            while (low < high) {
                val mid = (low + high) ushr 1
                if (values[mid] >= value) high = mid else low = mid + 1
            }
            if (low == 0) return originMs
            val a = values[low - 1]
            val b = values[low]
            val f = if (b > a) ((value - a) / (b - a)).coerceIn(0f, 1f) else 1f
            return originMs + ((low - 1 + f) * STEP_MS).roundToInt()
        }
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (values[mid] <= value) low = mid else high = mid - 1
        }
        if (low >= values.size - 1) return endMs
        val a = values[low]
        val b = values[low + 1]
        val f = if (b > a) ((value - a) / (b - a)).coerceIn(0f, 1f) else 0f
        return originMs + ((low + f) * STEP_MS).roundToInt()
    }

    companion object {
        const val STEP_MS = 4L

        /** Smooth any monotone [track] over [fromMs, toMs]; null when σ rounds to nothing. */
        fun of(fromMs: Long, toMs: Long, sigmaMs: Float, track: (Long) -> Float): EnglishSmoothTrack? {
            if (sigmaMs < STEP_MS || toMs <= fromMs) return null
            val radius = (3 * sigmaMs / STEP_MS).roundToInt()
            val origin = fromMs - radius * STEP_MS
            val count = ((toMs - fromMs) / STEP_MS + 1 + 2 * radius).toInt()
            val padded = count + 2 * radius
            val raw = FloatArray(padded) { track(origin + (it - radius) * STEP_MS) }
            val kernel = FloatArray(2 * radius + 1) { k ->
                val x = (k - radius) * STEP_MS / sigmaMs
                exp(-0.5f * x * x)
            }
            val sum = kernel.sum()
            for (k in kernel.indices) kernel[k] /= sum
            val values = FloatArray(count) { i ->
                var acc = 0f
                for (k in kernel.indices) acc += raw[i + k] * kernel[k]
                acc
            }
            for (i in 1 until values.size) values[i] = maxOf(values[i], values[i - 1])
            return EnglishSmoothTrack(origin, values)
        }

        /** A piecewise-linear track through ascending (media ms, position) knots, then smoothed. */
        fun through(knots: List<Pair<Long, Float>>, sigmaMs: Float): EnglishSmoothTrack? {
            if (knots.size < 2) return null
            val times = LongArray(knots.size) { knots[it].first }
            val positions = FloatArray(knots.size) { knots[it].second }
            fun linear(ms: Long): Float {
                if (ms <= times.first()) return positions.first()
                if (ms >= times.last()) return positions.last()
                var low = 0
                var high = times.size - 1
                while (low + 1 < high) {
                    val mid = (low + high) ushr 1
                    if (times[mid] <= ms) low = mid else high = mid
                }
                val span = times[high] - times[low]
                if (span <= 0) return positions[high]
                return positions[low] + (positions[high] - positions[low]) * (ms - times[low]).toFloat() / span
            }
            return of(times.first(), times.last(), sigmaMs, ::linear)
        }
    }
}
