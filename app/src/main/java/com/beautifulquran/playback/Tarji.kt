package com.beautifulquran.playback

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tarjīʿ (ترجيع) detector — the repeated reverberation of the voice on a
 * single held note, as in the ḥadīth of Ibn Mughaffal describing the
 * Prophet's ﷺ recitation ("يُرَجِّعُ"). Pure DSP over 8 kHz mono samples:
 * no Android dependencies, hop-count time only, so unit tests synthesize
 * waves directly.
 *
 * A hold is reported ([holdMs]) while successive 80 ms frames stay voiced
 * (periodic, above the noise floor) on one pitch. Once the hold is long
 * enough, the amplitude envelope is scanned for an oscillation in the
 * tarjīʿ band (~3–9.5 Hz): when its depth clears [MIN_TREMOLO_DEPTH],
 * [reverberating] turns on and [tremolo] exposes the oscillation itself,
 * zero-centred and in phase with the voice, so the glint rides the exact
 * reverberation the listener hears. [tremoloGain] ramps the effect in and
 * out so neither detection edge ever pops.
 *
 * Fed by [VoiceEnergy.onPcm16]; consumed read-only from the glint draw
 * path via the volatile mirrors in [VoiceEnergy].
 */
class Tarji {

    /** True while a held note carries a detected reverberation. */
    var reverberating = false
        private set

    /** The reverberation itself: zero-centred, ~−1..1, phase-locked to the
     * voice. Meaningful only while [reverberating]. */
    var tremolo = 0f
        private set

    /** Attack/release envelope (0..1) on the whole effect — no pops at the
     * detection edges. */
    var tremoloGain = 0f
        private set

    /** Milliseconds the current single note has been held. */
    var holdMs = 0f
        private set

    // Rolling 80 ms analysis frame at 8 kHz, plus a reuse buffer for the
    // linearised copy (the audio thread must not allocate per hop).
    private val frame = FloatArray(FRAME_SAMPLES)
    private val work = FloatArray(FRAME_SAMPLES)
    private var frameFill = 0

    // Per-hop envelope RMS series (48 hops ≈ 1 s).
    private val env = FloatArray(ENV_HOPS)
    private var envCount = 0

    private var holdPitchHz = 0f
    private var misses = 0
    private var peak = 0f
    private var tremoloSmoothed = 0f

    /** Consume [length] mono samples at 8 kHz. Called on the audio thread. */
    fun onSamples8k(samples: FloatArray, length: Int = samples.size) {
        for (i in 0 until length) {
            frame[frameFill % FRAME_SAMPLES] = samples[i]
            frameFill++
            if (frameFill >= FRAME_SAMPLES && frameFill % HOP_SAMPLES == 0) onHop()
        }
    }

    fun reset() {
        reverberating = false
        tremolo = 0f
        tremoloGain = 0f
        holdMs = 0f
        holdPitchHz = 0f
        misses = 0
        peak = 0f
        tremoloSmoothed = 0f
        frameFill = 0
        envCount = 0
    }

    private fun onHop() {
        // Linearise the ring into [work] (oldest → newest).
        val start = frameFill - FRAME_SAMPLES
        for (j in 0 until FRAME_SAMPLES) work[j] = frame[(start + j) % FRAME_SAMPLES]

        var sumSq = 0f
        for (v in work) sumSq += v * v
        val rms = sqrt(sumSq / FRAME_SAMPLES)
        peak = maxOf(rms, peak * PEAK_DECAY)
        val floor = maxOf(MIN_FLOOR, FLOOR_OF_PEAK * peak)

        env[envCount % ENV_HOPS] = rms
        envCount++

        val (pitchHz, clarity) = pitch()
        val voiced = clarity >= MIN_CLARITY && rms >= floor

        if (voiced) {
            val sameNote =
                holdPitchHz > 0f && abs(pitchHz - holdPitchHz) / holdPitchHz <= MAX_PITCH_DRIFT
            if (holdMs > 0f && sameNote) {
                holdMs += HOP_MS
                holdPitchHz += PITCH_EMA * (pitchHz - holdPitchHz)
            } else {
                // New note (or first voiced frame): the hold restarts here.
                holdMs = HOP_MS.toFloat()
                holdPitchHz = pitchHz
            }
            misses = 0
        } else if (++misses > MAX_MISSES) {
            holdMs = 0f
            holdPitchHz = 0f
        }

        updateTremolo(rms)

        val target = if (reverberating) 1f else 0f
        val tau = if (reverberating) ATTACK_MS else RELEASE_MS
        tremoloGain += (HOP_MS / tau) * (target - tremoloGain)
    }

    /** Envelope oscillation scan over the recent window. */
    private fun updateTremolo(latestRms: Float) {
        val n = minOf(envCount, ENV_HOPS)
        if (holdMs < HOLD_MIN_MS || n < MIN_ENV_HOPS) {
            reverberating = false
            return
        }
        val start = envCount - n
        var mean = 0f
        for (j in 0 until n) mean += env[(start + j) % ENV_HOPS]
        mean /= n
        if (mean <= 0f) {
            reverberating = false
            return
        }
        var sumSq = 0f
        for (j in 0 until n) {
            val d = env[(start + j) % ENV_HOPS] - mean
            sumSq += d * d
        }
        val amp = sqrt(2f * sumSq / n) // sine-amplitude estimate
        val depth = amp / mean

        // Oscillation rate from hysteresis crossings of the demeaned envelope.
        val h = 0.3f * sqrt(sumSq / n)
        var crossings = 0
        var sign = 0
        for (j in 0 until n) {
            val d = env[(start + j) % ENV_HOPS] - mean
            if (sign <= 0 && d > h) {
                if (sign < 0) crossings++
                sign = 1
            } else if (sign >= 0 && d < -h) {
                if (sign > 0) crossings++
                sign = -1
            }
        }
        val hz = crossings / (2f * n * HOP_MS / 1000f)

        reverberating = depth >= MIN_TREMOLO_DEPTH && hz in MIN_TREMOLO_HZ..MAX_TREMOLO_HZ
        val raw = ((latestRms - mean) / amp).coerceIn(-1.5f, 1.5f)
        val prev = tremoloSmoothed
        tremoloSmoothed += TREMOLO_EMA * (raw - tremoloSmoothed)
        // Lead the measured oscillation by the analysis+smoothing lag, so the
        // shimmer swells *with* the voice rather than trailing it: for a
        // near-sinusoid at the measured rate, s(t+τ) ≈ s·cos ωτ + ṡ·sin ωτ / ω.
        tremolo = if (reverberating) {
            val omega = 2f * Math.PI.toFloat() * hz
            val dS = (tremoloSmoothed - prev) * (1000f / HOP_MS)
            val wt = (omega * LAG_SEC).coerceAtMost(1.2f)
            (
                tremoloSmoothed * kotlin.math.cos(wt) +
                    (dS / omega) * kotlin.math.sin(wt)
                ).coerceIn(-1.5f, 1.5f)
        } else {
            tremoloSmoothed
        }
    }

    /** Normalized-autocorrelation pitch over the reciter's vocal range. */
    private fun pitch(): Pair<Float, Float> {
        var energy = 0f
        for (j in 0 until FRAME_SAMPLES - MAX_LAG) energy += work[j] * work[j]
        if (energy <= 1e-8f) return 0f to 0f
        var bestLag = 0
        var best = 0f
        for (lag in MIN_LAG..MAX_LAG) {
            var corr = 0f
            for (j in 0 until FRAME_SAMPLES - MAX_LAG) corr += work[j] * work[j + lag]
            if (corr > best) {
                best = corr
                bestLag = lag
            }
        }
        if (bestLag == 0) return 0f to 0f
        return (SAMPLE_RATE / bestLag.toFloat()) to (best / energy)
    }

    companion object {
        const val SAMPLE_RATE = 8_000
        const val HOP_MS = 20
        const val HOP_SAMPLES = SAMPLE_RATE * HOP_MS / 1000 // 160
        const val FRAME_SAMPLES = HOP_SAMPLES * 4 // 80 ms
        private const val ENV_HOPS = 48
        private const val MIN_ENV_HOPS = 20

        /** Hold must survive this long before reverberation is considered. */
        const val HOLD_MIN_MS = 400

        // Reciter vocal range ~70–350 Hz (covers playback-speed shifts).
        private const val MIN_LAG = SAMPLE_RATE / 350 // 22
        private const val MAX_LAG = SAMPLE_RATE / 70 // 114
        private const val MIN_CLARITY = 0.5f
        private const val MAX_PITCH_DRIFT = 0.08f
        private const val PITCH_EMA = 0.1f
        private const val MAX_MISSES = 2

        private const val MIN_FLOOR = 0.006f
        private const val FLOOR_OF_PEAK = 0.15f
        private const val PEAK_DECAY = 0.997f

        /** Tarjīʿ lives around 3–9.5 Hz of envelope oscillation. */
        private const val MIN_TREMOLO_HZ = 3f
        private const val MAX_TREMOLO_HZ = 9.5f
        private const val MIN_TREMOLO_DEPTH = 0.035f
        private const val TREMOLO_EMA = 0.35f
        /** Analysis + smoothing lag the phase lead compensates (~45 ms). */
        private const val LAG_SEC = 0.045f
        private const val ATTACK_MS = 250f
        private const val RELEASE_MS = 450f
    }
}
