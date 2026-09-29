package com.beautifulquran.domain

/** Rubʿ al-ḥizb mark in Uthmani text (۞). */
internal const val HIZB_MARKER = '\u06DE'

/**
 * A hizb file whose first word starts this late is a recited stop, not a
 * breath. Shorter openings, including mujawwad pauses under this line and
 * ordinary verse breaths, play from the start of the file.
 */
internal const val HIZB_STOP_LEAD_MS = 800L

/** How much of the file to keep in front of the first word, so the attack is not clipped. */
internal const val HIZB_ATTACK_MS = 160L

/** A playhead already this close to the attack is left where it is. */
internal const val HIZB_SEEK_SLACK_MS = 40L

internal fun isHizbStop(ayahText: String): Boolean = HIZB_MARKER in ayahText

/**
 * File position where an automatic advance into this ayah should start.
 * 0 means play the file from the beginning. The returned position is still
 * on the file clock, so segment timings do not move.
 */
internal fun hizbGapEntryMs(hizbStop: Boolean, firstWordStartMs: Long): Long {
    if (!hizbStop || firstWordStartMs < HIZB_STOP_LEAD_MS) return 0L
    return (firstWordStartMs - HIZB_ATTACK_MS).coerceAtLeast(0L)
}

/**
 * Seek target for one position jump, or null when this jump should keep the
 * file start. [automaticItemAdvance] is the playlist moving to the next ayah
 * by itself. A repeat of the same file, a user seek, and a chapter open are
 * not that.
 */
internal fun hizbGapSeekMs(
    automaticItemAdvance: Boolean,
    hizbStop: Boolean,
    firstWordStartMs: Long,
    positionMs: Long,
): Long? {
    if (!automaticItemAdvance) return null
    val entry = hizbGapEntryMs(hizbStop, firstWordStartMs)
    if (entry <= 0L) return null
    if (positionMs + HIZB_SEEK_SLACK_MS >= entry) return null
    return entry
}
