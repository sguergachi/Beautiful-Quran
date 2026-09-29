package com.beautifulquran.domain

/**
 * A jump that lands within this of the file start is still the start of the
 * verse. Farther in, the listener chose that position.
 */
internal const val VERSE_OPENING_SLACK_MS = 40L

/**
 * Media position where the voice should start, matching the highlight.
 * [firstWordStartMs] is the first segment, the same gate the highlight holds
 * for. Null when this jump is not the start of the file, or the verse has
 * no opening silence.
 */
internal fun verseOpeningSeekMs(firstWordStartMs: Long, positionMs: Long): Long? {
    if (firstWordStartMs <= 0L || positionMs < 0L) return null
    if (positionMs > VERSE_OPENING_SLACK_MS) return null
    if (positionMs + VERSE_OPENING_SLACK_MS >= firstWordStartMs) return null
    return firstWordStartMs
}
