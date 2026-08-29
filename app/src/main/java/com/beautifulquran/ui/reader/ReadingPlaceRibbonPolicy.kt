package com.beautifulquran.ui.reader

/** A real paused media verse on this chapter; null for live or unrelated media. */
internal fun pausedReadingPlaceRibbonAyah(
    renderedSurahId: Int,
    mediaSurahId: Int?,
    mediaAyah: Int?,
    isPlaying: Boolean,
    isBuffering: Boolean,
): Int? = mediaAyah?.takeIf {
        mediaSurahId == renderedSurahId &&
            it >= 1 &&
            !isPlaying &&
            !isBuffering
    }

/** Pause temporarily replaces the visit's parked green ribbon. */
internal fun readingPlaceRibbonAyah(
    parkedAyah: Int?,
    renderedSurahId: Int,
    mediaSurahId: Int?,
    mediaAyah: Int?,
    isPlaying: Boolean,
    isBuffering: Boolean,
): Int? = pausedReadingPlaceRibbonAyah(
    renderedSurahId = renderedSurahId,
    mediaSurahId = mediaSurahId,
    mediaAyah = mediaAyah,
    isPlaying = isPlaying,
    isBuffering = isBuffering,
) ?: parkedAyah
