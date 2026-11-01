package com.beautifulquran.ui.reader

/**
 * Silence between a word's voiced end and the next onset that counts as the
 * reciter pausing (media ms). Connected speech leaves under 20 ms between
 * timed words (96% of the corpus) and real pauses mostly run past a second,
 * so the line is not sensitive to its exact value.
 */
internal const val ENGLISH_PAUSE_MS = 150L

/**
 * English Scroll flows straight into the next word through connected speech:
 * the reveal spans the whole hold, as every other wash does. Only where the
 * reciter pauses does it finish by the voiced end and hold through the
 * silence. (Finishing 100 ms early before *every* word made each one stop.)
 * [durationMs] is the wall-time hold; [spokenMs] is the occurrence's media span.
 */
internal fun englishRevealDurationMs(
    durationMs: Int?,
    spokenMs: Long? = null,
    speed: Float = 1f,
): Int? {
    val duration = durationMs?.coerceAtLeast(1) ?: return null
    val voiced = spokenMs?.takeIf { it > 0 && speed.isFinite() && speed > 0f } ?: return duration
    if (duration * speed.toDouble() - voiced < ENGLISH_PAUSE_MS) return duration
    // Round down: the visual floor must not spend the reciter's pause. A sub-ms
    // wall span is allowed; mediaWashWindow keeps at least one media millisecond.
    return (voiced.toDouble() / speed).toInt()
}
