package com.beautifulquran.tarjilab

import com.beautifulquran.playback.Tarji
import com.beautifulquran.playback.TarjiLabCapture
import com.beautifulquran.ui.reader.InkEngine
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/**
 * The Tarjīʿ Lab's detector knobs — the same eight the Ink Lab's Tarjīʿ
 * section exposes, mirrored 1:1 onto [Tarji] for offline replay.
 */
@Serializable
data class TarjiLabKnobs(
    val maxTremoloHz: Float = Tarji.MAX_TREMOLO_HZ,
    val minTremoloHz: Float = Tarji.MIN_TREMOLO_HZ,
    val holdMinMs: Float = Tarji.HOLD_MIN_MS.toFloat(),
    val minTremoloDepth: Float = Tarji.MIN_TREMOLO_DEPTH,
    val minPeriodicity: Float = Tarji.MIN_PERIODICITY,
    val maxPitchDrift: Float = Tarji.MAX_PITCH_DRIFT,
    val attackMs: Float = Tarji.ATTACK_MS,
    val releaseMs: Float = Tarji.RELEASE_MS,
) {
    /** Apply onto a fresh detector — the analysis entry point. */
    fun applyTo(detector: Tarji) {
        detector.maxTremoloHz = maxTremoloHz
        detector.minTremoloHz = minTremoloHz
        detector.holdMinMs = holdMinMs
        detector.minTremoloDepth = minTremoloDepth
        detector.minPeriodicity = minPeriodicity
        detector.maxPitchDrift = maxPitchDrift
        detector.attackMs = attackMs
        detector.releaseMs = releaseMs
    }

    companion object {
        /** The lab's knobs are the Ink Lab's: one source of truth. */
        fun fromTuning(t: InkEngine.Tuning): TarjiLabKnobs = TarjiLabKnobs(
            maxTremoloHz = t.glintResonanceMaxHz,
            minTremoloHz = t.tarjiMinHz,
            holdMinMs = t.tarjiHoldMinMs,
            minTremoloDepth = t.tarjiMinDepth,
            minPeriodicity = t.tarjiMinPeriodicity,
            maxPitchDrift = t.tarjiPitchDrift,
            attackMs = t.tarjiAttackMs,
            releaseMs = t.tarjiReleaseMs,
        )

        /** Restore an imported sample's knobs into the Ink Lab tuning. */
        fun applyToTuning(knobs: TarjiLabKnobs, t: InkEngine.Tuning): InkEngine.Tuning =
            t.copy(
                glintResonanceMaxHz = knobs.maxTremoloHz,
                tarjiMinHz = knobs.minTremoloHz,
                tarjiHoldMinMs = knobs.holdMinMs,
                tarjiMinDepth = knobs.minTremoloDepth,
                tarjiMinPeriodicity = knobs.minPeriodicity,
                tarjiPitchDrift = knobs.maxPitchDrift,
                tarjiAttackMs = knobs.attackMs,
                tarjiReleaseMs = knobs.releaseMs,
            )
    }
}

/**
 * Per-hop detector output for one [TarjiLabCapture], computed by replaying
 * the captured hops through a fresh [Tarji] — the same pure DSP that runs
 * live on the tap, with the knobs fixed at analysis time. Every array is
 * hop-aligned with the capture.
 */
class TarjiLabTrace internal constructor(
    val hopCount: Int,
    val hopDurationMs: Float,
    /** Index of the first hop with detector output (the 4-hop frame warmup). */
    val firstAnalysisHop: Int,
    /** 80 ms frame RMS envelope — the same values [Tarji]'s scan sees. */
    val envRms: FloatArray,
    val tremolo: FloatArray,
    val gain: FloatArray,
    val reverberating: BooleanArray,
    val rateHz: FloatArray,
    val pitchHz: FloatArray,
    val holdMs: FloatArray,
) {
    /** The closed span of hops where the detector held a reverberation. */
    val reverberatingSpan: IntRange?
        get() {
            var first = -1
            var last = -1
            for (i in firstAnalysisHop until hopCount) {
                if (reverberating[i]) {
                    if (first < 0) first = i
                    last = i
                }
            }
            return if (first < 0) null else first..last
        }

    /** Mean detected rate over the reverberating span (Hz), 0 when none. */
    val meanRateHz: Float
        get() {
            val span = reverberatingSpan ?: return 0f
            var sum = 0f
            var n = 0
            for (i in span) {
                if (rateHz[i] > 0f) {
                    sum += rateHz[i]
                    n++
                }
            }
            return if (n == 0) 0f else sum / n
        }
}

/**
 * Re-run the tarjīʿ detector over a captured hop stream with [knobs],
 * snapshotting every published value per hop. Feeding one hop at a time
 * reproduces the live path exactly (same ring, same hop clock); the delay
 * history is irrelevant offline, so [Tarji.delayHops] stays zero and the
 * reported values are the ones the shimmer would render at the tap.
 */
fun analyzeTarjiCapture(
    capture: TarjiLabCapture,
    knobs: TarjiLabKnobs,
): TarjiLabTrace {
    val n = capture.hopCount
    val detector = Tarji()
    detector.hopSamples = capture.hopSamples
    knobs.applyTo(detector)
    val scratch = FloatArray(capture.hopSamples)
    val hopDur = capture.hopContentDurationMs()
    val env = FloatArray(n)
    val tremolo = FloatArray(n)
    val gain = FloatArray(n)
    val reverberating = BooleanArray(n)
    val rate = FloatArray(n)
    val pitch = FloatArray(n)
    val hold = FloatArray(n)
    var resolved = -1
    for (i in 0 until n) {
        System.arraycopy(capture.pcm, i * capture.hopSamples, scratch, 0, capture.hopSamples)
        detector.onSamples8k(scratch)
        // The detector resolves its first hop only once the 80 ms ring (four
        // hops) is full — the same warmup the live path has.
        if (i < DETECTOR_FRAME_HOPS - 1) continue
        if (resolved < 0) resolved = i
        env[i] = frameRms(capture.pcm, i, capture.hopSamples)
        tremolo[i] = detector.tremolo
        gain[i] = detector.tremoloGain
        reverberating[i] = detector.reverberating
        rate[i] = detector.lastRateHz
        pitch[i] = detector.lastPitchHz
        hold[i] = detector.holdMs
    }
    if (resolved < 0) resolved = DETECTOR_FRAME_HOPS - 1
    return TarjiLabTrace(
        hopCount = n,
        hopDurationMs = hopDur,
        firstAnalysisHop = resolved,
        envRms = env,
        tremolo = tremolo,
        gain = gain,
        reverberating = reverberating,
        rateHz = rate,
        pitchHz = pitch,
        holdMs = hold,
    )
}

/** RMS of the 80 ms frame ending at hop [hop] (hops [hop−3]..[hop]) — the
 * detector's own envelope window. */
private fun frameRms(pcm: FloatArray, hop: Int, hopSamples: Int): Float {
    var sum = 0f
    val start = (hop - 3) * hopSamples
    for (j in start until start + 4 * hopSamples) sum += pcm[j] * pcm[j]
    return sqrt(sum / (4f * hopSamples))
}

/** Interpolated trace values at [ms] from the capture start — the lab's
 * playhead read. Falls back to the nearest resolved hop outside the trace. */
class TarjiLabPoint(
    val tremolo: Float,
    val gain: Float,
    val reverberating: Boolean,
    val rateHz: Float,
)

fun tracePointAt(trace: TarjiLabTrace, ms: Float): TarjiLabPoint {
    val first = trace.firstAnalysisHop
    val last = trace.hopCount - 1
    if (last < first) return TarjiLabPoint(0f, 0f, false, 0f)
    val hopDuration = trace.hopDurationMs
    var f = (ms / hopDuration) - 0.5f
    if (f < first) f = first.toFloat()
    if (f > last) f = last.toFloat()
    val before = f.toInt()
    val after = (before + 1).coerceAtMost(last)
    val fraction = (f - before).coerceIn(0f, 1f)
    fun lerp(a: FloatArray, x: Int, y: Int): Float = a[x] + fraction * (a[y] - a[x])
    val rev = trace.reverberating[after] || trace.reverberating[before]
    return TarjiLabPoint(
        tremolo = lerp(trace.tremolo, before, after),
        gain = lerp(trace.gain, before, after),
        reverberating = rev,
        rateHz = trace.rateHz[after].takeIf { rev } ?: 0f,
    )
}

/**
 * The ideal sine the measured [TarjiLabTrace.tremolo] is compared against:
 * the rate is the detector's own mean, the phase and amplitude are least-
 * squares fitted over the reverberating span, so the lab shows at a glance
 * whether the measured pulse is clean and in-phase with a pure vibrato.
 */
class TarjiSineFit internal constructor(
    val amplitude: Float,
    val phaseRad: Float,
    val rateHz: Float,
    /** Hop index where the fit starts (reverberating span start). */
    val startHop: Int,
    /** Hop index (inclusive) where the fit ends. */
    val endHop: Int,
) {
    /** The fitted sine at [ms] from the capture start (0 outside the span). */
    fun valueAt(ms: Float, hopDurationMs: Float): Float {
        val hop = (ms / hopDurationMs)
        if (hop < startHop || hop > endHop || rateHz <= 0f) return 0f
        val t = (ms - startHop * hopDurationMs) / 1000f
        return amplitude * sin(2f * PI_F * rateHz * t + phaseRad)
    }

    private companion object {
        const val PI_F = 3.14159265f
    }
}

/** Least-squares sine fit over the trace's reverberating span. */
fun fitTarjiSine(trace: TarjiLabTrace): TarjiSineFit? {
    val span = trace.reverberatingSpan ?: return null
    val rate = trace.meanRateHz
    if (rate <= 0f) return null
    val n = span.last - span.first + 1
    if (n < 3) return null
    val hopDur = trace.hopDurationMs
    var a = 0f
    var b = 0f
    var weight = 0f
    for (i in span) {
        val t = (i - span.first) * hopDur / 1000f
        val w = trace.gain[i]
        a += w * trace.tremolo[i] * sin(2f * PI * rate * t)
        b += w * trace.tremolo[i] * cos(2f * PI * rate * t)
        weight += w
    }
    if (weight <= 0f) return null
    a /= weight
    b /= weight
    val amplitude = sqrt(a * a + b * b)
    if (amplitude <= 1e-4f) return null
    return TarjiSineFit(
        amplitude = amplitude,
        phaseRad = atan2(a, b),
        rateHz = rate,
        startHop = span.first,
        endHop = span.last,
    )
}

private const val PI = 3.14159265f

/** The detector's 80 ms ring holds four 20 ms hops; analysis starts at hop 3. */
private const val DETECTOR_FRAME_HOPS = 4
