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
 *
 * A reciter's pause is a break the blur must not cross: smoothed across it,
 * the ink eases on into the silence and is already moving before the voice
 * returns, so the stop is never seen. Within [breaks] the kernel narrows to a
 * third of the distance to the pause, so the word lands, the ink stands still
 * for the whole silence, and the next word leaves as the voice does.
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
        fun of(
            fromMs: Long,
            toMs: Long,
            sigmaMs: Float,
            breaks: List<LongRange> = emptyList(),
            track: (Long) -> Float,
        ): EnglishSmoothTrack? {
            if (sigmaMs < STEP_MS || toMs <= fromMs) return null
            val radius = (3 * sigmaMs / STEP_MS).roundToInt()
            val origin = fromMs - radius * STEP_MS
            val count = ((toMs - fromMs) / STEP_MS + 1 + 2 * radius).toInt()
            val padded = count + 2 * radius
            val raw = FloatArray(padded) { track(origin + (it - radius) * STEP_MS) }
            // One kernel per narrowed width, in whole steps: index r has radius r.
            val kernels = arrayOfNulls<FloatArray>(radius + 1)
            fun kernel(r: Int): FloatArray = kernels[r] ?: FloatArray(2 * r + 1) { k ->
                val x = (k - r) * 3f / maxOf(r, 1)
                exp(-0.5f * x * x)
            }.also { k -> val sum = k.sum(); for (j in k.indices) k[j] /= sum; kernels[r] = k }
            // The verse's own start and end are edges too: the first word leaves
            // with the voice and the last lands on time, not blurred past them.
            val edges = (breaks.flatMap { listOf(it.first, it.last) } + fromMs + toMs).sorted().toLongArray()
            fun room(ms: Long): Long {
                if (edges.isEmpty()) return Long.MAX_VALUE
                var low = 0
                var high = edges.size
                while (low < high) {
                    val mid = (low + high) ushr 1
                    if (edges[mid] < ms) low = mid + 1 else high = mid
                }
                val after = edges.getOrNull(low)?.let { it - ms } ?: Long.MAX_VALUE
                val before = edges.getOrNull(low - 1)?.let { ms - it } ?: Long.MAX_VALUE
                return minOf(after, before)
            }
            val values = FloatArray(count) { i ->
                val ms = origin + i * STEP_MS
                val inBreak = breaks.any { ms >= it.first && ms <= it.last }
                val r = if (inBreak) 0 else minOf(radius.toLong(), room(ms) / STEP_MS).toInt()
                if (r == 0) return@FloatArray raw[i + radius]
                val k = kernel(r)
                var acc = 0f
                for (j in k.indices) acc += raw[i + radius - r + j] * k[j]
                acc
            }
            for (i in 1 until values.size) values[i] = maxOf(values[i], values[i - 1])
            return EnglishSmoothTrack(origin, values)
        }

        /** A piecewise-linear track through ascending (media ms, position) knots, then smoothed. */
        fun through(
            knots: List<Pair<Long, Float>>,
            sigmaMs: Float,
            breaks: List<LongRange> = emptyList(),
        ): EnglishSmoothTrack? {
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
            return of(times.first(), times.last(), sigmaMs, breaks, ::linear)
        }
    }
}
