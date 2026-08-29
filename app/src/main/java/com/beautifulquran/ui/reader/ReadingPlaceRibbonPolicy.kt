package com.beautifulquran.ui.reader

/** The parked place is replaced only by a real paused media verse on this chapter. */
internal fun readingPlaceRibbonAyah(
    parkedAyah: Int?,
    renderedSurahId: Int,
    mediaSurahId: Int?,
    mediaAyah: Int?,
    isPlaying: Boolean,
    isBuffering: Boolean,
): Int? = mediaAyah
    ?.takeIf {
        mediaSurahId == renderedSurahId &&
            it >= 1 &&
            !isPlaying &&
            !isBuffering
    }
    ?: parkedAyah
