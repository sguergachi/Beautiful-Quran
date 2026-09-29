package com.beautifulquran.domain

import com.beautifulquran.data.model.Segment

/**
 * Silence shorter than this is left alone. A seek that close to a word
 * costs more than the quiet it removes.
 */
internal const val SILENCE_SLACK_MS = 40L

/**
 * Where to seek when [positionMs] is in silence before a word.
 * Null when a word is already sounding, or the remaining quiet is inside
 * the slack. The returned time is that word's [Segment.startMs], the same
 * instant the highlight starts it.
 */
internal fun nextVoicedMs(segments: List<Segment>, positionMs: Long): Long? {
    if (segments.isEmpty() || positionMs < 0L) return null
    for (seg in segments) {
        if (positionMs + SILENCE_SLACK_MS < seg.startMs) return seg.startMs
        if (positionMs < seg.endMs) return null
    }
    return null
}

/** The playhead is past the last word, in the file's trailing silence. */
internal fun isAfterLastWord(segments: List<Segment>, positionMs: Long): Boolean {
    val endMs = segments.lastOrNull()?.endMs ?: return false
    return positionMs >= endMs + SILENCE_SLACK_MS
}

/**
 * The ayah to enter when this one has finished its words.
 * [ayah] 0 is the basmalah lead-in. A null result keeps the file, which is
 * the last verse of a surah that is not repeating.
 */
internal fun nextVerseAyah(
    ayah: Int,
    ayahCount: Int,
    repeatOne: Boolean,
    repeatAll: Boolean,
    range: IntRange?,
    opensWithBasmalah: Boolean,
): Int? = when {
    ayahCount <= 0 -> null
    repeatOne -> ayah
    range != null && ayah >= range.last -> range.first
    ayah < ayahCount -> ayah + 1
    repeatAll -> if (opensWithBasmalah) 0 else 1
    else -> null
}
