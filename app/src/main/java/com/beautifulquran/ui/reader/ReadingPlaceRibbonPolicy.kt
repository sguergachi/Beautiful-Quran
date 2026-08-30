package com.beautifulquran.ui.reader

/** The verse eligible for a deliberate pause drop; null for an already-paused open. */
internal fun pausedReadingPlaceRibbonAyah(
    renderedSurahId: Int,
    mediaSurahId: Int?,
    mediaAyah: Int?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    playedHere: Boolean,
): Int? = mediaAyah?.takeIf {
        mediaSurahId == renderedSurahId &&
            it >= 1 &&
            !isPlaying &&
            !isBuffering &&
            playedHere
    }
