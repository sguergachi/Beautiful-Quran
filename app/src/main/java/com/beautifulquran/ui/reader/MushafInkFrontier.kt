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
