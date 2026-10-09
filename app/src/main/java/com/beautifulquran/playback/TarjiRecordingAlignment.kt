package com.beautifulquran.playback

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** Matches decoder priming to the tap; ambiguous or steady audio never establishes an offset. */
internal class TarjiRecordingAlignment {
    var offsetMs = Double.NaN
        private set
    var correlation = 0.0
        private set
    private val scores = DoubleArray(13)

    fun match(live: FloatArray, count: Int, firstMediaMs: Double, liveHopMs: Double,
              decoded: FloatArray, decodedHopMs: Double): Boolean {
        if (count * liveHopMs < 1500.0 || !firstMediaMs.isFinite()) return false
        for (offset in -6..6) {
            var x = 0.0; var y = 0.0; var xx = 0.0; var yy = 0.0; var xy = 0.0
            var n = 0
            for (i in 0 until count) {
                val p = (firstMediaMs + i * liveHopMs + offset * decodedHopMs) / decodedHopMs - 0.5
                val a = p.toInt()
                if (p < 0.0 || a + 1 >= decoded.size) continue
                val value = decoded[a] + (p - a) * (decoded[a + 1] - decoded[a])
                val vx = ln(live[i].coerceAtLeast(1e-5f).toDouble())
                val vy = ln(value.coerceAtLeast(1e-5))
                x += vx; y += vy; xx += vx * vx; yy += vy * vy; xy += vx * vy
                n++
            }
            val vx = if (n > 0) xx - x * x / n else 0.0
            val vy = if (n > 0) yy - y * y / n else 0.0
            scores[offset + 6] = if (n >= 64 && vx > 1e-5 && vy > 1e-5)
                (xy - x * y / n) / sqrt(vx * vy) else 0.0
        }
        val best = scores.indices.maxBy { scores[it] }
        correlation = scores[best]
        var competitor = 0.0
        for (i in scores.indices) if (abs(i - best) > 1) competitor = maxOf(competitor, scores[i])
        if (correlation < 0.9 || correlation - competitor < 0.03 || best == 0 || best == 12) return false
        offsetMs = (best - 6) * decodedHopMs
        return true
    }
}
