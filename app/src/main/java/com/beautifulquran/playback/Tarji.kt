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

    /** Debug/diagnostics: last pitch estimate (Hz) and its clarity 0..1. */
    var lastPitchHz = 0f
        private set
    var lastClarity = 0f
        private set

    /** Diagnostics: last measured envelope oscillation rate (Hz). */
    var lastRateHz = 0f
        private set

    /**
     * Read-out delay in content hops, set from the output route latency
     * (× playback speed) by [VoiceEnergy]: the PCM tap hears the voice
     * *before* the listener does, so the reported signal is delayed to match
     * what is actually reaching the ear right now.
     */
    var delayHops = 0

    /**
     * Fastest envelope oscillation that still counts as tarjīʿ (Hz) — the
     * Ink Lab's rate ceiling. Only pulses at or below this rate open the
     * shimmer.
     */
    var maxTremoloHz = MAX_TREMOLO_HZ

    // Reported (delayed) views — what renderers should consume.
    private val histTremolo = FloatArray(HIST_HOPS)
    private val histGain = FloatArray(HIST_HOPS)
    private val histRev = FloatArray(HIST_HOPS)
    private var histCount = 0

    /** Tarjīʿ state as it reaches the ear now (delayed by [delayHops]). */
    val syncReverberating: Boolean get() = histRev[histIndex()] != 0f
    val syncTremolo: Float get() = histTremolo[histIndex()]
    val syncTremoloGain: Float get() = histGain[histIndex()]

    private fun histIndex(): Int {
        val oldest = maxOf(0, histCount - HIST_HOPS)
        return (maxOf(oldest, histCount - 1 - delayHops)) % HIST_HOPS
    }

    // Rolling 80 ms analysis frame at 8 kHz, plus a reuse buffer for the
    // linearised copy (the audio thread must not allocate per hop).
    private val frame = FloatArray(FRAME_SAMPLES)
    private val work = FloatArray(FRAME_SAMPLES)
    private val corrs = FloatArray(MAX_LAG + 1)
    private var frameFill = 0

    // Per-hop envelope RMS series (48 hops ≈ 1 s).
    private val env = FloatArray(ENV_HOPS)
    private val envCorr = FloatArray(MAX_ENV_LAG + 1)
    private var envCount = 0

    private var holdPitchHz = 0f
    private var holdStartEnvCount = 0
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
        histCount = 0
        holdStartEnvCount = 0
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
        lastPitchHz = pitchHz
        lastClarity = clarity
        val voiced = clarity >= MIN_CLARITY && rms >= floor

        if (voiced) {
            val sameNote = holdPitchHz > 0f && samePitch(pitchHz, holdPitchHz)
            if (holdMs > 0f && sameNote) {
                holdMs += HOP_MS
                // Track in the anchor's own octave — single-hop octave flips
                // (lag L vs 2L scoring within noise) must not drag it.
                var p = pitchHz
                while (p > holdPitchHz * 1.4142f) p /= 2f
                while (p < holdPitchHz / 1.4142f) p *= 2f
                holdPitchHz += PITCH_EMA * (p - holdPitchHz)
            } else {
                // New note (or first voiced frame): the hold restarts here.
                holdMs = HOP_MS.toFloat()
                holdPitchHz = pitchHz
                holdStartEnvCount = envCount
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

        histTremolo[histCount % HIST_HOPS] = tremolo
        histGain[histCount % HIST_HOPS] = tremoloGain
        histRev[histCount % HIST_HOPS] = if (reverberating) 1f else 0f
        histCount++
    }

    /** Envelope oscillation scan over the held note's own envelope. */
    private fun updateTremolo(latestRms: Float) {
        // Only envelope samples from within the hold: the syllable attack
        // ramp would otherwise poison the depth estimate for ~1 s.
        val n = minOf(envCount - holdStartEnvCount, ENV_HOPS)
        if (n < MIN_ENV_HOPS) {
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

        // Periodicity of the demeaned envelope: autocorrelation over the
        // tarjīʿ band (1.5 Hz … maxTremoloHz). Robust where crossings fail —
        // reciters pulse anywhere from slow ~2 Hz swells (Hani) to ~6–8 Hz
        // vibrato (Alafasy).
        val minLag = (1000f / (maxTremoloHz * HOP_MS)).toInt()
            .coerceIn(2, MAX_ENV_LAG - 4)
        var bestC = 0f
        var bestLag = 0
        for (lag in 2..MAX_ENV_LAG) {
            if (n - lag < MIN_ENV_HOPS / 2) break
            var corr = 0f
            var e0 = 0f
            var eL = 0f
            for (j in 0 until n - lag) {
                val a = env[(start + j) % ENV_HOPS] - mean
                val b = env[(start + j + lag) % ENV_HOPS] - mean
                corr += a * b
                e0 += a * a
                eL += b * b
            }
            val norm = corr / (sqrt(e0 * eL) + 1e-9f)
            envCorr[lag] = norm
            if (lag >= minLag && norm > bestC) {
                bestC = norm
                bestLag = lag
            }
        }
        // Ceiling game only: a low maxTremoloHz must not admit a faster pulse
        // via its double/triple-period lag (5.5 Hz reading as 2.8 Hz under a
        // 4 Hz ceiling). The old guard vetoed any pick whose *in-band* half
        // was strong — that also killed real tarjīʿ on Alafasy/Hani 1:7 when
        // fast ~10–25 Hz texture coexisted with the slow swell. Only an
        // out-of-band short peak that is an exact submultiple of the pick
        // counts as a ceiling cheat.
        var shortPeakLag = 0
        var shortPeakC = 0f
        for (lag in 2 until minLag) {
            val c = envCorr[lag]
            if (c > shortPeakC) {
                shortPeakC = c
                shortPeakLag = lag
            }
        }
        val harmonicLeak = shortPeakLag >= 2 && bestLag > 0 &&
            bestLag % shortPeakLag == 0 && bestLag > shortPeakLag &&
            shortPeakC >= HARMONIC_OF_BEST * bestC

        val rateHz =
            if (bestLag > 0) 1000f / (bestLag * HOP_MS.toFloat()) else 0f
        lastRateHz = rateHz

        // Keep the signal smoothing even while gated off, so a lock-on starts
        // from the live envelope rather than a stale frozen value.
        val raw = ((latestRms - mean) / amp).coerceIn(-1.5f, 1.5f)
        val prev = tremoloSmoothed
        tremoloSmoothed += TREMOLO_EMA * (raw - tremoloSmoothed)

        // Hysteresis: stricter to switch on than to stay on — no flapping
        // when the reverberation breathes near the gate.
        val longEnough = holdMs >= HOLD_MIN_MS
        val periodic = bestC >= MIN_PERIODICITY && !harmonicLeak &&
            rateHz in MIN_TREMOLO_HZ..maxTremoloHz
        val stillPeriodic = bestC >= MIN_PERIODICITY * 0.7f && !harmonicLeak &&
            rateHz in (MIN_TREMOLO_HZ - 0.5f)..(maxTremoloHz + 1f)
        reverberating = if (reverberating) {
            longEnough && depth >= MIN_TREMOLO_DEPTH * DEPTH_OFF_RATIO && stillPeriodic
        } else {
            longEnough && depth >= MIN_TREMOLO_DEPTH && periodic
        }

        // Lead the measured oscillation by the analysis+smoothing lag, so the
        // shimmer swells *with* the voice rather than trailing it: for a
        // near-sinusoid at the measured rate, s(t+τ) ≈ s·cos ωτ + ṡ·sin ωτ / ω.
        tremolo = if (reverberating) {
            val omega = 2f * Math.PI.toFloat() * rateHz
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

    /** Octave-folded pitch match: autocorrelation flips freely between a
     * period and its double on real voice, and those are the same note. */
    private fun samePitch(a: Float, b: Float): Boolean {
        if (a <= 0f || b <= 0f) return false
        var r = a / b
        while (r > 1.4142f) r /= 2f
        while (r < 0.7071f) r *= 2f
        return abs(r - 1f) <= MAX_PITCH_DRIFT
    }

    /** Normalized-autocorrelation pitch over the reciter's vocal range. */
    private fun pitch(): Pair<Float, Float> {
        var energy = 0f
        for (j in 0 until FRAME_SAMPLES - MAX_LAG) energy += work[j] * work[j]
        if (energy <= 1e-8f) return 0f to 0f
        var best = 0f
        for (lag in MIN_LAG..MAX_LAG) {
            var corr = 0f
            for (j in 0 until FRAME_SAMPLES - MAX_LAG) corr += work[j] * work[j + lag]
            corrs[lag] = corr
            if (corr > best) best = corr
        }
        if (best <= 0f) return 0f to 0f
        // Octave-stabilize: harmonics make lag multiples score near-identically,
        // and picking the plain max flips the estimate between L and 2L hop to
        // hop (which kept resetting the hold on real recitation). Take the
        // shortest period within 5% of the best instead.
        var lag = MIN_LAG
        while (lag <= MAX_LAG && corrs[lag] < 0.95f * best) lag++
        if (lag > MAX_LAG) return 0f to 0f
        return (SAMPLE_RATE / lag.toFloat()) to (best / energy)
    }

    companion object {
        const val SAMPLE_RATE = 8_000
        const val HOP_MS = 20
        const val HOP_SAMPLES = SAMPLE_RATE * HOP_MS / 1000 // 160
        const val FRAME_SAMPLES = HOP_SAMPLES * 4 // 80 ms
        private const val ENV_HOPS = 64
        private const val MIN_ENV_HOPS = 30

        /** Hold must survive this long before reverberation is considered. */
        const val HOLD_MIN_MS = 400

        // Reciter vocal range ~70–350 Hz (covers playback-speed shifts).
        private const val MIN_LAG = SAMPLE_RATE / 350 // 22
        private const val MAX_LAG = SAMPLE_RATE / 70 // 114
        private const val MIN_CLARITY = 0.5f
        /** Pitch glide tolerance on long waqf holds (fraction). Real closers
         * slide a little without leaving the note. */
        private const val MAX_PITCH_DRIFT = 0.12f
        private const val PITCH_EMA = 0.1f
        /** Brief unvoiced blips inside a long hold (hops of grace). */
        private const val MAX_MISSES = 6

        private const val MIN_FLOOR = 0.006f
        private const val FLOOR_OF_PEAK = 0.15f
        private const val PEAK_DECAY = 0.997f

        /** Tarjīʿ lives around 1.5–10 Hz of envelope oscillation: slow ~2 Hz
         * swells (Hani) to ~6–8 Hz vibrato (Alafasy), at any hop rate. The
         * ceiling is Ink-Lab-tunable via [maxTremoloHz]. */
        private const val MIN_TREMOLO_HZ = 1.5f
        const val MAX_TREMOLO_HZ = 10f
        private const val MIN_TREMOLO_DEPTH = 0.035f
        /** Off-gate depth as a fraction of [MIN_TREMOLO_DEPTH] (hysteresis). */
        private const val DEPTH_OFF_RATIO = 0.7f
        /** Envelope-autocorrelation periodicity needed to call it a pulse. */
        private const val MIN_PERIODICITY = 0.4f
        /** Out-of-band short peak must be this strong vs the in-band pick to
         * count as a ceiling cheat (see harmonicLeak). */
        private const val HARMONIC_OF_BEST = 0.85f
        // Band floor as an envelope-hop lag (20 ms hops): 1.5 Hz → 33.
        private const val MAX_ENV_LAG = 33
        private const val TREMOLO_EMA = 0.35f
        /** Analysis + smoothing lag the phase lead compensates (~45 ms). */
        private const val LAG_SEC = 0.045f
        private const val ATTACK_MS = 250f
        /** Slow release: sub-second lulls in a long hold's pulsing (the
         * reciter breathing *within* the tarjīʿ) must not drop the shimmer. */
        private const val RELEASE_MS = 800f
        /** Read-out history for the output-latency delay (~1.3 s). */
        private const val HIST_HOPS = 64
    }
}
