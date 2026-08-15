package com.beautifulquran.tarjilab

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlinx.serialization.Serializable

/** Whether this word's hold is a reciter vibrato, a still note, or unlabeled. */
@Serializable
enum class TarjiExpectationKind { UNLABELED, NO_SHIMMER, PULSES }

/** Kept so schema-2 samples still decode. The lab no longer authors a sine. */
@Serializable
data class TarjiTargetStyle(
    val depth: Float = 1f,
    val troughFloor: Float = 0f,
    val buildMs: Float = 1_000f,
    val dryMs: Float = 50f,
)

/**
 * Human ground truth for one capture. The product is a hold window
 * ([startMs]..[endMs]) plus an optional hand-shaped [envelope] — not a
 * target frequency. Crest/style fields remain so older samples import.
 */
@Serializable
data class TarjiLabExpectation(
    val kind: TarjiExpectationKind = TarjiExpectationKind.UNLABELED,
    val startMs: Float? = null,
    val endMs: Float? = null,
    val crestMs: List<Float> = emptyList(),
    /** Crest that owns phase when a regular target rate is auditioned. */
    val phaseAnchorMs: Float? = null,
    val style: TarjiTargetStyle = TarjiTargetStyle(),
    /** Hop-aligned 0..1 signature shape, empty when the ear has not drawn. */
    val envelope: List<Float> = emptyList(),
) {
    val hasWindow: Boolean
        get() = startMs != null && endMs != null && endMs > startMs

    val window: TarjiHoldWindow?
        get() {
            val start = startMs ?: return null
            val end = endMs ?: return null
            if (end <= start) return null
            return TarjiHoldWindow(start, end)
        }

    val canPreview: Boolean
        get() = kind == TarjiExpectationKind.NO_SHIMMER ||
            (kind == TarjiExpectationKind.PULSES && hasWindow)

    /** Robust target rate from the median interval between marked crests. */
    val rateHz: Float?
        get() {
            val intervals = crestMs.sorted().zipWithNext { a, b -> b - a }
                .filter { it >= MIN_CREST_INTERVAL_MS }
                .sorted()
            if (intervals.isEmpty()) return null
            val middle = intervals.size / 2
            val median = if (intervals.size % 2 == 1) {
                intervals[middle]
            } else {
                (intervals[middle - 1] + intervals[middle]) / 2f
            }
            return 1_000f / median
        }

    fun markStart(ms: Float, durationMs: Float): TarjiLabExpectation {
        val at = ms.coerceIn(0f, durationMs)
        val end = endMs?.takeIf { it > at + TarjiHoldWindow.MIN_HOLD_MS }
            ?: (at + TarjiHoldWindow.MIN_HOLD_MS).coerceAtMost(durationMs)
        return copy(
            kind = if (kind == TarjiExpectationKind.NO_SHIMMER) kind else TarjiExpectationKind.PULSES,
            startMs = at,
            endMs = end.takeIf { it > at },
            crestMs = crestMs.filter { it >= at },
            phaseAnchorMs = phaseAnchorMs?.takeIf { it >= at },
        )
    }

    fun markEnd(ms: Float, durationMs: Float): TarjiLabExpectation {
        val at = ms.coerceIn(0f, durationMs)
        val start = startMs?.takeIf { it < at - TarjiHoldWindow.MIN_HOLD_MS }
            ?: (at - TarjiHoldWindow.MIN_HOLD_MS).coerceAtLeast(0f)
        return copy(
            kind = if (kind == TarjiExpectationKind.NO_SHIMMER) kind else TarjiExpectationKind.PULSES,
            startMs = start.takeIf { it < at },
            endMs = at,
            crestMs = crestMs.filter { it <= at },
            phaseAnchorMs = phaseAnchorMs?.takeIf { it <= at },
        )
    }

    fun withWindow(window: TarjiHoldWindow, captureMs: Float): TarjiLabExpectation {
        val next = TarjiHoldWindow.of(window.startMs, window.endMs, captureMs)
        return copy(
            startMs = next.startMs,
            endMs = next.endMs,
            crestMs = crestMs.filter { it in next.startMs..next.endMs },
            phaseAnchorMs = phaseAnchorMs?.takeIf { it in next.startMs..next.endMs },
        )
    }

    fun withEnvelope(values: List<Float>): TarjiLabExpectation = copy(envelope = values)

    fun labeled(kind: TarjiExpectationKind): TarjiLabExpectation = copy(kind = kind)

    fun addCrest(ms: Float, durationMs: Float): TarjiLabExpectation {
        val at = ms.coerceIn(0f, durationMs)
        val marks = crestMs.filter { abs(it - at) >= MIN_CREST_INTERVAL_MS } + at
        return copy(
            kind = TarjiExpectationKind.PULSES,
            crestMs = marks.sorted(),
            phaseAnchorMs = at,
        )
    }

    fun removeLastCrest(): TarjiLabExpectation {
        val marks = crestMs.dropLast(1)
        return copy(crestMs = marks, phaseAnchorMs = marks.lastOrNull())
    }

    /** Fill the expected span with a regular cadence, preserving one
     * listener-marked crest as the phase anchor. Individual crests can still
     * be added afterward when the reciter accelerates or slows naturally. */
    fun withRate(rateHz: Float): TarjiLabExpectation {
        val start = startMs ?: return this
        val end = endMs ?: return this
        if (end <= start) return this
        val anchor = (phaseAnchorMs ?: crestMs.firstOrNull() ?: return this)
            .coerceIn(start, end)
        val period = 1_000f / rateHz.coerceIn(MIN_TARGET_HZ, MAX_TARGET_HZ)
        var first = anchor
        while (first - period >= start) first -= period
        val marks = mutableListOf<Float>()
        var at = first
        while (at <= end + 0.5f) {
            marks += at.coerceIn(start, end)
            at += period
        }
        return copy(kind = TarjiExpectationKind.PULSES, crestMs = marks.distinct())
    }

    companion object {
        fun noShimmer(): TarjiLabExpectation =
            TarjiLabExpectation(kind = TarjiExpectationKind.NO_SHIMMER)

        private const val MIN_CREST_INTERVAL_MS = 40f
        private const val MIN_TARGET_HZ = 1.5f
        private const val MAX_TARGET_HZ = 10f
    }
}

/** Continuous manually authored shimmer signal at one capture time. */
data class TarjiTargetPoint(
    val tremolo: Float = 0f,
    val gain: Float = 0f,
    val holding: Boolean = false,
)

/**
 * Turn the listener's onset, crest, and end marks into the exact pulse used
 * by the target preview. Adjacent crests define each local cycle, preserving
 * non-uniform cadence instead of reducing the voice to one average Hz.
 */
fun targetTarjiPointAt(expectation: TarjiLabExpectation, ms: Float): TarjiTargetPoint {
    if (!expectation.canPreview || expectation.kind != TarjiExpectationKind.PULSES) {
        return TarjiTargetPoint()
    }
    val start = expectation.startMs ?: return TarjiTargetPoint()
    val end = expectation.endMs ?: return TarjiTargetPoint()
    if (ms !in start..end) return TarjiTargetPoint()
    // Lab edits and schema exports keep crests ordered; avoid sorting this
    // list hundreds of times while the canvas samples the target each frame.
    val crests = expectation.crestMs
    val before = crests.indexOfLast { it <= ms }
    val (anchor, period) = when {
        before < 0 -> crests.first() to (crests[1] - crests[0])
        before >= crests.lastIndex ->
            crests.last() to (crests.last() - crests[crests.lastIndex - 1])
        else -> crests[before] to (crests[before + 1] - crests[before])
    }
    val phase = (ms - anchor) / period.coerceAtLeast(1f)
    val style = expectation.style
    val build = smootherstep((ms - start) / style.buildMs.coerceAtLeast(1f))
    val dry = smootherstep((end - ms) / style.dryMs.coerceAtLeast(1f))
    return TarjiTargetPoint(
        tremolo = cos(2f * PI * phase),
        gain = min(build, dry),
        holding = true,
    )
}

private fun smootherstep(value: Float): Float {
    val x = value.coerceIn(0f, 1f)
    return x * x * x * (x * (x * 6f - 15f) + 10f)
}

private const val PI = 3.14159265f

/** Detector error against the listener's labels; positive edge errors are late. */
data class TarjiExpectationComparison(
    val detectedStartMs: Float?,
    val detectedEndMs: Float?,
    val detectedRateHz: Float?,
    val startErrorMs: Float?,
    val endErrorMs: Float?,
    val rateErrorHz: Float?,
    val meanCrestErrorMs: Float?,
)

/** Compare the current detector trace with manually marked ear truth. */
fun compareTarjiExpectation(
    expectation: TarjiLabExpectation,
    trace: TarjiLabTrace,
): TarjiExpectationComparison {
    val span = trace.reverberatingSpan
    val detectedStart = span?.let { (it.first + 0.5f) * trace.hopDurationMs }
    val detectedEnd = span?.let { (it.last + 0.5f) * trace.hopDurationMs }
    val detectedRate = trace.meanRateHz.takeIf { it > 0f }
    val detectedCrests = detectedTarjiCrests(trace)
    val crestError = expectation.crestMs.takeIf { it.isNotEmpty() && detectedCrests.isNotEmpty() }
        ?.map { expected -> detectedCrests.minOf { abs(it - expected) } }
        ?.average()
        ?.toFloat()
    return TarjiExpectationComparison(
        detectedStartMs = detectedStart,
        detectedEndMs = detectedEnd,
        detectedRateHz = detectedRate,
        startErrorMs = expectation.startMs?.let { expected -> detectedStart?.minus(expected) },
        endErrorMs = expectation.endMs?.let { expected -> detectedEnd?.minus(expected) },
        rateErrorHz = expectation.rateHz?.let { expected -> detectedRate?.minus(expected) },
        meanCrestErrorMs = crestError,
    )
}

/** Positive local maxima of the exact pulse sent to the shimmer renderer. */
fun detectedTarjiCrests(trace: TarjiLabTrace): List<Float> {
    if (trace.hopCount < 3) return emptyList()
    val result = mutableListOf<Float>()
    for (i in maxOf(trace.firstAnalysisHop + 1, 1) until trace.hopCount - 1) {
        if (
            trace.reverberating[i] && trace.tremolo[i] > 0f &&
            trace.tremolo[i] >= trace.tremolo[i - 1] &&
            trace.tremolo[i] > trace.tremolo[i + 1]
        ) {
            result += (i + 0.5f) * trace.hopDurationMs
        }
    }
    return result
}
