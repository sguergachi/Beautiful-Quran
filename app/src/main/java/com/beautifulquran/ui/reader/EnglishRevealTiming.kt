package com.beautifulquran.ui.reader

/**
 * English Scroll finishes its reveal before the next timed word begins.
 * Keep the soft-edge minimum, and never stretch a short repeat to that floor.
 * Durations are wall milliseconds; the shared window converts speed once.
 */
internal fun englishRevealDurationMs(durationMs: Int?, minimumMs: Int): Int? {
    val duration = durationMs?.coerceAtLeast(1) ?: return null
    return (duration - 100).coerceAtLeast(minOf(duration, minimumMs.coerceAtLeast(1)))
}
