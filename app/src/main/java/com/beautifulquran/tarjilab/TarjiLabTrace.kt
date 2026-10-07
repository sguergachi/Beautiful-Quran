package com.beautifulquran.tarjilab

import com.beautifulquran.playback.Tarji
import com.beautifulquran.playback.TarjiDetectorMode
import com.beautifulquran.playback.TarjiEarPulse
import com.beautifulquran.playback.TarjiExperimentalDetector
import com.beautifulquran.playback.TarjiFrame
import com.beautifulquran.playback.TarjiLabCapture
import com.beautifulquran.playback.TarjiRecordingDetector
import com.beautifulquran.ui.reader.InkEngine
import java.util.concurrent.CancellationException
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/**
 * Per-reciter detector knobs and visual glint scale. Detector fields mirror
 * [Tarji] for offline replay; brightness applies only to paint.
 */
@Serializable
data class TarjiLabKnobs(
    val maxTremoloHz: Float = Tarji.MAX_TREMOLO_HZ,
    val minTremoloHz: Float = Tarji.MIN_TREMOLO_HZ,
    val holdMinMs: Float = Tarji.HOLD_MIN_MS.toFloat(),
    val minTremoloDepth: Float = Tarji.MIN_TREMOLO_DEPTH,
    val minPeriodicity: Float = Tarji.MIN_PERIODICITY,
    /** Quietest voice (RMS) a reverberation may open on; 0 is no threshold. */
    val minVolume: Float = Tarji.MIN_VOLUME,
    val maxPitchDrift: Float = Tarji.MAX_PITCH_DRIFT,
    val attackMs: Float = Tarji.ATTACK_MS,
    val releaseMs: Float = Tarji.RELEASE_MS,
    /** Visual scale only; never changes the detector or measured trace. */
    val glintBrightness: Float = 1f,
) {
    /** Apply onto a fresh detector — the analysis entry point. */
    fun applyTo(detector: Tarji) {
        detector.maxTremoloHz = maxTremoloHz
        detector.minTremoloHz = minTremoloHz
        detector.holdMinMs = holdMinMs
        detector.minTremoloDepth = minTremoloDepth
        detector.minPeriodicity = minPeriodicity
        detector.minVolume = minVolume
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
            minVolume = t.tarjiMinVolume,
            maxPitchDrift = t.tarjiPitchDrift,
            attackMs = t.tarjiAttackMs,
            releaseMs = t.tarjiReleaseMs,
            glintBrightness = t.glintBrightness,
        )

        /** Apply the lab's full pulse and knobs to live playback, preserving the wash settings. */
        fun applyToTuning(knobs: TarjiLabKnobs, t: InkEngine.Tuning): InkEngine.Tuning =
            t.copy(
                glintResonance = true,
                glintResonanceDepth = InkEngine.GLINT_RESONANCE_DEPTH,
                glintResonanceMaxHz = knobs.maxTremoloHz,
                tarjiMinHz = knobs.minTremoloHz,
                tarjiHoldMinMs = knobs.holdMinMs,
                tarjiMinDepth = knobs.minTremoloDepth,
                tarjiMinPeriodicity = knobs.minPeriodicity,
                tarjiMinVolume = knobs.minVolume,
                tarjiPitchDrift = knobs.maxPitchDrift,
                tarjiAttackMs = knobs.attackMs,
                tarjiReleaseMs = knobs.releaseMs,
                glintBrightness = knobs.glintBrightness,
            )
    }
}

/**
 * Per-hop detector output for one [TarjiLabCapture], computed by replaying
 * the captured hops through the shared [Tarji] extractor and chosen method,
 * with the knobs fixed at analysis time. Every array is hop-aligned with the
 * capture; visual phase always comes from the measured voice.
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
    val amplitudeRateHz: FloatArray = FloatArray(hopCount),
    val pitchModulationRateHz: FloatArray = FloatArray(hopCount),
    val amplitudeDepth: FloatArray = FloatArray(hopCount),
    val pitchModulationDepth: FloatArray = FloatArray(hopCount),
    val amplitudePeriodicity: FloatArray = FloatArray(hopCount),
    val pitchModulationPeriodicity: FloatArray = FloatArray(hopCount),
    val visualUsesAmplitude: BooleanArray = BooleanArray(hopCount),
    val candidateModulation: FloatArray = FloatArray(hopCount),
    val mode: TarjiDetectorMode = TarjiDetectorMode.Current,
    /** Source analysis-hop clock, including the capture's lead-in. */
    val eventStartHop: IntArray = IntArray(hopCount) { -1 },
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
 *
 * The capture's lead-in is fed first and discarded: the reader's detector
 * never meets a word cold, so neither may the lab's.
 * Recording is capture-local analysis of that lead-in and capture, not a
 * whole-verse verdict. [wanted] is polled at least every 64 extraction hops.
 */
fun analyzeTarjiCapture(
    capture: TarjiLabCapture,
    knobs: TarjiLabKnobs,
    mode: TarjiDetectorMode = TarjiDetectorMode.Current,
    wanted: () -> Boolean = { true },
): TarjiLabTrace {
    val n = capture.hopCount
    val detector = Tarji()
    detector.hopSamples = capture.hopSamples
    knobs.applyTo(detector)
    val scratch = FloatArray(capture.hopSamples)
    val hopDur = capture.hopContentDurationMs()
    detector.hopContentDurationMs = hopDur.toDouble()
    val experiment = if (mode == TarjiDetectorMode.Cycles || mode == TarjiDetectorMode.Spectrum)
        TarjiExperimentalDetector() else null
    val frames = if (mode == TarjiDetectorMode.Recording) ArrayList<TarjiFrame>() else null
    val env = FloatArray(n)
    val tremolo = FloatArray(n)
    val gain = FloatArray(n)
    val reverberating = BooleanArray(n)
    val rate = FloatArray(n)
    val pitch = FloatArray(n)
    val hold = FloatArray(n)
    val amRate = FloatArray(n)
    val fmRate = FloatArray(n)
    val amDepth = FloatArray(n)
    val fmDepth = FloatArray(n)
    val amPeriodicity = FloatArray(n)
    val fmPeriodicity = FloatArray(n)
    val usesAmplitude = BooleanArray(n)
    val candidate = FloatArray(n)
    val eventStart = IntArray(n) { -1 }
    var resolved = -1
    val leadHops = capture.leadInHopCount
    // The voice the reader's pulse is drawn from (see [TarjiEarPulse]), kept
    // for the lead-in too: it is the level behind the capture's first hops.
    val voiceRms = FloatArray(leadHops + n)
    val voicePitch = FloatArray(leadHops + n)
    val voicePitchLead = FloatArray(leadHops + n)
    val voiceRate = FloatArray(leadHops + n)
    val voiceUsesAmplitude = BooleanArray(leadHops + n)
    fun checkWanted() {
        if (!wanted()) throw CancellationException("Lab analysis replaced")
    }
    fun keepVoice(hop: Int) {
        val frame = detector.measurements
        if (frame.hop >= 0) experiment?.next(frame, mode, detector)
        frames?.add(if (frame.hop >= 0) frame.copy() else TarjiFrame(hop = hop, hopMs = hopDur.toDouble()))
        voiceRms[hop] = detector.lastHopRms
        voicePitch[hop] = detector.lastFoldedPitchHz
        voicePitchLead[hop] = detector.lastPitchLeadHops
        voiceRate[hop] = experiment?.decision?.rateHz ?: detector.lastRateHz
        voiceUsesAmplitude[hop] = experiment?.decision?.usesAmplitude ?: detector.lastVisualUsesAmplitude
    }
    for (i in 0 until leadHops) {
        if (i and 63 == 0) checkWanted()
        System.arraycopy(capture.leadInPcm, i * capture.hopSamples, scratch, 0, capture.hopSamples)
        detector.onSamples8k(scratch)
        keepVoice(i)
    }
    for (i in 0 until n) {
        if (i and 63 == 0) checkWanted()
        System.arraycopy(capture.pcm, i * capture.hopSamples, scratch, 0, capture.hopSamples)
        detector.onSamples8k(scratch)
        keepVoice(leadHops + i)
        // The detector resolves its first hop only once the 80 ms ring (four
        // hops) is full — the same warmup the live path has.
        if (leadHops + i < DETECTOR_FRAME_HOPS - 1) continue
        if (resolved < 0) resolved = i
        env[i] = frameRms(capture, i)
        gain[i] = detector.tremoloGain
        reverberating[i] = detector.reverberating
        rate[i] = detector.lastRateHz
        pitch[i] = detector.lastPitchHz
        hold[i] = detector.holdMs
        amRate[i] = detector.lastAmplitudeRateHz
        fmRate[i] = detector.lastPitchModulationRateHz
        amDepth[i] = detector.lastAmplitudeDepth
        fmDepth[i] = detector.lastPitchModulationDepth
        amPeriodicity[i] = detector.lastAmplitudePeriodicity
        fmPeriodicity[i] = detector.lastPitchModulationPeriodicity
        usesAmplitude[i] = detector.lastVisualUsesAmplitude
        candidate[i] = detector.lastCandidateModulation
        eventStart[i] = detector.eventStartHop
        experiment?.let {
            val decision = it.decision
            gain[i] = decision.gain
            reverberating[i] = decision.reverberating
            rate[i] = decision.rateHz
            usesAmplitude[i] = decision.usesAmplitude
            eventStart[i] = decision.eventStartHop
            amRate[i] = it.modulation.am.rateHz
            fmRate[i] = it.modulation.fm.rateHz
            amDepth[i] = it.modulation.am.depth
            fmDepth[i] = it.modulation.fm.depth
            amPeriodicity[i] = it.modulation.am.score
            fmPeriodicity[i] = it.modulation.fm.score
        }
    }
    if (resolved < 0) resolved = DETECTOR_FRAME_HOPS - 1
    frames?.let {
        checkWanted()
        val recording = TarjiRecordingDetector.analyze(it, detector, wanted)
        for (hop in voiceRate.indices) {
            if (hop and 63 == 0) checkWanted()
            voiceRate[hop] = recording.rate[hop]
            voiceUsesAmplitude[hop] = recording.amplitude[hop]
            val i = hop - leadHops
            if (i < resolved) continue
            gain[i] = recording.gain[hop]
            rate[i] = recording.rate[hop]
            usesAmplitude[i] = recording.amplitude[hop]
            eventStart[i] = recording.eventStart[hop]
            reverberating[i] = recording.eventStart[hop] >= 0
            amRate[i] = if (usesAmplitude[i]) rate[i] else 0f
            fmRate[i] = if (usesAmplitude[i]) 0f else rate[i]
            amDepth[i] = 0f
            fmDepth[i] = 0f
            amPeriodicity[i] = 0f
            fmPeriodicity[i] = 0f
        }
    }
    // The pulse the reader paints: the voice around each hop's own instant,
    // not the detector's causal estimate of it.
    val pulse = TarjiEarPulse.series(
        voiceRms, voicePitch, voicePitchLead, voiceRate, voiceUsesAmplitude,
        hopDur.toDouble(), from = leadHops,
    )
    for (i in resolved until n) {
        tremolo[i] = pulse[i]
        if (mode != TarjiDetectorMode.Current) candidate[i] = pulse[i]
    }
    checkWanted()
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
        amplitudeRateHz = amRate,
        pitchModulationRateHz = fmRate,
        amplitudeDepth = amDepth,
        pitchModulationDepth = fmDepth,
        amplitudePeriodicity = amPeriodicity,
        pitchModulationPeriodicity = fmPeriodicity,
        visualUsesAmplitude = usesAmplitude,
        candidateModulation = candidate,
        mode = mode,
        eventStartHop = eventStart,
    )
}

/** Measured pulse before the acceptance gate. Preserve a fixed scale so a
 * rejected hold remains inspectable rather than being multiplied into silence. */
fun tarjiPulseWave(trace: TarjiLabTrace): List<Float> =
    List(trace.hopCount) { i -> trace.candidateModulation[i].coerceIn(-1f, 1f) }

/** Reader output, drawn over the candidate where the tuned detector accepts it. */
fun tarjiAcceptedPulseWave(trace: TarjiLabTrace): List<Float> =
    List(trace.hopCount) { i -> (trace.tremolo[i] * trace.gain[i]).coerceIn(-1f, 1f) }

/** RMS of the 80 ms frame ending at hop [hop] (hops [hop−3]..[hop]) — the
 * detector's own envelope window, reaching back into the lead-in for the
 * capture's first three hops. */
private fun frameRms(capture: TarjiLabCapture, hop: Int): Float {
    val hopSamples = capture.hopSamples
    val lead = capture.leadInPcm
    var sum = 0f
    val start = (hop - 3) * hopSamples
    for (j in start until start + 4 * hopSamples) {
        val v = if (j >= 0) capture.pcm[j] else lead[lead.size + j]
        sum += v * v
    }
    return sqrt(sum / (4f * hopSamples))
}

/** Interpolated trace values at [ms] from the capture start — the lab's
 * playhead read. Falls back to the nearest resolved hop outside the trace. */
class TarjiLabPoint(
    val tremolo: Float,
    val gain: Float,
    val reverberating: Boolean,
    val rateHz: Float,
    val amplitudeRateHz: Float,
    val pitchModulationRateHz: Float,
    val amplitudeDepth: Float,
    val pitchModulationDepth: Float,
    val amplitudePeriodicity: Float,
    val pitchModulationPeriodicity: Float,
    val visualUsesAmplitude: Boolean,
)

fun tracePointAt(trace: TarjiLabTrace, ms: Float): TarjiLabPoint {
    val first = trace.firstAnalysisHop
    val last = trace.hopCount - 1
    if (last < first) return TarjiLabPoint(0f, 0f, false, 0f, 0f, 0f, 0f, 0f, 0f, 0f, false)
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
        amplitudeRateHz = lerp(trace.amplitudeRateHz, before, after),
        pitchModulationRateHz = lerp(trace.pitchModulationRateHz, before, after),
        amplitudeDepth = lerp(trace.amplitudeDepth, before, after),
        pitchModulationDepth = lerp(trace.pitchModulationDepth, before, after),
        amplitudePeriodicity = lerp(trace.amplitudePeriodicity, before, after),
        pitchModulationPeriodicity = lerp(trace.pitchModulationPeriodicity, before, after),
        visualUsesAmplitude = trace.visualUsesAmplitude[after],
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
