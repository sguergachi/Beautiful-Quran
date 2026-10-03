package com.beautifulquran.tarjilab

import com.beautifulquran.playback.Tarji
import com.beautifulquran.playback.TarjiLabCapture
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** A measured pulse and a rounded search band around it; never a synthesized effect. */
internal data class TarjiPulseMatch(val rateHz: Float, val minHz: Float, val maxHz: Float)

/**
 * Survey only complete hops inside the selected audio. A temporary broad-band
 * replay finds coherent candidates even when the current tuning rejects them.
 * Its output is never displayed or saved as the user's detector settings.
 */
internal fun matchTarjiPulse(
    capture: TarjiLabCapture,
    window: TarjiHoldWindow,
    knobs: TarjiLabKnobs,
): TarjiPulseMatch? {
    val hopMs = capture.hopContentDurationMs()
    if (capture.hopCount == 0 || !hopMs.isFinite() || hopMs <= 0f ||
        !window.startMs.isFinite() || !window.endMs.isFinite()) return null
    val first = ceil(window.startMs.coerceAtLeast(0f) / hopMs).toInt().coerceAtMost(capture.hopCount)
    val end = floor(window.endMs / hopMs).toInt().coerceIn(0, capture.hopCount)
    if (end <= first) return null
    val trace = analyzeTarjiCapture(capture.slice(first until end), knobs.copy(
        minTremoloHz = Tarji.MIN_TREMOLO_HZ, maxTremoloHz = Tarji.MAX_TREMOLO_HZ,
        minTremoloDepth = 0.01f, minPeriodicity = Tarji.MIN_PERIODICITY, holdMinMs = 0f,
    ))
    // Count cycles in the measured wave: the broad-band autocorrelation rate
    // can select a longer multiple of a fast pulse's fundamental period.
    val rates = mutableListOf<Float>()
    var previousCrossing = Float.NaN
    var armed = false
    for (i in maxOf(1, trace.firstAnalysisHop) until trace.hopCount) {
        val amplitude = trace.visualUsesAmplitude[i]
        val regularity = if (amplitude) trace.amplitudePeriodicity[i]
            else trace.pitchModulationPeriodicity[i]
        if (!trace.reverberating[i] || regularity < 0.5f ||
            amplitude != trace.visualUsesAmplitude[i - 1]) {
            previousCrossing = Float.NaN
            armed = false
            continue
        }
        val pulse = trace.candidateModulation[i]
        if (pulse < -0.1f) armed = true
        if (!armed || pulse < 0f) continue
        val before = trace.candidateModulation[i - 1]
        val crossing = i - 1 + (-before / (pulse - before)).coerceIn(0f, 1f)
        if (previousCrossing.isFinite()) {
            val rate = 1_000f / ((crossing - previousCrossing) * hopMs)
            if (rate in Tarji.MIN_TREMOLO_HZ..Tarji.MAX_TREMOLO_HZ) rates += rate
        }
        previousCrossing = crossing
        armed = false
    }
    rates.sort()
    if (rates.isEmpty()) return null
    val rate = rates[rates.size / 2]
    val margin = max(0.5f, rate * 0.15f)
    val agreeing = rates.filter { abs(it - rate) <= margin }
    // Require sustained agreement and at least two cycles, rather than
    // trusting a single analysis hop or averaging two different notes.
    val supportedMs = agreeing.sumOf { (1_000f / it).toDouble() }
    val measuredMs = rates.sumOf { (1_000f / it).toDouble() }
    if (agreeing.size < 2 || supportedMs < measuredMs * 0.75 || supportedMs < 300.0) return null
    return TarjiPulseMatch(rate,
        (floor((rate - margin) * 10f) / 10f).coerceAtLeast(Tarji.MIN_TREMOLO_HZ),
        (ceil((rate + margin) * 10f) / 10f).coerceAtMost(Tarji.MAX_TREMOLO_HZ))
}
