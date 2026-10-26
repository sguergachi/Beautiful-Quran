package com.beautifulquran.ui.reader

/**
 * English Scroll finishes by the voiced end, then holds through the silence.
 * Keep the earlier 100 ms saving and soft-edge minimum wherever they fit.
 * The reveal duration is in wall time; [spokenMs] is the occurrence's media span.
 */
internal fun englishRevealDurationMs(
    durationMs: Int?,
    minimumMs: Int,
    spokenMs: Long? = null,
    speed: Float = 1f,
): Int? {
    val duration = durationMs?.coerceAtLeast(1) ?: return null
    val earlier = (duration - 100).coerceAtLeast(minOf(duration, minimumMs.coerceAtLeast(1)))
    val voiced = spokenMs?.takeIf { it > 0 && speed.isFinite() && speed > 0f } ?: return earlier
    // Round down: the visual floor must not spend the reciter's pause. A sub-ms
    // wall span is allowed; mediaWashWindow keeps at least one media millisecond.
    return minOf(earlier, (voiced.toDouble() / speed).toInt())
}
