package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.EnglishAlignment
import com.beautifulquran.domain.HighlightFrame
import com.beautifulquran.domain.AudibleSilence

/** The same calibrated, seek-aware media sample that selects the Arabic word. */
internal data class EnglishWashClock(
    val surahId: Int,
    val ayah: Int,
    val activation: Long,
    val positionMs: Long,
    val voiceStartMs: Long,
    val segments: List<Segment> = emptyList(),
    val reciterId: Int? = null,
    val silence: List<AudibleSilence> = emptyList(),
) {
    /** Project only this performance; all time policy lives in HighlightSync. */
    fun onFrame(frame: HighlightFrame?): EnglishWashClock =
        if (frame != null && frame.item.surah == surahId && frame.item.ayah == ayah &&
            frame.inkGeneration == activation && (reciterId == null || frame.item.reciter == reciterId)) copy(positionMs = frame.inkMs) else this

    fun samePerformanceAs(other: EnglishWashClock?): Boolean =
        other != null && surahId == other.surahId && ayah == other.ayah && activation == other.activation

    fun matches(word: ActiveWord, surahId: Int): Boolean =
        this.surahId == surahId && ayah == word.ayah && activation == word.playbackActivation

    /** Re-says locate their semantic word, rather than the retained forward prefix. */
    fun followsFirstWash(word: ActiveWord, surahId: Int): Boolean = matches(word, surahId) && !word.isRepeat
}

/**
 * Reordering can exhaust English bands before Arabic words (24:2 ends in
 * punishment, then Arabic continues with a group of believers). Keep the final
 * band moving through that tail. A backward repeat starts a second wash, so
 * the first wash must have finished before it. Ownership for taps is unchanged.
 */
internal fun englishWashWindows(
    positions: List<Int>,
    segments: List<Segment>,
): Map<Int, EnglishWashWindow> = buildMap {
    var highWater = -1
    val repeats = segments.map {
        val repeated = it.position <= highWater
        highWater = maxOf(highWater, it.position)
        repeated
    }
    positions.forEachIndexed { index, position ->
        val first = segments.indexOfFirst { it.position >= position }
        if (first < 0) return@forEachIndexed
        val start = segments[first].startMs
        val next = positions.getOrNull(index + 1)?.let { boundary ->
            segments.indexOfFirst { it.position >= boundary }.takeIf { it >= 0 }
        } ?: segments.size
        val repeat = (first + 1 until next).firstOrNull { repeats[it] }
        val end = repeat?.let { segments[it].startMs }
            ?: segments.getOrNull(next)?.startMs ?: segments.last().endMs
        if (end >= start) put(position, EnglishWashWindow(start, end))
    }
}

/** A word onset closes preceding ink before its repeat; stale seek samples still wait. */
internal fun englishWashPositionMs(word: ActiveWord, clock: EnglishWashClock?, surahId: Int): Long =
    maxOf(word.startMs, clock?.takeIf { it.matches(word, surahId) }?.positionMs ?: word.startMs)

/** The same edge in whole-verse coordinates, for carried-leaf ownership and follow. */
internal fun englishWashThrough(
    alignment: EnglishAlignment,
    windows: Map<Int, EnglishWashWindow>,
    positionMs: Long,
    voiceStartMs: Long,
): Float? {
    for (span in alignment.washSpans) {
        val window = windows[span.position] ?: return null
        val progress = window.progress(positionMs, if (span.position == 1) voiceStartMs else Long.MIN_VALUE)
        if (progress < 1f) return span.from + (span.to - span.from) * progress
    }
    return 1f
}

/** Project one band's clock through a page cut without restarting it on the next leaf. */
internal fun englishWashFragmentProgress(span: EnglishInkSpan, progress: Float, project: (Float) -> Float): Float {
    val from = project(span.from)
    val to = project(span.to)
    if (to <= from) return 0f
    return ((project(span.from + (span.to - span.from) * progress) - from) / (to - from)).coerceIn(0f, 1f)
}

/** How much of a semantic tint lies behind the continuous wash's soft edge. */
internal fun englishInkLayerProgress(range: IntRange, washEdge: Float): Float =
    ((washEdge - range.first) / (range.last + 1 - range.first)).coerceIn(0f, 1f)
