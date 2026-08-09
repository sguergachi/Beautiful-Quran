package com.beautifulquran.playback

import kotlin.math.abs
import kotlin.math.ceil
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
 * below half the detected event's peak (the voice releases), the
 * reverberation is over and the effect stops — a fast dry-down, no flicker
 * through the decaying tail.
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
     * stream rate so one hop stays at [HOP_MS] of *content* after integer
     * decimation (44.1 kHz → 8820 Hz → 176 samples). The pitch lag range and
     * reported frequency scale with this effective analysis rate instead of
     * assuming every stream landed at exactly 8 kHz.
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
    // hop). [hopSamples] keeps a hop at approximately 20 ms of content.
    private var frame = FloatArray(FRAME_SAMPLES)
    private var work = FloatArray(FRAME_SAMPLES)
    private var analysisSampleRate = SAMPLE_RATE
    private var minPitchLag = SAMPLE_RATE / MAX_PITCH_HZ
    private var maxPitchLag = SAMPLE_RATE / MIN_PITCH_HZ
    private var corrs = FloatArray(maxPitchLag + 1)
    private var frameFill = 0

    // Per-hop envelope RMS series (64 hops ≈ 1.3 s).
    private val env = FloatArray(ENV_HOPS)
    private val envCorr = FloatArray(MAX_ENV_LAG + 2)
    private val envRawCorr = FloatArray(MAX_ENV_LAG + 2)
    private val envResidual = FloatArray(ENV_HOPS)
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
    private var eventPeak = 0f
    private var endOfHold = false
    private var climaxUnder = 0
    private var pulseUnder = 0
    private var steadyGap = 0
    private var eventRateHz = 0f
    private var levelTransitionGrace = 0

    /** Consume [length] mono samples at the decimated rate (≈8 kHz). Called
     * on the audio thread. */
    fun onSamples8k(samples: FloatArray, length: Int = samples.size) {
        val hop = hopSamples
        if (frame.size != hop * FRAME_HOPS) {
            // Source rate changed: one hop must still be exactly HOP_MS of
            // content. Restart the frame so the hop boundary stays aligned.
            frame = FloatArray(hop * FRAME_HOPS)
            work = FloatArray(hop * FRAME_HOPS)
            analysisSampleRate = hop * 1000 / HOP_MS
            minPitchLag = (analysisSampleRate / MAX_PITCH_HZ).coerceAtLeast(1)
            maxPitchLag = (analysisSampleRate / MIN_PITCH_HZ)
                .coerceIn(minPitchLag, frame.size - 1)
            corrs = FloatArray(maxPitchLag + 1)
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
        eventPeak = 0f
        endOfHold = false
        climaxUnder = 0
        pulseUnder = 0
        steadyGap = 0
        eventRateHz = 0f
        levelTransitionGrace = 0
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
                eventPeak = 0f
                climaxUnder = 0
                pulseUnder = 0
                steadyGap = 0
                eventRateHz = 0f
                levelTransitionGrace = 0
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

        updateTremolo()

        // The shimmer settles with the voice: its strength follows the
        // envelope's remaining intensity, full while the swell is strong
        // (≥ [CLIMAX_FULL] of the event's peak) and fading as the voice
        // dies toward the climax gate — the end of the word reads as the
        // effect drying, never as a full-strength pulse after the climax.
        val target = if (reverberating) {
            val level = if (eventPeak > 0f) climaxLevel / eventPeak else 1f
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
    private fun updateTremolo() {
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

        // A sharp loudness step contains enough curved residual energy to
        // resemble a fast pulse while the rolling window straddles both
        // levels. Do not let that transition consume the word's one event;
        // wait until the window's leading/trailing quarters describe the
        // same sustained level. Real AM remains balanced around its mean.
        val edgeSize = maxOf(1, n / 4)
        var leadingLevel = 0f
        var trailingLevel = 0f
        for (j in 0 until edgeSize) {
            leadingLevel += env[(start + j) % ENV_HOPS]
            trailingLevel += env[(start + n - edgeSize + j) % ENV_HOPS]
        }
        leadingLevel /= edgeSize
        trailingLevel /= edgeSize
        val acquisitionLevelBalance =
            minOf(leadingLevel, trailingLevel) / maxOf(leadingLevel, trailingLevel)

        // Remove the local level trend before measuring modulation. Merely
        // subtracting the mean makes a crescendo highly autocorrelated and
        // can report a plain rising note as a fast pulse.
        val centre = (n - 1) * 0.5f
        var slopeNumerator = 0f
        var slopeDenominator = 0f
        for (j in 0 until n) {
            val x = j - centre
            slopeNumerator += x * (env[(start + j) % ENV_HOPS] - mean)
            slopeDenominator += x * x
        }
        val slope = slopeNumerator / slopeDenominator.coerceAtLeast(1f)
        var sumSq = 0f
        for (j in 0 until n) {
            val residual = env[(start + j) % ENV_HOPS] - (mean + slope * (j - centre))
            envResidual[j] = residual
            sumSq += residual * residual
        }
        val amp = sqrt(2f * sumSq / n) // sine-amplitude estimate
        val depth = amp / mean

        val minHz = minTremoloHz.coerceIn(MIN_TREMOLO_HZ, MAX_MEASURABLE_TREMOLO_HZ)
        val maxHz = maxTremoloHz.coerceIn(minHz, MAX_MEASURABLE_TREMOLO_HZ)
        val minLag = ceil(1000f / (maxHz * HOP_MS)).toInt()
            .coerceIn(2, MAX_ENV_LAG)
        val maxBandLag = (1000f / (minHz * HOP_MS)).toInt()
            .coerceIn(minLag, MAX_ENV_LAG)
        val crossingThreshold = amp * 0.1f
        var previousSign = 0
        var crossings = 0
        for (j in 0 until n) {
            val sign = when {
                envResidual[j] > crossingThreshold -> 1
                envResidual[j] < -crossingThreshold -> -1
                else -> 0
            }
            if (sign != 0) {
                if (previousSign != 0 && sign != previousSign) crossings++
                previousSign = sign
            }
        }
        val crossingRateHz = crossings * 1000f / (2f * (n - 1) * HOP_MS)
        val oscillatory = crossings >= 2 &&
            crossingRateHz in (minHz - 0.5f)..(maxHz + 1f)

        // Periodicity of the detrended envelope: autocorrelation over the
        // tarjīʿ band (1.5 Hz … maxTremoloHz). Alternating residual crossings
        // first prove that the shape actually oscillates; correlation then
        // measures its period without mistaking a crescendo for a pulse.
        // One extra lag is evaluated for the above-ceiling harmonic guard.
        val maxComputedLag = minOf(MAX_ENV_LAG + 1, n - MIN_CORR_PAIRS)
        for (lag in 2..maxComputedLag) {
            var corr = 0f
            var e0 = 0f
            var eL = 0f
            var rawCorr = 0f
            var rawE0 = 0f
            var rawEL = 0f
            for (j in 0 until n - lag) {
                val a = envResidual[j]
                val b = envResidual[j + lag]
                corr += a * b
                e0 += a * a
                eL += b * b
                val rawA = env[(start + j) % ENV_HOPS] - mean
                val rawB = env[(start + j + lag) % ENV_HOPS] - mean
                rawCorr += rawA * rawB
                rawE0 += rawA * rawA
                rawEL += rawB * rawB
            }
            envCorr[lag] = corr / (sqrt(e0 * eL) + 1e-9f)
            envRawCorr[lag] = rawCorr / (sqrt(rawE0 * rawEL) + 1e-9f)
        }

        val candidateMax = minOf(maxBandLag, maxComputedLag)
        var bandBestC = 0f
        var rawBandBestC = 0f
        var bestC = 0f
        var bestLag = 0
        if (candidateMax >= minLag) {
            for (lag in minLag..candidateMax) {
                val c = envCorr[lag]
                bandBestC = maxOf(bandBestC, c)
                rawBandBestC = maxOf(rawBandBestC, envRawCorr[lag])
                if (c > bestC) {
                    bestC = c
                    bestLag = lag
                }
            }
        }
        if (!oscillatory) bestLag = 0

        val acquiring = trackedLag !in minLag..maxBandLag
        trackedLag = when {
            acquiring -> bestLag
            bestLag == 0 -> trackedLag
            bestC < TRACKED_SWITCH_FLOOR -> trackedLag
            envCorr[trackedLag] >= TRACKED_LAG_KEEP * bestC -> trackedLag
            else -> bestLag
        }
        val rateHz =
            if (trackedLag > 0) 1000f / (trackedLag * HOP_MS.toFloat()) else 0f
        lastRateHz = rateHz

        // Keep the signal smoothing even while gated off, so a lock-on starts
        // from the live envelope rather than a stale frozen value.
        val raw = if (amp > 1e-6f) {
            (envResidual[n - 1] / amp).coerceIn(-1.5f, 1.5f)
        } else {
            0f
        }
        val prev = tremoloSmoothed
        tremoloSmoothed += TREMOLO_EMA * (raw - tremoloSmoothed)

        val depthGate = minTremoloDepth.coerceAtLeast(0f)
        val periodGate = minPeriodicity.coerceIn(0.05f, 1f)
        val longEnough = holdMs >= holdMinMs.coerceAtLeast(0f)

        // Ceiling-cheat guard: only current, local peaks above the configured
        // ceiling may veto an in-band period. This also keeps a new note from
        // inheriting stale correlation bins from the preceding note.
        val cheatLo = (1000f / (2f * maxHz * HOP_MS)).toInt() + 1
        val cheatHi = minOf(minLag - 1, maxComputedLag - 1)
        var shortPeakLag = 0
        var shortPeakC = 0f
        if (cheatHi >= cheatLo) {
            for (lag in maxOf(2, cheatLo)..cheatHi) {
                val c = envCorr[lag]
                val localPeak = c >= envCorr[lag - 1] && c >= envCorr[lag + 1]
                if (localPeak && c > shortPeakC) {
                    shortPeakC = c
                    shortPeakLag = lag
                }
            }
        }
        val harmonicLeak = shortPeakLag >= 2 && trackedLag > 0 &&
            trackedLag % shortPeakLag == 0 && trackedLag > shortPeakLag &&
            trackedLag <= 3 * shortPeakLag &&
            shortPeakC >= HARMONIC_OF_BEST * bandBestC
        val coherent = trackedLag > 0 && oscillatory && bandBestC >= periodGate && !harmonicLeak &&
            rateHz in minHz..maxHz
        val lifecycleCoherent = trackedLag > 0 && oscillatory && rawBandBestC >= periodGate &&
            !harmonicLeak && rateHz in minHz..maxHz
        // Deep AM may bridge an irregular climax only after a genuine period
        // has been acquired. Depth alone can never turn a trend into tarjīʿ.
        val deep = trackedLag > 0 && oscillatory && depth >= DEEP_DEPTH_GATE &&
            rateHz in minHz..maxHz && !harmonicLeak
        val periodic = coherent || deep
        val stillPeriodic = (trackedLag > 0 && oscillatory &&
            bandBestC >= periodGate * 0.7f &&
            !harmonicLeak && rateHz in (minHz - 0.5f)..(maxHz + 1f)) ||
            (trackedLag > 0 && oscillatory &&
                depth >= DEEP_DEPTH_GATE * DEPTH_OFF_RATIO &&
                !harmonicLeak && rateHz in (minHz - 0.5f)..(maxHz + 1f))

        val gapBefore = steadyGap
        steadyGap = when {
            coherent -> 0
            depth < DEEP_DEPTH_GATE * DEPTH_OFF_RATIO -> steadyGap + 1
            steadyGap >= NEW_EVENT_GAP_PERSIST -> steadyGap
            else -> 0
        }
        if (endOfHold && coherent && gapBefore >= NEW_EVENT_GAP_PERSIST) {
            // A distinct oscillatory event may recur on the same pitch. Pitch
            // continuity is not event identity (connected words often share it).
            endOfHold = false
            eventPeak = 0f
            climaxUnder = 0
            pulseUnder = 0
            eventRateHz = 0f
            levelTransitionGrace = 0
        }

        if (eventPeak > 0f) eventPeak = maxOf(climaxLevel, eventPeak)
        val under = eventPeak > 0f && climaxLevel < CLIMAX_OFF * eventPeak
        climaxUnder = if (under) climaxUnder + 1 else 0
        var climaxOver = climaxUnder >= CLIMAX_PERSIST
        val next = if (reverberating) {
            longEnough && depth >= depthGate * DEPTH_OFF_RATIO && stillPeriodic &&
                !endOfHold
        } else {
            longEnough && acquisitionLevelBalance >= ACQUISITION_LEVEL_BALANCE &&
                depth >= depthGate && periodic && !endOfHold
        }
        val rateRatio = if (eventRateHz > 0f && rateHz > 0f) {
            maxOf(eventRateHz / rateHz, rateHz / eventRateHz)
        } else {
            1f
        }
        if (reverberating && rateRatio <= MAX_EVENT_RATE_RATIO) {
            eventRateHz += EVENT_RATE_EMA * (rateHz - eventRateHz)
        }
        val cadenceBridge = eventRateHz > 0f && rateRatio <= MAX_EVENT_RATE_RATIO &&
            depth >= depthGate * DEPTH_OFF_RATIO
        if (climaxOver && cadenceBridge) {
            // Same cadence at a new stable level is continued tarjīʿ, not a
            // release. Re-normalize without an off/on edge. A cadence jump,
            // such as Hani 1:7 entering the later letters, still ends.
            eventPeak = climaxLevel
            climaxUnder = 0
            climaxOver = false
            // The rolling envelope briefly contains both loudness levels,
            // which can obscure an otherwise unchanged pulse. Give it only
            // long enough to replace that mixed analysis window.
            levelTransitionGrace = LEVEL_TRANSITION_GRACE_HOPS
        }
        val evidenceReadyMs =
            (maxBandLag + 1 + MIN_CORR_PAIRS) * HOP_MS.toFloat()
        pulseUnder = when {
            holdMs < evidenceReadyMs || lifecycleCoherent || levelTransitionGrace > 0 ||
                (cadenceBridge && climaxUnder > 0) -> 0
            eventPeak > 0f || next -> pulseUnder + 1
            else -> 0
        }
        if (levelTransitionGrace > 0) levelTransitionGrace--
        if (climaxOver || pulseUnder >= PULSE_GAP_PERSIST) {
            endOfHold = true
        }
        reverberating = next && !endOfHold
        if (eventPeak == 0f && reverberating) {
            // A reverberant room can make the consonant attack much louder
            // than the sustained voice (Hani 1:7). The climax belongs to the
            // detected sustain, not that pre-event echo peak.
            eventPeak = climaxLevel
            eventRateHz = rateHz
            climaxUnder = 0
        }

        // Lead the measured oscillation by the analysis+smoothing lag, so the
        // shimmer swells *with* the voice rather than trailing it: for a
        // near-sinusoid at the measured rate, s(t+τ) ≈ s·cos ωτ + ṡ·sin ωτ / ω.
        tremolo = if (reverberating) {
            val omega = 2f * Math.PI.toFloat() * rateHz
            val dS = (tremoloSmoothed - prev) * (1000f / HOP_MS)
            // Keep the compensation as a fixed time lead. Capping this angle
            // shortens the lead as cadence rises and reverses the upper band.
            val wt = omega * LAG_SEC
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
        for (j in 0 until frame.size - maxPitchLag) energy += work[j] * work[j]
        if (energy <= 1e-8f) return 0f to 0f
        var best = 0f
        for (lag in minPitchLag..maxPitchLag) {
            var corr = 0f
            for (j in 0 until frame.size - maxPitchLag) corr += work[j] * work[j + lag]
            corrs[lag] = corr
            if (corr > best) best = corr
        }
        if (best <= 0f) return 0f to 0f
        // Octave-stabilize: harmonics make lag multiples score near-identically,
        // and picking the plain max flips the estimate between L and 2L hop to
        // hop (which kept resetting the hold on real recitation). Take the
        // shortest period within 5% of the best instead.
        var lag = minPitchLag
        while (lag <= maxPitchLag && corrs[lag] < 0.95f * best) lag++
        if (lag > maxPitchLag) return 0f to 0f
        return (analysisSampleRate / lag.toFloat()) to (best / energy)
    }

    companion object {
        const val SAMPLE_RATE = 8_000
        const val HOP_MS = 20
        const val HOP_SAMPLES = SAMPLE_RATE * HOP_MS / 1000 // 160
        const val FRAME_SAMPLES = HOP_SAMPLES * 4 // 80 ms
        private const val FRAME_HOPS = 4
        private const val ENV_HOPS = 64
        private const val MIN_ENV_HOPS = 20
        private const val MIN_CORR_PAIRS = MIN_ENV_HOPS / 2

        /** Hold must survive this long before reverberation is considered.
         * Kept short so the shimmer starts its build-up with the hold. */
        const val HOLD_MIN_MS = 300

        // Reciter vocal range ~70–350 Hz (covers playback-speed shifts).
        private const val MIN_PITCH_HZ = 70
        private const val MAX_PITCH_HZ = 350
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
        /** A window whose edge levels differ by nearly 3× is a level
         * transition, not enough evidence to spend the word's event. */
        private const val ACQUISITION_LEVEL_BALANCE = 0.35f

        /** Tarjīʿ lives around 1.5–10 Hz of envelope oscillation: slow ~2 Hz
         * swells (Hani) to ~6–8 Hz vibrato (Alafasy), at any hop rate.
         * Ink Lab: [minTremoloHz] / [maxTremoloHz]. */
        const val MIN_TREMOLO_HZ = 1.5f
        const val MAX_TREMOLO_HZ = 10f
        /** Phase-safe ceiling of the rolling 80 ms RMS envelope. Above this,
         * its first 12.5 Hz null attenuates then inverts the vocal pulse. */
        const val MAX_MEASURABLE_TREMOLO_HZ = MAX_TREMOLO_HZ
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
        /** Envelope level, as a fraction of the detected event's peak, below
         * which the climactic reverberation is over and the effect stops —
         * the strong swell's end, not the release's last gasp. Sits above the
         * vibrato's smoothed troughs (~0.57×peak at 30% depth) so a deep hold
         * never trips its own gate. */
        private const val CLIMAX_OFF = 0.52f
        /** Envelope level (fraction of the detected event's peak) at which
         * the shimmer is at full strength; between [CLIMAX_FULL] and
         * [CLIMAX_OFF] it fades with the voice, so the word's end reads as
         * the effect settling rather than pulsing at full strength past the
         * climax. */
        private const val CLIMAX_FULL = 0.75f
        /** A sudden cadence change is a new articulation, not the same hold. */
        private const val MAX_EVENT_RATE_RATIO = 3f
        private const val EVENT_RATE_EMA = 0.1f
        /** A falling envelope must outlast the adaptive peak before it is a
         * release. A stable quieter pulse re-normalizes inside this window. */
        private const val CLIMAX_PERSIST = 24
        /** Maximum time for the rolling analysis window to forget a stable
         * level change after cadence continuity has validated it. */
        private const val LEVEL_TRANSITION_GRACE_HOPS = MAX_ENV_LAG + MIN_CORR_PAIRS
        /** Deep modulation may bridge a brief irregular climax, but it cannot
         * replace coherent pulse evidence across later letters of the hold. */
        private const val PULSE_GAP_PERSIST = 10
        /** Steady-envelope separation before the same pitch may start a new event. */
        private const val NEW_EVENT_GAP_PERSIST = PULSE_GAP_PERSIST

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
