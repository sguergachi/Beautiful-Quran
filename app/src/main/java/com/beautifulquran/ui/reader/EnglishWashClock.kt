package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.EnglishAlignment

/** The same calibrated, seek-aware media sample that selects the Arabic word. */
internal data class EnglishWashClock(
    val surahId: Int,
    val ayah: Int,
    val activation: Long,
    val positionMs: Long,
    val voiceStartMs: Long,
    val segments: List<Segment> = emptyList(),
) {
    fun matches(word: ActiveWord, surahId: Int): Boolean =
        this.surahId == surahId && ayah == word.ayah && activation == word.playbackActivation

    /** A calibration change cannot lift laid ink; a new seek generation can. */
    fun after(previous: EnglishWashClock?): EnglishWashClock =
        if (previous != null && previous.surahId == surahId && previous.ayah == ayah &&
            previous.activation == activation && previous.segments === segments) {
            copy(positionMs = maxOf(previous.positionMs, positionMs))
        } else this
}

/** One continuous English band lasts until the next band, or the recitation's end. */
internal data class EnglishWashWindow(val startMs: Long, val endMs: Long) {
    fun progress(positionMs: Long, voiceStartMs: Long = startMs): Float {
        val start = maxOf(startMs, voiceStartMs).coerceAtMost(endMs)
        if (positionMs < start) return 0f
        if (positionMs >= endMs) return 1f
        return (positionMs - start).toFloat() / (endMs - start)
    }
}

/**
 * Reordering can exhaust English bands before Arabic words (24:2 ends in
 * punishment, then Arabic continues with a group of believers). Keep the final
 * band moving through that tail. Repeats inside a band are part of its time;
 * later re-says cannot lift ink already laid. Ownership for taps is unchanged.
 */
internal fun englishWashWindows(
    positions: List<Int>,
    segments: List<Segment>,
): Map<Int, EnglishWashWindow> = buildMap {
    positions.forEachIndexed { index, position ->
        val start = segments.firstOrNull { it.position >= position }?.startMs ?: return@forEachIndexed
        val next = positions.getOrNull(index + 1)
        val end = next?.let { boundary -> segments.firstOrNull { it.position >= boundary }?.startMs }
            ?: segments.lastOrNull()?.endMs ?: return@forEachIndexed
        if (end >= start) put(position, EnglishWashWindow(start, end))
    }
}

/** A tap waits for its own clock generation; a stale seek sample cannot finish its wash. */
internal fun englishWashPositionMs(word: ActiveWord, clock: EnglishWashClock?, surahId: Int): Long =
    clock?.takeIf { it.matches(word, surahId) }?.positionMs ?: word.startMs

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
