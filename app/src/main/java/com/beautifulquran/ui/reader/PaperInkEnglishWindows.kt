package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.EnglishAlignment
import com.beautifulquran.domain.EnglishWordSpan
import kotlin.math.roundToLong

/** Keep a future English word fully unread until the continuous wash reaches it. */
internal fun englishPaperInkWordProgress(washEdge: Float, start: Int, end: Int): Float =
    if (washEdge < start) -1f else ((washEdge - start) / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f)

/** Split the reader's continuous English bands into word clocks for the material study. */
internal fun paperInkEnglishWindows(
    ranges: List<IntRange>,
    textLength: Int,
    alignment: EnglishAlignment?,
    segments: List<Segment>,
): List<EnglishWashWindow> {
    val bands = alignment?.washSpans?.takeIf { it.isNotEmpty() }
        ?: listOf(EnglishWordSpan(0f, 1f, segments.first().position))
    val windows = englishWashWindows(bands.map { it.position }, segments)
    return ranges.mapIndexed { index, range ->
        val midpoint = (range.first + range.last + 1f) / (2 * textLength)
        val band = bands.firstOrNull { midpoint < it.to } ?: bands.last()
        val window = windows.getValue(band.position)
        fun at(character: Int): Long {
            val share = ((character.toFloat() / textLength - band.from) / (band.to - band.from))
                .coerceIn(0f, 1f)
            return window.startMs + ((window.endMs - window.startMs) * share).roundToLong()
        }
        EnglishWashWindow(at(if (index == 0) 0 else ranges[index - 1].last + 1), at(range.last + 1))
    }
}
