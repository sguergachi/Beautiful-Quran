package com.beautifulquran.playback

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
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
 * enough, its intensity and fundamental-frequency tracks are scanned for an
 * oscillation in the tarjīʿ band (~1.5–10 Hz). Either amplitude tremolo or
 * pitch vibrato may open [reverberating]. [tremolo] exposes the live 20 ms
 * modulation itself, zero-centred and in phase with the voice, so the glint
 * rides the exact reverberation the listener hears. [tremoloGain] ramps the
 * effect in and out so neither detection edge ever pops.
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

    /** Analysis-hop at which the current acoustic event was acquired. */
    var eventStartHop = -1
        private set

    /** The reverberation itself: zero-centred, ~−1..1, phase-locked to the
     * voice. Meaningful only while [reverberating]. */
    var tremolo = 0f
        private set

    /** Attack/release envelope (0..1) on the whole effect — no pops at the
     * detection edges. While an event builds, it tracks the swell: the first
     * pulses of the hold are soft and reach full depth only near the crest. */
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
     * (wall-time route × playback speed + content-time tap backlog + the
     * Sonic resampler's own content-time buffer at non-1× speed) by
     * [VoiceEnergy]: the PCM tap hears the voice *before* the listener does,
     * so the reported signal is delayed to match what is actually reaching
     * the ear right now.
     */
    var delayHops = 0f

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
    private val histEventStartHop = IntArray(HIST_HOPS)
    private var histCount = 0

    /** Tarjīʿ state as it reaches the ear now (delayed by [delayHops]). */
    val syncReverberating: Boolean get() = delayed(histRev) >= 0.5f
    val syncTremolo: Float get() = delayed(histTremolo)
    val syncTremoloGain: Float get() = delayed(histGain)

    /** Event identity on the same delayed clock as [syncReverberating]. */
    val syncEventStartHop: Int
        get() {
            if (!syncReverberating || histCount == 0) return -1
            val position = delayedPosition() ?: return -1
            val before = floor(position).toInt()
            val after = minOf(before + 1, histCount - 1)
            val beforeIndex = before % HIST_HOPS
            val afterIndex = after % HIST_HOPS
            return when {
                histRev[beforeIndex] >= 0.5f -> histEventStartHop[beforeIndex]
                histRev[afterIndex] >= 0.5f -> histEventStartHop[afterIndex]
                else -> -1
            }
        }

    /** Linear read-out between analysis hops keeps device latency from being
     * quantized up to 20 ms early. Detection itself remains hop-based. */
    private fun delayed(history: FloatArray): Float {
        val position = delayedPosition() ?: return 0f
        val before = floor(position).toInt()
        val after = minOf(before + 1, histCount - 1)
        val fraction = position - before
        val a = history[before % HIST_HOPS]
        return a + fraction * (history[after % HIST_HOPS] - a)
    }

    private fun delayedPosition(): Float? {
        if (histCount == 0) return null
        val newest = histCount - 1
        val oldest = maxOf(0, histCount - HIST_HOPS)
        return (newest - delayHops.coerceAtLeast(0f))
            .coerceAtLeast(oldest.toFloat())
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

    // Long-window RMS and per-hop pitch series (64 hops ≈ 1.3 s). The 80 ms
    // envelope rejects consonant texture during detection; [latestHopRms]
    // drives the visual phase directly once that evidence has opened it.
    private val env = FloatArray(ENV_HOPS)
    private val envCorr = FloatArray(MAX_ENV_LAG + 2)
    private val envRawCorr = FloatArray(MAX_ENV_LAG + 2)
    private val envResidual = FloatArray(ENV_HOPS)
    private val pitchEnv = FloatArray(ENV_HOPS)
    private val pitchCorr = FloatArray(MAX_ENV_LAG + 2)
    private val pitchResidual = FloatArray(ENV_HOPS)
    private val amScan = ModulationScan()
    private val fmScan = ModulationScan()
    private var envCount = 0

    private var holdPitchHz = 0f
    private var holdStartEnvCount = 0
    private var misses = 0
    private var peak = 0f
    private var latestHopRms = 0f
    private var modulationPitchHz = 0f
    private var modulationClarity = 0f
    private var modulationPitchLeadHops = 0f
    private var trackedLag = 0
    private var trackedPitchLag = 0
    private var visualUsesAmplitude = false

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

    // Swell tracker: tarjīʿ is a *build* — the shimmer must arrive soft at
    // the start of the hold and reach full depth only as the swell
    // approaches the event's peak, never as a full-strength pulse from the
    // first detected hop. [eventHops] counts hops since the event was
    // acquired; the gain target ramps over [SWELL_RAMP_HOPS] from it, so a
    // steady hold and a crescendo both ease in instead of blinking on.
    private var eventHops = 0

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
        eventStartHop = -1
        tremolo = 0f
        tremoloGain = 0f
        holdMs = 0f
        holdPitchHz = 0f
        misses = 0
        peak = 0f
        latestHopRms = 0f
        modulationPitchHz = 0f
        modulationClarity = 0f
        modulationPitchLeadHops = 0f
        climaxLevel = 0f
        eventPeak = 0f
        endOfHold = false
        climaxUnder = 0
        pulseUnder = 0
        steadyGap = 0
        eventRateHz = 0f
        levelTransitionGrace = 0
        eventHops = 0
        trackedLag = 0
        trackedPitchLag = 0
        visualUsesAmplitude = false
        amScan.clear()
        fmScan.clear()
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
        var hopSumSq = 0f
        for (j in frame.size - hopSamples until frame.size) {
            hopSumSq += work[j] * work[j]
        }
        val hopRms = sqrt(hopSumSq / hopSamples)
        latestHopRms = hopRms
        peak = maxOf(rms, peak * PEAK_DECAY)
        val floor = maxOf(MIN_FLOOR, FLOOR_OF_PEAK * peak)

        env[envCount % ENV_HOPS] = rms
        envCount++
        climaxLevel += CLIMAX_EMA * (rms - climaxLevel)

        val (pitchHz, clarity) = holdPitch()
        updateModulationPitch()
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
                eventHops = 0
                trackedLag = 0
                trackedPitchLag = 0
            }
            misses = 0
        } else if (++misses > MAX_MISSES) {
            holdMs = 0f
            holdPitchHz = 0f
            endOfHold = true
            trackedLag = 0
            trackedPitchLag = 0
        }

        val pitchIndex = (envCount - 1) % ENV_HOPS
        pitchEnv[pitchIndex] = when {
            voiced && modulationClarity >= MIN_CLARITY && holdPitchHz > 0f ->
                foldPitch(modulationPitchHz, holdPitchHz)
            envCount > 1 -> pitchEnv[(envCount - 2) % ENV_HOPS]
            else -> 0f
        }

        val wasReverberating = reverberating
        updateTremolo()
        if (!wasReverberating && reverberating) eventStartHop = hopCount

        // The shimmer builds with the swell and settles with the voice. Its
        // strength ramps in over the event's own build ([SWELL_RAMP_HOPS]) —
        // the first pulses of a waqf hold are soft and full on/off depth is
        // reached only as the swell approaches its crest, never a harsh blink
        // from the first detected hop. It then rides the sustain and fades as
        // the voice dies toward the climax gate ([CLIMAX_FULL] → [CLIMAX_OFF]
        // of the event's peak), so the word's end reads as the effect drying,
        // never as a full-strength pulse past the climax.
        val target = if (reverberating) {
            eventHops++
            val ramp = (eventHops.toFloat() / SWELL_RAMP_HOPS).coerceIn(0f, 1f)
            val level = if (eventPeak > 0f) climaxLevel / eventPeak else 1f
            ramp * ((level - CLIMAX_OFF) / (CLIMAX_FULL - CLIMAX_OFF)).coerceIn(0f, 1f)
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
        histEventStartHop[histCount % HIST_HOPS] =
            if (reverberating) eventStartHop else -1
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
        val minHz = minTremoloHz.coerceIn(MIN_TREMOLO_HZ, MAX_MEASURABLE_TREMOLO_HZ)
        val maxHz = maxTremoloHz.coerceIn(minHz, MAX_MEASURABLE_TREMOLO_HZ)
        val minLag = ceil(1000f / (maxHz * HOP_MS)).toInt()
            .coerceIn(2, MAX_ENV_LAG)
        val maxBandLag = (1000f / (minHz * HOP_MS)).toInt()
            .coerceIn(minLag, MAX_ENV_LAG)
        val depthGate = minTremoloDepth.coerceAtLeast(0f)
        val periodGate = minPeriodicity.coerceIn(0.05f, 1f)
        val longEnough = holdMs >= holdMinMs.coerceAtLeast(0f)

        scanModulation(
            values = env,
            residuals = envResidual,
            correlations = envCorr,
            rawCorrelations = envRawCorr,
            n = n,
            start = start,
            minLag = minLag,
            maxBandLag = maxBandLag,
            minHz = minHz,
            maxHz = maxHz,
            periodGate = periodGate,
            previousLag = trackedLag,
            liveValue = latestHopRms,
            liveOffsetHops = RMS_PHASE_LEAD_HOPS,
            deepGate = DEEP_DEPTH_GATE,
            result = amScan,
        )
        trackedLag = amScan.trackedLag
        if (!amScan.valid) {
            reverberating = false
            endOfHold = true
            return
        }
        val latestPitch = pitchEnv[(start + n - 1) % ENV_HOPS]
        val previousPitch = pitchEnv[(start + n - 2) % ENV_HOPS]
        val beforePreviousPitch = pitchEnv[(start + n - 3) % ENV_HOPS]
        scanModulation(
            values = pitchEnv,
            residuals = pitchResidual,
            correlations = pitchCorr,
            n = n,
            start = start,
            minLag = minLag,
            maxBandLag = maxBandLag,
            minHz = minHz,
            maxHz = maxHz,
            periodGate = periodGate,
            previousLag = trackedPitchLag,
            liveValue = projectForward(
                latestPitch,
                previousPitch,
                beforePreviousPitch,
                modulationPitchLeadHops,
            ),
            liveOffsetHops = modulationPitchLeadHops,
            result = fmScan,
        )
        trackedPitchLag = fmScan.trackedLag
        val amOpen = amScan.depth >= depthGate && amScan.periodic
        val amKeep = amScan.depth >= depthGate * DEPTH_OFF_RATIO && amScan.stillPeriodic
        val fmOpen = fmScan.depth >= MIN_PITCH_DEPTH && fmScan.periodic
        val fmKeep = fmScan.depth >= MIN_PITCH_DEPTH * DEPTH_OFF_RATIO && fmScan.stillPeriodic
        // A level step can forge AM residuals, but it cannot forge coherent
        // YIN pitch motion. Keep that AM-only guard out of FM acquisition and
        // phase selection.
        val amSafe = amOpen && amScan.levelBalance >= ACQUISITION_LEVEL_BALANCE
        // Prefer audible intensity when both forms acquire together. Keep
        // that polarity for the event, however: hopping between AM and FM
        // near a depth threshold would create a visible one-frame phase jump.
        // Fall back only if the chosen form itself loses coherence.
        visualUsesAmplitude = when {
            !reverberating -> amSafe || !fmOpen
            visualUsesAmplitude && !amKeep && fmKeep -> false
            !visualUsesAmplitude && !fmKeep && amKeep -> true
            else -> visualUsesAmplitude
        }
        val rateHz = if (visualUsesAmplitude) amScan.rateHz else fmScan.rateHz
        val liveModulation = if (visualUsesAmplitude) amScan.raw else fmScan.raw
        lastRateHz = rateHz
        val anyCoherent = amScan.coherent || fmScan.coherent
        val lifecycleCoherent = amScan.lifecycleCoherent || fmScan.coherent

        val gapBefore = steadyGap
        steadyGap = when {
            anyCoherent -> 0
            !amKeep && !fmKeep -> steadyGap + 1
            steadyGap >= NEW_EVENT_GAP_PERSIST -> steadyGap
            else -> 0
        }
        if (endOfHold && anyCoherent && gapBefore >= NEW_EVENT_GAP_PERSIST) {
            // A distinct oscillatory event may recur on the same pitch. Pitch
            // continuity is not event identity (connected words often share it).
            endOfHold = false
            eventPeak = 0f
            climaxUnder = 0
            pulseUnder = 0
            eventRateHz = 0f
            levelTransitionGrace = 0
            eventHops = 0
        }

        if (eventPeak > 0f) eventPeak = maxOf(climaxLevel, eventPeak)
        val under = eventPeak > 0f && climaxLevel < CLIMAX_OFF * eventPeak
        climaxUnder = if (under) climaxUnder + 1 else 0
        var climaxOver = climaxUnder >= CLIMAX_PERSIST
        val acquisitionOpen = amSafe || fmOpen
        val next = if (reverberating) {
            longEnough && (amKeep || fmKeep) && !endOfHold
        } else {
            longEnough && acquisitionOpen && !endOfHold
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
            (amKeep || fmKeep)
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
            eventHops = 0
        }

        // The long track decides the event and its slow baseline, but the
        // live sample supplies the visible phase. Its timestamp comes from
        // actual analysis support, never the noisy modulation-rate estimate.
        tremolo = if (reverberating) liveModulation else {
            // Not reverberating: fade the stored signal with the gain so the
            // release dries to still ink instead of pulsing on tail noise.
            liveModulation * tremoloGain
        }
    }

    private class ModulationScan {
        var valid = false
        var rateHz = 0f
        var depth = 0f
        var raw = 0f
        var coherent = false
        var lifecycleCoherent = false
        var periodic = false
        var stillPeriodic = false
        var levelBalance = 0f
        var trackedLag = 0

        fun clear() {
            valid = false
            rateHz = 0f
            depth = 0f
            raw = 0f
            coherent = false
            lifecycleCoherent = false
            periodic = false
            stillPeriodic = false
            levelBalance = 0f
            trackedLag = 0
        }
    }

    /** Shared long-window evidence scan for intensity and F0 modulation. */
    private fun scanModulation(
        values: FloatArray,
        residuals: FloatArray,
        correlations: FloatArray,
        rawCorrelations: FloatArray? = null,
        n: Int,
        start: Int,
        minLag: Int,
        maxBandLag: Int,
        minHz: Float,
        maxHz: Float,
        periodGate: Float,
        previousLag: Int,
        liveValue: Float,
        liveOffsetHops: Float,
        deepGate: Float? = null,
        result: ModulationScan,
    ) {
        result.clear()
        var mean = 0f
        for (j in 0 until n) mean += values[(start + j) % ENV_HOPS]
        mean /= n
        if (mean <= 0f) return

        val edgeSize = maxOf(1, n / 4)
        var leadingLevel = 0f
        var trailingLevel = 0f
        for (j in 0 until edgeSize) {
            leadingLevel += values[(start + j) % ENV_HOPS]
            trailingLevel += values[(start + n - edgeSize + j) % ENV_HOPS]
        }
        val levelBalance = minOf(leadingLevel, trailingLevel) /
            maxOf(leadingLevel, trailingLevel)

        val centre = (n - 1) * 0.5f
        var slopeNumerator = 0f
        var slopeDenominator = 0f
        for (j in 0 until n) {
            val x = j - centre
            slopeNumerator += x * (values[(start + j) % ENV_HOPS] - mean)
            slopeDenominator += x * x
        }
        val slope = slopeNumerator / slopeDenominator.coerceAtLeast(1f)
        var sumSq = 0f
        for (j in 0 until n) {
            val residual = values[(start + j) % ENV_HOPS] -
                (mean + slope * (j - centre))
            residuals[j] = residual
            sumSq += residual * residual
        }
        val amp = sqrt(2f * sumSq / n)
        val depth = amp / mean
        val crossingThreshold = amp * 0.1f
        var previousSign = 0
        var crossings = 0
        for (j in 0 until n) {
            val sign = when {
                residuals[j] > crossingThreshold -> 1
                residuals[j] < -crossingThreshold -> -1
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

        val maxComputedLag = minOf(MAX_ENV_LAG + 1, n - MIN_CORR_PAIRS)
        for (lag in 2..maxComputedLag) {
            var corr = 0f
            var e0 = 0f
            var eL = 0f
            var rawCorr = 0f
            var rawE0 = 0f
            var rawEL = 0f
            for (j in 0 until n - lag) {
                val a = residuals[j]
                val b = residuals[j + lag]
                corr += a * b
                e0 += a * a
                eL += b * b
                if (rawCorrelations != null) {
                    val rawA = values[(start + j) % ENV_HOPS] - mean
                    val rawB = values[(start + j + lag) % ENV_HOPS] - mean
                    rawCorr += rawA * rawB
                    rawE0 += rawA * rawA
                    rawEL += rawB * rawB
                }
            }
            correlations[lag] = corr / (sqrt(e0 * eL) + 1e-9f)
            if (rawCorrelations != null) {
                rawCorrelations[lag] = rawCorr / (sqrt(rawE0 * rawEL) + 1e-9f)
            }
        }

        val candidateMax = minOf(maxBandLag, maxComputedLag)
        var bandBestC = 0f
        var rawBandBestC = 0f
        var bestLag = 0
        if (candidateMax >= minLag) {
            for (lag in minLag..candidateMax) {
                rawBandBestC = maxOf(rawBandBestC, rawCorrelations?.get(lag) ?: correlations[lag])
                if (correlations[lag] > bandBestC) {
                    bandBestC = correlations[lag]
                    bestLag = lag
                }
            }
        }
        if (!oscillatory) bestLag = 0
        val windowTruncates = maxComputedLag < maxBandLag
        // While the window is still growing it cannot yet resolve the slowest
        // allowed lag, so any in-band pick sits at (or near) the truncation
        // edge. Re-pick fresh every hop instead of carrying a stale lag across
        // that growth: the old TRACKED_LAG_KEEP carry pinned a 5 Hz period to
        // the edge lag it first saw and never let the true period in. The
        // periodicity/depth gates already reject a weak edge correlation, so
        // a fresh pick opens the gate only once the period is actually visible.
        val trackedLag = if (windowTruncates) {
            bestLag
        } else {
            when {
                previousLag !in minLag..maxBandLag -> bestLag
                bestLag == 0 || bandBestC < TRACKED_SWITCH_FLOOR -> previousLag
                correlations[previousLag] >= TRACKED_LAG_KEEP * bandBestC -> previousLag
                else -> bestLag
            }
        }

        val rateHz = if (trackedLag > 0) {
            1000f / (trackedLag * HOP_MS.toFloat())
        } else {
            0f
        }
        val cheatLo = (1000f / (2f * maxHz * HOP_MS)).toInt() + 1
        val cheatHi = minOf(minLag - 1, maxComputedLag - 1)
        var shortPeakLag = 0
        var shortPeakC = 0f
        if (cheatHi >= cheatLo) {
            for (lag in maxOf(2, cheatLo)..cheatHi) {
                val c = correlations[lag]
                if (c >= correlations[lag - 1] && c >= correlations[lag + 1] && c > shortPeakC) {
                    shortPeakC = c
                    shortPeakLag = lag
                }
            }
        }
        val harmonicLeak = shortPeakLag >= 2 && trackedLag > shortPeakLag &&
            trackedLag % shortPeakLag == 0 && trackedLag <= 3 * shortPeakLag &&
            shortPeakC >= HARMONIC_OF_BEST * bandBestC
        val inBand = trackedLag > 0 && oscillatory && !harmonicLeak &&
            rateHz in minHz..maxHz
        val coherent = inBand && bandBestC >= periodGate
        val deep = inBand && deepGate != null && depth >= deepGate
        val stillPeriodic = inBand &&
            (bandBestC >= periodGate * 0.7f ||
                (deepGate != null && depth >= deepGate * DEPTH_OFF_RATIO))
        var liveSlope = slope
        if (trackedLag > 0) {
            // A full-cycle difference cancels the coherent modulation, so
            // the visual baseline follows a crescendo/glide without bending
            // the pulse it is meant to reveal.
            var cycleDifference = 0f
            for (j in 0 until n - trackedLag) {
                cycleDifference += values[(start + j + trackedLag) % ENV_HOPS] -
                    values[(start + j) % ENV_HOPS]
            }
            liveSlope = cycleDifference / ((n - trackedLag) * trackedLag)
        }
        val raw = if (amp > 1e-6f) {
            val liveBaseline = mean + liveSlope * (n - 1 + liveOffsetHops - centre)
            ((liveValue - liveBaseline) / amp).coerceIn(-1.5f, 1.5f)
        } else {
            0f
        }
        result.valid = true
        result.rateHz = rateHz
        result.depth = depth
        result.raw = raw
        result.coherent = coherent
        result.lifecycleCoherent = inBand && rawBandBestC >= periodGate
        result.periodic = coherent || deep
        result.stillPeriodic = stillPeriodic
        result.levelBalance = levelBalance
        result.trackedLag = trackedLag
    }

    /** Octave-folded pitch match: autocorrelation flips freely between a
     * period and its double on real voice, and those are the same note. */
    private fun samePitch(a: Float, b: Float): Boolean {
        if (a <= 0f || b <= 0f) return false
        val r = foldPitch(a, b) / b
        return abs(r - 1f) <= maxPitchDrift
    }

    private fun foldPitch(pitch: Float, anchor: Float): Float {
        var folded = pitch
        if (folded <= 0f || anchor <= 0f) return folded
        var r = folded / anchor
        while (r > 1.4142f) r /= 2f
        while (r < 0.7071f) r *= 2f
        folded = anchor * r
        return folded
    }

    /** Quadratic local projection from the YIN support centre to the newest
     * 20 ms hop. It follows upper-band FM curvature without consulting the
     * noisier long-window modulation-rate estimate. */
    private fun projectForward(
        latest: Float,
        previous: Float,
        beforePrevious: Float,
        hops: Float,
    ): Float {
        val h = hops.coerceIn(0f, 1f)
        return (h + 1f) * (h + 2f) * latest * 0.5f -
            h * (h + 2f) * previous +
            h * (h + 1f) * beforePrevious * 0.5f
    }

    /** Stable 80 ms pitch used only for hold identity and lifecycle timing. */
    private fun holdPitch(): Pair<Float, Float> {
        var energy = 0f
        for (j in 0 until frame.size - maxPitchLag) energy += work[j] * work[j]
        if (energy <= 1e-8f) return 0f to 0f
        var best = 0f
        for (lag in minPitchLag..maxPitchLag) {
            var correlation = 0f
            for (j in 0 until frame.size - maxPitchLag) {
                correlation += work[j] * work[j + lag]
            }
            corrs[lag] = correlation
            if (correlation > best) best = correlation
        }
        if (best <= 0f) return 0f to 0f
        var lag = minPitchLag
        while (lag <= maxPitchLag && corrs[lag] < 0.95f * best) lag++
        if (lag > maxPitchLag) return 0f to 0f
        return (analysisSampleRate / lag.toFloat()) to (best / energy)
    }

    /** Short, sub-lag YIN pitch used only for vibrato movement and phase. */
    private fun updateModulationPitch() {
        val pitchFrameSamples = hopSamples * PITCH_MODULATION_FRAME_HOPS
        val pitchStart = frame.size - pitchFrameSamples
        val pairs = pitchFrameSamples - maxPitchLag
        if (pairs <= 0) {
            modulationPitchHz = 0f
            modulationClarity = 0f
            modulationPitchLeadHops = 0f
            return
        }
        var cumulativeDifference = 0f
        corrs[0] = 1f
        for (lag in 1..maxPitchLag) {
            var difference = 0f
            for (j in 0 until pairs) {
                val delta = work[pitchStart + j] - work[pitchStart + j + lag]
                difference += delta * delta
            }
            cumulativeDifference += difference
            corrs[lag] = if (cumulativeDifference > 1e-8f) {
                difference * lag / cumulativeDifference
            } else {
                1f
            }
        }

        var lag = minPitchLag
        while (lag < maxPitchLag) {
            if (corrs[lag] < YIN_THRESHOLD) {
                while (lag < maxPitchLag && corrs[lag + 1] < corrs[lag]) lag++
                break
            }
            lag++
        }
        if (lag == maxPitchLag && corrs[lag] >= YIN_THRESHOLD) {
            var bestValue = corrs[minPitchLag]
            lag = minPitchLag
            for (candidate in minPitchLag + 1..maxPitchLag) {
                if (corrs[candidate] < bestValue) {
                    bestValue = corrs[candidate]
                    lag = candidate
                }
            }
        }
        val fractionalLag = if (lag in minPitchLag + 1 until maxPitchLag) {
            val left = corrs[lag - 1]
            val centre = corrs[lag]
            val right = corrs[lag + 1]
            val curvature = left - 2f * centre + right
            val offset = if (abs(curvature) > 1e-9f) {
                (0.5f * (left - right) / curvature).coerceIn(-0.5f, 0.5f)
            } else {
                0f
            }
            lag + offset
        } else {
            lag.toFloat()
        }
        modulationPitchHz = analysisSampleRate / fractionalLag
        modulationClarity = (1f - corrs[lag]).coerceIn(0f, 1f)
        val supportCentre = (pairs - 1 + fractionalLag) * 0.5f
        val liveCentre = pitchFrameSamples - (hopSamples + 1) * 0.5f
        modulationPitchLeadHops = (liveCentre - supportCentre) / hopSamples
    }

    companion object {
        const val SAMPLE_RATE = 8_000
        const val HOP_MS = 20
        const val HOP_SAMPLES = SAMPLE_RATE * HOP_MS / 1000 // 160
        const val FRAME_SAMPLES = HOP_SAMPLES * 4 // 80 ms
        private const val FRAME_HOPS = 4
        private const val PITCH_MODULATION_FRAME_HOPS = 2
        private const val RMS_PHASE_LEAD_HOPS = (FRAME_HOPS - 1) * 0.5f
        private const val YIN_THRESHOLD = 0.15f
        private const val ENV_HOPS = 64
        private const val MIN_ENV_HOPS = 16
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
        /** Product ceiling. Faster flutter is not a held-note reverberation. */
        const val MAX_MEASURABLE_TREMOLO_HZ = MAX_TREMOLO_HZ
        /** Shipped AM depth gate — Ink Lab: [minTremoloDepth]. */
        const val MIN_TREMOLO_DEPTH = 0.035f
        /** Relative AM depth at which the envelope is self-evidently
         * vibrating, autocorrelation aside — real crescents are irregular
         * and their periodicity dips below the autocorr gate right at the
         * climax. Shallow noise swells stay rejected by the autocorr. */
        private const val DEEP_DEPTH_GATE = 0.06f
        /** About ten cents of coherent F0 motion admits pitch-only vibrato. */
        private const val MIN_PITCH_DEPTH = 0.006f
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
        /** Shipped attack of [tremoloGain] — Ink Lab: [attackMs]. */
        const val ATTACK_MS = 250f
        /** Shipped release of [tremoloGain] (mid-hold lull bridge) — Ink Lab:
         * [releaseMs]. */
        const val RELEASE_MS = 800f
        /** Release once the hold's climax is over (ms) — a near-instant dry,
         * so the shimmer never flickers into the tail or the next word. */
        const val CLIMAX_RELEASE_MS = 50f
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
        /** Hops over which the shimmer's depth builds from the first detected
         * pulse to full (1 s). The first pulses of a waqf hold are soft and
         * the magnitude reaches full only as the swell approaches its crest,
         * instead of turning on and off at full depth from the start. */
        private const val SWELL_RAMP_HOPS = 50
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
         * ([sonicContentMs]) and optional live tap backlog
         * ([measuredSinkContentMs]) are already content-time and do not.
         */
        fun earDelayHops(
            routeMs: Long,
            sinkMs: Long,
            speed: Float,
            downstreamMs: Long = 0,
            sonicContentMs: Float = 0f,
            measuredSinkContentMs: Double? = null,
        ): Float {
            val safeSpeed = speed.coerceAtLeast(0f)
            val routeContentMs = (routeMs + downstreamMs).coerceAtLeast(0L) * safeSpeed
            val sinkContentMs = measuredSinkContentMs?.coerceAtLeast(0.0)?.toFloat()
                ?: (sinkMs.coerceAtLeast(0L) * safeSpeed)
            return ((routeContentMs + sinkContentMs + sonicContentMs) / HOP_MS)
                .coerceIn(0f, HIST_HOPS - 1f)
        }
    }
}
