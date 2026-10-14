package com.beautifulquran.ui.reader

/**
 * A word owns its wash; between words, Media3 owns the verse. Fade-led focus
 * can be missing, ahead or stale during a handoff and must not re-cover read ink.
 */
internal fun mushafInkFrontier(
    activeWordAyah: Int?,
    fadeLedAyah: Int?,
    playingAyah: Int?,
): Int? = activeWordAyah ?: playingAyah ?: fadeLedAyah

/** A null word is a completed tail only after this playback generation spoke. */
internal fun mushafAwaitingFirstWord(
    activeWordAyah: Int?,
    frontierAyah: Int?,
    playingAyah: Int?,
    inkId: Long,
    heardInkId: Long?,
): Boolean = activeWordAyah == null &&
    (frontierAyah != playingAyah || heardInkId != inkId)
