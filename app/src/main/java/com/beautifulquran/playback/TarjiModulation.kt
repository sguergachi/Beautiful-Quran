package com.beautifulquran.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Evidence only: visual phase always comes from the PCM ear track. */
internal class TarjiEvidence {
    var open = false
    var keep = false
    var rateHz = 0f
    var depth = 0f
    var score = 0f
    var levelBalance = 1f

    fun clear() {
        open = false
        keep = false
        rateHz = 0f
        depth = 0f
        score = 0f
        levelBalance = 1f
    }
}

/** Common log-amplitude/cents features. One-hop AM look-ahead is paid at the tap. */
internal class TarjiFeatureWindow {
    val amplitude = FloatArray(SIZE)
    val pitch = FloatArray(SIZE)
    val amplitudeWeight = FloatArray(SIZE)
    val pitchWeight = FloatArray(SIZE)
    var count = 0
        private set
    var hold = -1
        private set
    var hopMs = 20.0
        private set
    private var reference = 0f
    private var olderRms = 0f
    private var previousRms = 0f
    private var previousPitch = Float.NaN
    private var olderPitch = Float.NaN
    private var previousQuality = 0f
    private var previousVoiced = false
    private var pending = false

    fun clear() {
        count = 0
        hold = -1
        reference = 0f
        pending = false
    }

    fun push(frame: TarjiFrame): Boolean {
        if (frame.hop < 0 || frame.holdMs <= 0f) { clear(); return false }
        if (hold != frame.holdStartHop) { clear(); hold = frame.holdStartHop }
        hopMs = frame.hopMs
        if (pending) {
            val slot = count % SIZE
            amplitude[slot] = ln(max(1e-5f, (olderRms + 2f * previousRms + frame.hopRms) * 0.25f))
            amplitudeWeight[slot] = if (previousVoiced) 1f else 0f
            if (previousPitch.isFinite()) {
                if (reference == 0f) reference = previousPitch
                var sum = 2.0 * ln(previousPitch / reference)
                var weight = 2
                if (olderPitch.isFinite()) { sum += ln(olderPitch / reference); weight++ }
                if (frame.f0Valid) { sum += ln(frame.f0Hz / reference); weight++ }
                pitch[slot] = (1200.0 * sum / (weight * ln(2.0))).toFloat()
                pitchWeight[slot] = previousQuality
            } else {
                pitch[slot] = 0f
                pitchWeight[slot] = 0f
            }
            count++
        }
        olderRms = if (pending) previousRms else frame.hopRms
        olderPitch = if (pending) previousPitch else Float.NaN
        previousRms = frame.hopRms
        previousPitch = if (frame.f0Valid) frame.f0Hz else Float.NaN
        previousQuality = frame.pitchQuality
        previousVoiced = frame.voiced
        val wasPending = pending
        pending = true
        return wasPending
    }

    companion object { const val SIZE = 96 }
}

/** Shared bounded workspace for the two causal experimental evidence methods. */
internal class TarjiModulation {
    val features = TarjiFeatureWindow()
    val am = TarjiEvidence()
    val fm = TarjiEvidence()
    private val values = FloatArray(TarjiFeatureWindow.SIZE)
    private val weights = FloatArray(TarjiFeatureWindow.SIZE)
    private val residual = FloatArray(TarjiFeatureWindow.SIZE)
    private val workWeights = FloatArray(TarjiFeatureWindow.SIZE)
    private val sorted = FloatArray(TarjiFeatureWindow.SIZE)
    private val extrema = TarjiExtrema(TarjiFeatureWindow.SIZE)
    private val full = TarjiFit()
    private val left = TarjiFit()
    private val right = TarjiFit()
    private val best = TarjiFit()
    private val amPersistence = TarjiSpectralPersistence()
    private val fmPersistence = TarjiSpectralPersistence()

    fun clear() {
        features.clear()
        am.clear()
        fm.clear()
        amPersistence.clear()
        fmPersistence.clear()
    }

    fun next(frame: TarjiFrame, mode: TarjiDetectorMode, knobs: Tarji) {
        val holdBefore = features.hold
        if (holdBefore != frame.holdStartHop) { amPersistence.clear(); fmPersistence.clear() }
        if (!features.push(frame)) { am.clear(); fm.clear(); return }
        if (mode == TarjiDetectorMode.Spectrum && features.count % 2 != 0) return
        scan(false, mode, knobs, am, amPersistence)
        scan(true, mode, knobs, fm, fmPersistence)
    }

    private fun scan(pitch: Boolean, mode: TarjiDetectorMode, knobs: Tarji,
                     out: TarjiEvidence, persistence: TarjiSpectralPersistence) {
        out.clear()
        val n = min(features.count, TarjiFeatureWindow.SIZE)
        val source = if (pitch) features.pitch else features.amplitude
        val quality = if (pitch) features.pitchWeight else features.amplitudeWeight
        for (i in 0 until n) {
            val slot = (features.count - n + i) % TarjiFeatureWindow.SIZE
            values[i] = source[slot]
            weights[i] = quality[slot]
        }
        val floor = if (pitch) 10f else atanh(knobs.minTremoloDepth.coerceIn(0.035f, 0.95f))
        val low = knobs.minTremoloHz.coerceIn(1.5f, 10f)
        val high = knobs.maxTremoloHz.coerceIn(low, 10f)
        if (mode == TarjiDetectorMode.Cycles) {
            if (!tarjiDetrend(values, weights, n, residual, workWeights, sorted)) return
            extrema.find(residual, weights, n, floor)
            extrema.evidence(residual, weights, n, features.hopMs, low, high, floor,
                (0.24f - 0.1f * knobs.minPeriodicity).coerceIn(0.1f, 0.2f), out)
            if (!pitch && out.keep) out.levelBalance = tarjiLevelBalance(values, n)
        } else {
            spectrum(n, low, high, floor, knobs.minPeriodicity, out, persistence)
            if (!pitch && out.keep) out.levelBalance = tarjiLevelBalance(values, n)
        }
        if (!pitch) out.depth = tanh(out.depth)
    }

    private fun spectrum(n: Int, low: Float, high: Float, floor: Float, regularity: Float,
                         out: TarjiEvidence, persistence: TarjiSpectralPersistence) {
        var winner = 0.0
        var winnerStep = 0f
        for (branch in 0..2) {
            val length = min(n, 32 * (branch + 1))
            if (length < 24) continue
            val from = n - length
            val bandLow = max(low, if (branch == 0) 4.5f else if (branch == 1) 2.25f else 1.5f)
            val bandHigh = min(high, if (branch == 0) 10f else if (branch == 1) 4.5f else 2.25f)
            if (bandLow > bandHigh) continue
            val step = (20.0 / (32 * (branch + 1))).toFloat()
            best.clear()
            var dominant = 0.0
            fun trial(f: Float) {
                full.fit(values, weights, from, n, from, n, f, features.hopMs)
                dominant = max(dominant, full.score)
                if (f !in bandLow..bandHigh || (length - 1) * features.hopMs * f < 2250.0) return
                if (full.score > best.score) best.set(full)
            }
            var frequency = max(0.75f, low - 0.5f)
            while (frequency <= 14.001f) { trial(frequency); frequency += step }
            trial(bandLow)
            trial(bandHigh)
            if (best.score <= 0.0) continue
            // Refine with real fits; guard bins never authorize an out-of-band rate.
            val centre = best.rateHz
            trial((centre - step * 0.5f).coerceIn(bandLow, bandHigh))
            trial((centre + step * 0.5f).coerceIn(bandLow, bandHigh))
            if (best.score + 1e-4 < dominant || best.depth < 0.7 * floor) continue
            val middle = from + length / 2
            left.fit(values, weights, from, middle, from, middle, best.rateHz, features.hopMs)
            right.fit(values, weights, middle, n, from, n, best.rateHz, features.hopMs)
            val phase = abs(atan2(sin(left.phase - right.phase), cos(left.phase - right.phase)))
            val ratio = if (right.depth > 0.0) left.depth / right.depth else 0.0
            val coherent = left.score >= 0.3 && right.score >= 0.3 && ratio in 0.5..2.0
            if (!coherent || phase > PI / 2 || best.score <= winner) continue
            winner = best.score
            winnerStep = step
            out.rateHz = best.rateHz
            out.depth = best.depth.toFloat()
            out.score = best.score.toFloat()
            out.keep = best.score >= 0.45 && best.coverage >= 0.85
            out.open = phase <= PI / 3 && best.score >= max(0.6, regularity.toDouble()) &&
                best.depth >= floor && out.keep
        }
        out.open = persistence.accept(out.open, out.rateHz, winnerStep)
    }
}

/** Schmitt turning points: missing observations never form or extend a cycle. */
internal class TarjiExtrema(size: Int) {
    val time = FloatArray(size)
    val value = FloatArray(size)
    val segment = IntArray(size)
    var count = 0
        private set

    fun find(series: FloatArray, weight: FloatArray, n: Int, retreat: Float) {
        count = 0
        var direction = 0
        var high = -Float.MAX_VALUE
        var low = Float.MAX_VALUE
        var highAt = 0
        var lowAt = 0
        var missing = 0
        var section = 0
        fun add(at: Int, v: Float) {
            if (at <= 0 || at >= n - 1 || count == time.size) return
            var shift = 0f
            if (weight[at - 1] > 0f && weight[at + 1] > 0f) {
                val curvature = series[at - 1] - 2f * v + series[at + 1]
                if (abs(curvature) > 1e-8f) shift =
                    (0.5f * (series[at - 1] - series[at + 1]) / curvature).coerceIn(-0.5f, 0.5f)
            }
            time[count] = at + shift
            segment[count] = section
            value[count++] = v
        }
        for (i in 0 until n) {
            if (weight[i] <= 0f) {
                if (++missing == 3) {
                    section++
                    direction = 0
                    high = -Float.MAX_VALUE
                    low = Float.MAX_VALUE
                }
                continue
            }
            missing = 0
            val v = series[i]
            if (direction >= 0) {
                if (v >= high) { high = v; highAt = i }
                if (high - v >= retreat) {
                    add(highAt, high)
                    direction = -1
                    low = v
                    lowAt = i
                }
            }
            if (direction <= 0) {
                if (v <= low) { low = v; lowAt = i }
                if (v - low >= retreat) {
                    add(lowAt, low)
                    direction = 1
                    high = v
                    highAt = i
                }
            }
        }
    }

    fun evidence(series: FloatArray, weights: FloatArray, n: Int, hopMs: Double,
                 lowHz: Float, highHz: Float, floor: Float, jitterLimit: Float, out: TarjiEvidence) {
        if (count < 4) return
        val a = count - 4
        if (segment[a] != segment[count - 1] || (n >= 3 && weights[n - 1] <= 0f &&
                weights[n - 2] <= 0f && weights[n - 3] <= 0f)) return
        val p0 = time[a + 2] - time[a]
        val p1 = time[a + 3] - time[a + 1]
        val period = (p0 + p1) * 0.5f
        if (period <= 0f) return
        val hz = tarjiCycleRate(period, hopMs, lowHz, highHz) ?: return
        val jitter = abs(p0 - p1) / period
        if (jitter > jitterLimit) return
        var valid = 0
        val first = ceil(time[a].toDouble()).toInt()
        val last = time[a + 3].toInt()
        for (i in first..last) if (weights[i] > 0f) valid++
        if (valid < 0.85f * (last - first + 1)) return
        var smallest = Float.MAX_VALUE
        var largest = 0f
        var sum = 0f
        for (i in a until a + 3) {
            if ((time[i + 1] - time[i]) / period !in 0.2f..0.8f) return
            val excursion = abs(value[i + 1] - value[i]) * 0.5f
            smallest = min(smallest, excursion)
            largest = max(largest, excursion)
            sum += excursion
        }
        val depth = sum - smallest - largest
        if (depth < floor || smallest < 0.35f * depth) return
        val age = n - time[a + 3] // includes the common one-hop feature look-ahead
        out.rateHz = hz
        out.depth = depth
        out.score = (1f - jitter).coerceIn(0f, 1f)
        out.open = age <= 0.85f * period
        out.keep = age <= 1.1f * period && abs(value[a + 3] - value[a + 2]) * 0.5f >= 0.7f * floor
    }
}

/** Linear residual with one robust reweight; buffers belong to the caller. */
internal fun tarjiDetrend(values: FloatArray, weights: FloatArray, n: Int, out: FloatArray,
                          workWeights: FloatArray, sorted: FloatArray): Boolean {
    var usable = 0
    for (i in 0 until n) { workWeights[i] = weights[i]; if (weights[i] > 0f) usable++ }
    if (usable < 12) return false
    repeat(2) { pass ->
        var sw = 0.0; var st = 0.0; var stt = 0.0; var sy = 0.0; var sty = 0.0
        for (i in 0 until n) {
            val w = workWeights[i].toDouble()
            sw += w; st += w * i; stt += w * i * i; sy += w * values[i]; sty += w * i * values[i]
        }
        val variance = stt - st * st / sw
        if (variance < 1e-6) return false
        val slope = (sty - st * sy / sw) / variance
        val intercept = (sy - slope * st) / sw
        var kept = 0
        for (i in 0 until n) {
            out[i] = (values[i] - intercept - slope * i).toFloat()
            if (weights[i] > 0f) sorted[kept++] = abs(out[i])
        }
        if (pass == 0) {
            sorted.sort(0, kept)
            val limit = max(1e-6f, 2.5f * 1.4826f * sorted[kept / 2])
            for (i in 0 until n) workWeights[i] = weights[i] * min(1f, limit / max(1e-6f, abs(out[i])))
        }
    }
    return true
}

/** Weighted trend-plus-sinusoid fit; projection removes the trend from both bases. */
private class TarjiFit {
    var score = 0.0
    var depth = 0.0
    var phase = 0.0
    var rateHz = 0f
    var coverage = 0.0
    fun clear() { score = 0.0; depth = 0.0; phase = 0.0; rateHz = 0f; coverage = 0.0 }
    fun set(other: TarjiFit) {
        score = other.score; depth = other.depth; phase = other.phase
        rateHz = other.rateHz; coverage = other.coverage
    }

    fun fit(y: FloatArray, quality: FloatArray, from: Int, until: Int, origin: Int, end: Int,
            frequency: Float, hopMs: Double) {
        clear()
        if (until - from < 8) return
        var sw = 0.0; var st = 0.0; var tt = 0.0; var sy = 0.0; var ty = 0.0; var yy = 0.0
        var sc = 0.0; var ss = 0.0; var tc = 0.0; var ts = 0.0
        var cc = 0.0; var cs = 0.0; var si = 0.0; var cy = 0.0; var syi = 0.0
        var valid = 0
        val angle = 2.0 * PI * frequency * hopMs / 1000.0
        val dc = cos(angle); val ds = sin(angle)
        var c = cos(angle * (from - origin)); var s = sin(angle * (from - origin))
        val hann = TarjiHann.windows[until - from]
        for (i in from until until) {
            val w = quality[i] * hann[i - from]
            if (quality[i] > 0f) valid++
            val t = (i - origin).toDouble()
            val v = y[i].toDouble()
            sw += w; st += w * t; tt += w * t * t; sy += w * v; ty += w * t * v; yy += w * v * v
            sc += w * c; ss += w * s; tc += w * t * c; ts += w * t * s
            cc += w * c * c; cs += w * c * s; si += w * s * s; cy += w * c * v; syi += w * s * v
            val nextC = c * dc - s * ds; s = s * dc + c * ds; c = nextC
        }
        coverage = valid.toDouble() / (until - from)
        if (coverage < 0.85 || sw < 3.0) return
        val variance = tt - st * st / sw
        if (variance < 1e-6) return
        val yt = ty - sy * st / sw
        val ct = tc - sc * st / sw
        val sit = ts - ss * st / sw
        val energy = yy - sy * sy / sw - yt * yt / variance
        val c2 = cc - sc * sc / sw - ct * ct / variance
        val s2 = si - ss * ss / sw - sit * sit / variance
        val cross = cs - sc * ss / sw - ct * sit / variance
        val yc = cy - sy * sc / sw - yt * ct / variance
        val ys = syi - sy * ss / sw - yt * sit / variance
        val determinant = c2 * s2 - cross * cross
        if (energy <= 1e-10 || c2 <= 1e-8 || s2 <= 1e-8 || determinant <= 1e-5 * c2 * s2) return
        val a = (yc * s2 - ys * cross) / determinant
        val b = (ys * c2 - yc * cross) / determinant
        score = ((a * yc + b * ys) / energy).coerceIn(0.0, 1.0)
        depth = sqrt(a * a + b * b)
        phase = atan2(b, a)
        rateHz = frequency
    }
}

private object TarjiHann {
    val windows = Array(TarjiFeatureWindow.SIZE + 1) { n ->
        DoubleArray(n) { if (n < 2) 1.0 else 0.5 - 0.5 * cos(2.0 * PI * it / (n - 1)) }
    }
}

private class TarjiSpectralPersistence {
    private var rate = 0f
    private var count = 0
    fun clear() { rate = 0f; count = 0 }
    fun accept(open: Boolean, next: Float, step: Float): Boolean {
        count = if (open && abs(next - rate) <= max(0.21f, step)) count + 1 else if (open) 1 else 0
        rate = next
        return count >= 3
    }
}

internal fun tarjiLevelBalance(logAmplitude: FloatArray, n: Int): Float {
    if (n < 2) return 0f
    var first = 0.0; var last = 0.0
    val half = n / 2
    for (i in 0 until half) first += logAmplitude[i]
    for (i in n - half until n) last += logAmplitude[i]
    return exp(-abs(first - last) / half).toFloat()
}

/** Quadratic turning-point interpolation has small bias at five samples per cycle. */
internal fun tarjiCycleRate(period: Float, hopMs: Double, low: Float, high: Float): Float? {
    val rate = (1000.0 / (period * hopMs)).toFloat()
    return if (rate in low * 0.99f..high * 1.01f) rate.coerceIn(low, high) else null
}
