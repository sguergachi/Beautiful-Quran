package com.beautifulquran.ui.reader

/** A parked verse keeps its chapter identity through in-place reader handoffs. */
internal data class ReadingPlace(val surahId: Int, val ayah: Int)

internal fun readingPlace(surahId: Int, ayah: Int): ReadingPlace? =
    ReadingPlace(surahId, ayah).takeIf { surahId in 1..114 && ayah >= 1 }

internal fun ReadingPlace?.ayahIn(renderedSurahId: Int): Int? =
    this?.ayah?.takeIf { surahId == renderedSurahId }

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
