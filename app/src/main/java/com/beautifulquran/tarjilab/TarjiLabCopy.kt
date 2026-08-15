package com.beautifulquran.tarjilab

import kotlin.math.abs

/** One scope readout: clock, the hold, and a single detector verdict. */
data class TarjiLabScopeCopy(
    val clock: String,
    val hold: String,
    val verdict: String?,
)

/** Seconds, one unit at the end of a range — never mixed with signed milliseconds. */
fun formatLabSeconds(ms: Float): String =
    "%.2f".format(ms.coerceAtLeast(0f) / 1000f)

fun formatLabRange(startMs: Float, endMs: Float): String =
    "${formatLabSeconds(startMs)}–${formatLabSeconds(endMs)} s"

fun formatLabClock(playheadMs: Float, durationMs: Float): String {
    if (durationMs <= 0f) return "Capturing"
    return "${formatLabSeconds(playheadMs.coerceIn(0f, durationMs))} / ${formatLabSeconds(durationMs)} s"
}

/**
 * Speak only when the ear and the detector disagree. Silence is agreement,
 * and silence is the unlabeled default — the algorithm does not talk first.
 */
fun detectorDisagreement(
    kind: TarjiExpectationKind,
    comparison: TarjiExpectationComparison?,
    analyzing: Boolean,
): String? {
    if (analyzing || comparison == null) return null
    return when (kind) {
        TarjiExpectationKind.UNLABELED -> null
        TarjiExpectationKind.NO_SHIMMER ->
            if (comparison.detectedStartMs != null) "Hears a hold" else null
        TarjiExpectationKind.PULSES -> {
            if (comparison.detectedStartMs == null) return "Doesn't hear it"
            val start = comparison.startErrorMs
            val end = comparison.endErrorMs
            when {
                start != null && start >= AGREE_MS -> "Late"
                start != null && start <= -AGREE_MS -> "Early"
                end != null && end >= AGREE_MS -> "Late"
                end != null && end <= -AGREE_MS -> "Early"
                abs(start ?: 0f) < AGREE_MS && abs(end ?: 0f) < AGREE_MS -> null
                else -> null
            }
        }
    }
}

fun tarjiLabScopeCopy(
    playheadMs: Float,
    durationMs: Float,
    window: TarjiHoldWindow?,
    analyzing: Boolean,
    kind: TarjiExpectationKind,
    comparison: TarjiExpectationComparison?,
): TarjiLabScopeCopy = TarjiLabScopeCopy(
    clock = formatLabClock(playheadMs, durationMs),
    hold = window?.let { formatLabRange(it.startMs, it.endMs) } ?: "—",
    verdict = detectorDisagreement(kind, comparison, analyzing),
)

private const val AGREE_MS = 80f
