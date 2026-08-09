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
 * tarjīʿ band (~1.5–10 Hz): when its depth clears [MIN_TREMOLO_DEPTH],
 * [reverberating] turns on and [tremolo] exposes the oscillation itself,
 * zero-centred and in phase with the voice, so the glint rides the exact
 * reverberation the listener hears. [tremoloGain] ramps the effect in and
 * out so neither detection edge ever pops.
 *
 * Tarjīʿ is the *climax* of the hold: once the smoothed envelope falls
 * below half the hold's peak (the voice releases), the reverberation is
 * over and the effect stops — a fast dry-down, no flicker through the
 * decaying tail.
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

    /** Total content hops consumed, including the three-hop frame warm-up. */
    var hopCount = 0
        private set

    /** Diagnostics: last measured envelope oscillation rate (Hz). */
    var lastRateHz = 0f
        private set

    /**
     * Read-out delay in content hops, set from the tap-to-ear latency
     * (route + measured tap-to-playback-head backlog, × playback speed, plus
     * the Sonic resampler's own content-time buffer at non-1× speed) by
     * [VoiceEnergy]: the PCM tap hears the voice *before* the listener does,
     * so the reported signal is delayed to match what is actually reaching
     * the ear right now.
     */
    var delayHops = 0

    /**
     * Decimated samples per analysis hop, set by [VoiceEnergy] from the real
     * stream rate so one hop is exactly [HOP_MS] of *content* at any source
     * rate (44.1 kHz decimates to 8820 Hz → 176.4 → 176 samples). The default
     * 160 is exact for 8 kHz. Keeps the delay, rate reads, and phase lead in
     * true content time instead of drifting with the integer decimation.
     */
    var hopSamples = HOP_SAMPLES

    /**
     * Fastest envelope oscillation that still counts as tarjīʿ (Hz) — the
     * Ink Lab's rate ceiling. Only pulses at or below this rate open the
     * shimmer.
     */
    var maxTremoloHz = MAX_TREMOLO_HZ

    /** Slowest envelope oscillation that still counts (Hz). Ink Lab. */
    var minTremoloHz = MIN_TREMOLO_HZ

    /** Hold length (ms) before reverberation is considered. Ink Lab. */
    var holdMinMs = HOLD_MIN_MS.toFloat()

    /** Minimum relative envelope AM depth to open the gate. Ink Lab. */
    var minTremoloDepth = MIN_TREMOLO_DEPTH

    /** Minimum envelope autocorrelation to call the pulse periodic. Ink Lab. */
    var minPeriodicity = MIN_PERIODICITY

    /** Pitch glide tolerance (fraction) while holding one note. Ink Lab. */
    var maxPitchDrift = MAX_PITCH_DRIFT

    /** Attack envelope of [tremoloGain] (ms). Ink Lab. */
    var attackMs = ATTACK_MS

    /** Release envelope of [tremoloGain] (ms). Ink Lab. */
    var releaseMs = RELEASE_MS

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

    // Rolling 80 ms analysis frame at the decimated rate, plus a reuse
    // buffer for the linearised copy (the audio thread must not allocate per
    // hop). [hopSamples] fixes a hop at exactly 20 ms of content.
    private var frame = FloatArray(FRAME_SAMPLES)
    private var work = FloatArray(FRAME_SAMPLES)
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
    private var trackedLag = 0

    // Climax tracker: tarjīʿ is the *building* reverberation of the hold,
    // peaking with the voice and ending when the voice releases. The level
    // EMA is fast enough to catch the release onset (the voice falls below
    // the gate within ~100 ms of dropping); the vibrato's own troughs sit
    // well above the gate, so a deep hold never trips its own gate. A short
    // persistence kills single-hop noise dips.
    private var climaxLevel = 0f
    private var holdPeak = 0f
    private var endOfHold = false
    private var climaxUnder = 0

    /** Consume [length] mono samples at the decimated rate (≈8 kHz). Called
     * on the audio thread. */
    fun onSamples8k(samples: FloatArray, length: Int = samples.size) {
        val hop = hopSamples
        if (frame.size != hop * FRAME_HOPS) {
            // Source rate changed: one hop must still be exactly HOP_MS of
            // content. Restart the frame so the hop boundary stays aligned.
            frame = FloatArray(hop * FRAME_HOPS)
            work = FloatArray(hop * FRAME_HOPS)
            frameFill = 0
        }
        for (i in 0 until length) {
            frame[frameFill % frame.size] = samples[i]
            frameFill++
            if (frameFill % hop == 0) {
                hopCount++
                if (frameFill >= frame.size) onHop()
            }
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
        climaxLevel = 0f
        holdPeak = 0f
        endOfHold = false
        climaxUnder = 0
        trackedLag = 0
        frameFill = 0
        envCount = 0
        histCount = 0
        hopCount = 0
        holdStartEnvCount = 0
    }

    private fun onHop() {
        // Linearise the ring into [work] (oldest → newest).
        val start = frameFill - frame.size
        for (j in 0 until frame.size) work[j] = frame[(start + j) % frame.size]

        var sumSq = 0f
        for (v in work) sumSq += v * v
        val rms = sqrt(sumSq / frame.size)
        peak = maxOf(rms, peak * PEAK_DECAY)
        val floor = maxOf(MIN_FLOOR, FLOOR_OF_PEAK * peak)

        env[envCount % ENV_HOPS] = rms
        envCount++
        climaxLevel += CLIMAX_EMA * (rms - climaxLevel)

        val (pitchHz, clarity) = pitch()
        lastPitchHz = pitchHz
        lastClarity = clarity
        val voiced = clarity >= MIN_CLARITY && rms >= floor

        if (voiced) {
            val sameNote = holdPitchHz > 0f && samePitch(pitchHz, holdPitchHz)
            if (holdMs > 0f && sameNote) {
                holdMs += HOP_MS
                holdPeak = maxOf(holdPeak, rms)
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
                holdPeak = rms
                climaxUnder = 0
                endOfHold = false
                trackedLag = 0
            }
            misses = 0
        } else if (++misses > MAX_MISSES) {
            holdMs = 0f
            holdPitchHz = 0f
            endOfHold = true
            trackedLag = 0
        }

        updateTremolo(rms)

        // The shimmer settles with the voice: its strength follows the
        // envelope's remaining intensity, full while the swell is strong
        // (≥ [CLIMAX_FULL] of the hold's peak) and fading as the voice
        // dies toward the climax gate — the end of the word reads as the
        // effect drying, never as a full-strength pulse after the climax.
        val target = if (reverberating) {
            val level = if (holdPeak > 0f) climaxLevel / holdPeak else 1f
            ((level - CLIMAX_OFF) / (CLIMAX_FULL - CLIMAX_OFF)).coerceIn(0f, 1f)
        } else {
            0f
        }
        val tau = if (reverberating) {
            attackMs.coerceAtLeast(1f)
        } else if (endOfHold) {
            // The hold's climax is over: dry the shimmer fast so it never
            // flickers into the tail or the next word.
            CLIMAX_RELEASE_MS
        } else {
            // Mid-hold lull (detection flap): bridge it without a blink.
            releaseMs.coerceAtLeast(1f)
        }
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
            endOfHold = true
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

        // Stay on the envelope's period once acquired: the autocorr's band
        // peak is broad and the best lag can flap hop to hop on real vibrato
        // (5.5 → 10 → 2.8 Hz in a single hold), which would make the shimmer
        // pulse at a different rate than the voice. Keep the previous lag
        // while its correlation is still respectable — and never trust a
        // switch when the whole autocorr has collapsed (bestC below
        // [TRACKED_SWITCH_FLOOR] means the peak is noise).
        val acquired = trackedLag !in 2..MAX_ENV_LAG
        trackedLag = when {
            acquired -> bestLag
            bestLag == 0 -> trackedLag
            bestC < TRACKED_SWITCH_FLOOR -> trackedLag
            envCorr[trackedLag] >= TRACKED_LAG_KEEP * bestC -> trackedLag
            else -> bestLag
        }
        if (acquired && trackedLag > 0 && bestC > 0f) {
            // Octave-stable start: tarjīʿ is the *slow* swell — when the
            // broad peak ties a period with its double (10 Hz texture vs the
            // 5 Hz swell), the slowest equally-strong lag is the voice's
            // period, not the texture's harmonic. Same rule the pitch uses.
            var slowest = trackedLag
            for (lag in trackedLag + 1..MAX_ENV_LAG) {
                if (envCorr[lag] >= OCTAVE_START_KEEP * bestC) slowest = lag
            }
            trackedLag = slowest
        }
        val rateHz =
            if (trackedLag > 0) 1000f / (trackedLag * HOP_MS.toFloat()) else 0f
        lastRateHz = rateHz

        // Keep the signal smoothing even while gated off, so a lock-on starts
        // from the live envelope rather than a stale frozen value.
        val raw = ((latestRms - mean) / amp).coerceIn(-1.5f, 1.5f)
        val prev = tremoloSmoothed
        tremoloSmoothed += TREMOLO_EMA * (raw - tremoloSmoothed)

        // Hysteresis: stricter to switch on than to stay on — no flapping
        // when the reverberation breathes near the gate. Gates are Ink-Lab-
        // tunable (see [holdMinMs], [minPeriodicity], [minTremoloDepth],
        // [minTremoloHz], [maxTremoloHz]).
        val minHz = minTremoloHz.coerceAtLeast(0.5f)
        val maxHz = maxTremoloHz.coerceAtLeast(minHz)
        val depthGate = minTremoloDepth.coerceAtLeast(0f)
        val periodGate = minPeriodicity.coerceIn(0.05f, 1f)
        val longEnough = holdMs >= holdMinMs.coerceAtLeast(0f)

        // Ceiling-cheat guard: a pulse above the ceiling must not slip in as
        // its own double/triple period (5.5 Hz reading as 2.8 Hz under a
        // 4 Hz ceiling). Only the (maxHz, 2×maxHz) cheat zone is scanned — a
        // pick's *true* double lives there — so coexisting fast vocal texture
        // (~10–25 Hz under a 10 Hz ceiling) never vetoes a real slow swell,
        // which is what the old wide scan did (it killed tarjīʿ on Alafasy
        // and Hani 1:7 when texture rode on the slow swell). The check runs
        // against the tracked period — the one the gates actually use.
        val cheatLo = (1000f / (2f * maxHz * HOP_MS)).toInt() + 1
        var shortPeakLag = 0
        var shortPeakC = 0f
        for (lag in cheatLo until minLag) {
            val c = envCorr[lag]
            if (c > shortPeakC) {
                shortPeakC = c
                shortPeakLag = lag
            }
        }
        val harmonicLeak = shortPeakLag >= 2 && trackedLag > 0 &&
            trackedLag % shortPeakLag == 0 && trackedLag > shortPeakLag &&
            // Only the pick's 2×/3× — the true ceiling cheat. Harmonics
            // further out (a 2.1 Hz swell's 8th harmonic at 16.7 Hz) are
            // voice texture riding the swell, not a cheat.
            trackedLag <= 3 * shortPeakLag &&
            shortPeakC >= HARMONIC_OF_BEST * bestC
        // Deep enough AM is self-evidently a vibration even when the swell is
        // irregular: real crescents (Alafasy 1:7's build to the waqf) pulse
        // unevenly and their envelope autocorrelation drops to ~0.1–0.3 while
        // the voice is clearly shaking — the periodicity gate alone would
        // drop the shimmer exactly at the climax. The rate band and the
        // harmonic-leak guard still hold (a fast flutter must never enter as
        // its own double period), and shallow noise swells stay rejected by
        // the autocorr.
        val deep = depth >= DEEP_DEPTH_GATE && rateHz in minHz..maxHz &&
            !harmonicLeak
        val periodic = (bestC >= periodGate && !harmonicLeak &&
            rateHz in minHz..maxHz) || deep
        val stillPeriodic = (bestC >= periodGate * 0.7f && !harmonicLeak &&
            rateHz in (minHz - 0.5f)..(maxHz + 1f)) ||
            (depth >= DEEP_DEPTH_GATE * DEPTH_OFF_RATIO && !harmonicLeak &&
                rateHz in (minHz - 0.5f)..(maxHz + 1f))
        // The climactic reverberation ends with the voice: once the fast
        // envelope level has sat below [CLIMAX_OFF] of the hold's own peak
        // for [CLIMAX_PERSIST] hops, the strong swell is over and the effect
        // must stop — the dying tail is not tarjīʿ. The gate sits above the
        // vibrato's smoothed troughs so a deep hold never trips its own gate.
        val under = holdPeak > 0f && climaxLevel < CLIMAX_OFF * holdPeak
        climaxUnder = if (under) climaxUnder + 1 else 0
        val climaxOver = climaxUnder >= CLIMAX_PERSIST
        if (climaxOver) endOfHold = true
        reverberating = if (reverberating) {
            longEnough && depth >= depthGate * DEPTH_OFF_RATIO && stillPeriodic &&
                !endOfHold
        } else {
            longEnough && depth >= depthGate && periodic && !endOfHold
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
            // Not reverberating: fade the stored signal with the gain so the
            // release dries to still ink instead of pulsing on tail noise.
            tremoloSmoothed * tremoloGain
        }
    }

    /** Octave-folded pitch match: autocorrelation flips freely between a
     * period and its double on real voice, and those are the same note. */
    private fun samePitch(a: Float, b: Float): Boolean {
        if (a <= 0f || b <= 0f) return false
        var r = a / b
        while (r > 1.4142f) r /= 2f
        while (r < 0.7071f) r *= 2f
        return abs(r - 1f) <= maxPitchDrift
    }

    /** Normalized-autocorrelation pitch over the reciter's vocal range. */
    private fun pitch(): Pair<Float, Float> {
        var energy = 0f
        for (j in 0 until frame.size - MAX_LAG) energy += work[j] * work[j]
        if (energy <= 1e-8f) return 0f to 0f
        var best = 0f
        for (lag in MIN_LAG..MAX_LAG) {
            var corr = 0f
            for (j in 0 until frame.size - MAX_LAG) corr += work[j] * work[j + lag]
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
        private const val FRAME_HOPS = 4
        private const val ENV_HOPS = 64
        private const val MIN_ENV_HOPS = 20

        /** Hold must survive this long before reverberation is considered.
         * Kept short so the shimmer starts its build-up with the hold. */
        const val HOLD_MIN_MS = 300

        // Reciter vocal range ~70–350 Hz (covers playback-speed shifts).
        private const val MIN_LAG = SAMPLE_RATE / 350 // 22
        private const val MAX_LAG = SAMPLE_RATE / 70 // 114
        private const val MIN_CLARITY = 0.5f
        /** Pitch glide tolerance on long waqf holds (fraction). Real closers
         * slide a little without leaving the note. Ink Lab: [maxPitchDrift]. */
        const val MAX_PITCH_DRIFT = 0.12f
        private const val PITCH_EMA = 0.1f
        /** Brief unvoiced blips inside a long hold (hops of grace). */
        private const val MAX_MISSES = 6

        private const val MIN_FLOOR = 0.006f
        private const val FLOOR_OF_PEAK = 0.15f
        private const val PEAK_DECAY = 0.997f

        /** Tarjīʿ lives around 1.5–10 Hz of envelope oscillation: slow ~2 Hz
         * swells (Hani) to ~6–8 Hz vibrato (Alafasy), at any hop rate.
         * Ink Lab: [minTremoloHz] / [maxTremoloHz]. */
        const val MIN_TREMOLO_HZ = 1.5f
        const val MAX_TREMOLO_HZ = 10f
        /** Shipped AM depth gate — Ink Lab: [minTremoloDepth]. */
        const val MIN_TREMOLO_DEPTH = 0.035f
        /** Relative AM depth at which the envelope is self-evidently
         * vibrating, autocorrelation aside — real crescents are irregular
         * and their periodicity dips below the autocorr gate right at the
         * climax. Shallow noise swells stay rejected by the autocorr. */
        private const val DEEP_DEPTH_GATE = 0.06f
        /** Off-gate depth as a fraction of [minTremoloDepth] (hysteresis). */
        private const val DEPTH_OFF_RATIO = 0.7f
        /** Envelope autocorrelation gate — Ink Lab: [minPeriodicity]. */
        const val MIN_PERIODICITY = 0.4f
        /** The tracked period is kept while it correlates at least this much
         * of the current best lag — the shimmer's rate stays locked to the
         * voice instead of flapping with the autocorr's broad peak. */
        private const val TRACKED_LAG_KEEP = 0.7f
        /** Below this autocorrelation the whole band peak is noise; the
         * tracked period is never re-picked on it (aligned with the shipped
         * periodicity gate). */
        private const val TRACKED_SWITCH_FLOOR = 0.4f
        /** At acquisition the tracked period is extended to the slowest lag
         * correlating at least this much of the best — tarjīʿ is the slow
         * swell, so a period tied with its double starts on the swell. */
        private const val OCTAVE_START_KEEP = 0.85f
        /** Out-of-band short peak must be this strong vs the in-band pick to
         * count as a ceiling cheat (see harmonicLeak). */
        private const val HARMONIC_OF_BEST = 0.85f
        // Band floor as an envelope-hop lag (20 ms hops): 1.5 Hz → 33.
        private const val MAX_ENV_LAG = 33
        private const val TREMOLO_EMA = 0.35f
        /** Analysis + smoothing lag the phase lead compensates (~45 ms). */
        private const val LAG_SEC = 0.045f
        /** Shipped attack of [tremoloGain] — Ink Lab: [attackMs]. */
        const val ATTACK_MS = 250f
        /** Shipped release of [tremoloGain] (mid-hold lull bridge) — Ink Lab:
         * [releaseMs]. */
        const val RELEASE_MS = 800f
        /** Release once the hold's climax is over (ms) — a near-instant dry,
         * so the shimmer never flickers into the tail or the next word. */
        const val CLIMAX_RELEASE_MS = 60f
        /** Read-out history for the output-latency delay (~1.3 s). */
        private const val HIST_HOPS = 64
        /** Sonic resampler's own buffer, in *content* ms, when speed ≠ 1
         * (it is bypassed at 1×). Its delay does not scale with speed. */
        const val SONIC_LATENCY_MS = 20f
        /** Climax-level EMA time constant (ms): fast enough to catch the
         * release onset (~100 ms after the voice drops), slow enough that
         * the vibrato's own troughs never reach the gate. */
        private const val CLIMAX_EMA_MS = 40f
        private const val CLIMAX_EMA = 0.39f // 1 − exp(−20/40)
        /** Envelope level, as a fraction of the hold's peak, below which the
         * climactic reverberation is over and the effect stops — the strong
         * swell's end, not the release's last gasp. Sits above the vibrato's
         * smoothed troughs (~0.57×peak at 30% depth) so a deep hold never
         * trips its own gate. */
        private const val CLIMAX_OFF = 0.52f
        /** Envelope level (fraction of the hold's peak) at which the shimmer
         * is at full strength; between [CLIMAX_FULL] and [CLIMAX_OFF] it
         * fades with the voice, so the word's end reads as the effect
         * settling rather than pulsing at full strength past the climax. */
        private const val CLIMAX_FULL = 0.75f
        /** Consecutive hops below [CLIMAX_OFF] before the climax is declared
         * over. Four hops reject a real vibrato trough; once reached, the end
         * stays latched until a genuinely new held note. */
        private const val CLIMAX_PERSIST = 4

        /**
         * Read-out delay in content hops that lands the reported signal on
         * the clock the reader trusts — the same one the highlight uses.
         * Pure, so the sync contract is unit-testable.
         *
         * The PCM tap sits at the sink *input*: downstream of it are the
         * sink's own AudioTrack buffer (wall-time, [sinkMs]) and then the
         * playback head that `positionMs` tracks. Delaying by the buffer
         * lands the shimmer on that head — in lockstep with the word ink —
         * and [routeMs] (the preset the highlight clock subtracts) carries
         * the Bluetooth share. The output path *after* the track
         * ([downstreamMs]) is left out by default: the highlight does not
         * include it either, so the shimmer rides the same reference.
         * Wall-time components scale with [speed]; the Sonic buffer
         * ([sonicContentMs]) is content-time and does not.
         */
        fun earDelayHops(
            routeMs: Long,
            sinkMs: Long,
            speed: Float,
            downstreamMs: Long = 0,
            sonicContentMs: Float = 0f,
        ): Int {
            val wallMs = (routeMs + sinkMs + downstreamMs).coerceAtLeast(0L)
            val wallHops = (wallMs * speed / HOP_MS).toInt()
            return (wallHops + (sonicContentMs / HOP_MS).toInt()).coerceIn(0, HIST_HOPS - 1)
        }
    }
}
