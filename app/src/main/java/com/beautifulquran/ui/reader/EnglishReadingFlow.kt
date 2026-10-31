package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.AudibleSilence
import com.beautifulquran.domain.EnglishAlignment
import com.beautifulquran.domain.TajweedPacing
import kotlin.math.roundToLong

/** One cumulative audible-time clock across a safe forward reading run. */
internal data class EnglishReadingFlow(
    val clock: EnglishWashWindow,
    val extent: EnglishReadingExtent? = null,
    val extentFallback: String? = null,
    val pace: EnglishReadingPace? = null,
    /** The reciter's tajweed tempo inside each spoken word; null is a steady clock. */
    val tempo: EnglishTempoWarp? = null,
) {
    fun through(positionMs: Long): Float = clock.progress(tempo?.forward(positionMs) ?: positionMs)
        .let { pace?.through(it) ?: it }
        .let { extent?.printed(it) ?: it }
    fun ink(through: Float): Float = extent?.ink(through) ?: through
    fun mediaAt(through: Float, afterGap: Boolean = false): Long = ink(through).let {
        clock.mediaAt(pace?.audible(it) ?: it, afterGap).let { at -> tempo?.inverse(at) ?: at }
    }
}

/**
 * Tajweed tempo on the media clock. Each spoken Arabic word's span maps onto
 * itself through [TajweedPacing.Curve.tempo], so the English edge slows where
 * the reciter holds a letter and quickens through short ones, yet every
 * word boundary, pause and deadline lands at the same media time.
 */
internal class EnglishTempoWarp(private val pieces: List<Piece>) {
    class Piece(val startMs: Long, val endMs: Long, val curve: TajweedPacing.Curve)

    private fun piece(ms: Long): Piece? {
        var low = 0
        var high = pieces.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (pieces[mid].startMs <= ms) low = mid + 1 else high = mid
        }
        return pieces.getOrNull(low - 1)?.takeIf { ms < it.endMs }
    }

    fun forward(ms: Long): Long {
        val p = piece(ms) ?: return ms
        val span = p.endMs - p.startMs
        return p.startMs + (p.curve.at((ms - p.startMs).toFloat() / span) * span).roundToLong().coerceIn(0, span)
    }

    fun inverse(ms: Long): Long {
        val p = piece(ms) ?: return ms
        val span = p.endMs - p.startMs
        val target = (ms - p.startMs).toFloat() / span
        var low = 0f
        var high = 1f
        repeat(24) {
            val mid = (low + high) / 2
            if (p.curve.at(mid) < target) low = mid else high = mid
        }
        return p.startMs + (high * span).roundToLong().coerceIn(0, span)
    }

    companion object {
        /** Words that overlap a neighbour or hold measured quiet keep the steady clock. */
        fun of(
            segments: List<Segment>,
            silence: List<AudibleSilence>,
            tempo: (Segment) -> TajweedPacing.Curve?,
        ): EnglishTempoWarp? {
            val sorted = segments.sortedBy { it.startMs }
            val pieces = sorted.mapIndexedNotNull { i, s ->
                if (s.endMs <= s.startMs) return@mapIndexedNotNull null
                if (sorted.getOrNull(i - 1)?.let { it.endMs > s.startMs } == true) return@mapIndexedNotNull null
                if (sorted.getOrNull(i + 1)?.let { it.startMs < s.endMs } == true) return@mapIndexedNotNull null
                if (silence.any { it.startMs < s.endMs && it.endMs > s.startMs }) return@mapIndexedNotNull null
                tempo(s)?.let { Piece(s.startMs, s.endMs, it) }
            }
            return pieces.takeIf { it.isNotEmpty() }?.let(::EnglishTempoWarp)
        }
    }
}

/** Geometry partitions share the run's rate; Arabic owners do not restart its speed. */
internal data class EnglishReadingWindow(val flow: EnglishReadingFlow, val from: Float, val to: Float) {
    fun progress(positionMs: Long): Float =
        ((flow.through(positionMs) - from) / (to - from)).coerceIn(0f, 1f)

    fun mediaAt(progress: Float, afterGap: Boolean): Long =
        flow.mediaAt(from + (to - from) * progress, afterGap)
}

/**
 * Steady motion through connected speech, holding the exact reached prefix in
 * measured quiet. A pause is not evidence for a different English word boundary.
 */
internal fun englishReadingPlan(
    alignment: EnglishAlignment,
    segments: List<Segment>,
    anchors: EnglishWashPlan,
    silence: List<AudibleSilence>,
    voiceStartMs: Long,
    tempo: EnglishTempoWarp? = null,
): EnglishWashPlan {
    if (alignment.phrases == null) return anchors
    fun fallback(reason: String) = anchors.copy(readingFlowFallback = reason)
    if (anchors.sourceOverlapFallbacks > 0) return fallback("source_overlap")
    if (segments.zipWithNext().any { (a, b) -> b.position <= a.position }) return fallback("source_repeat")
    if (anchors.spans.isEmpty() || anchors.spans.any {
        anchors.windows[it.position]?.let { window -> window.endMs <= window.startMs } != false
    }) return fallback("source_window")
    val start = maxOf(segments.first().startMs, voiceStartMs)
    val end = anchors.windows.getValue(anchors.spans.last().position).endMs
    // Every source owner contributes speech, including swallowed/weak owners.
    val speech = normalizedIntervals(segments.map { it.startMs..it.endMs }, start, end)
    val quiet = normalizedIntervals(silence.map { it.startMs..it.endMs }, start, end)
    val audible = buildList {
        for (span in speech) {
            var cursor = span.first
            for (pause in quiet) {
                if (pause.last <= cursor) continue
                if (pause.first >= span.last) break
                if (pause.first > cursor) add(cursor..minOf(pause.first, span.last))
                cursor = maxOf(cursor, pause.last)
                if (cursor >= span.last) break
            }
            if (cursor < span.last) add(cursor..span.last)
        }
    }
    if (audible.isEmpty()) return fallback("zero_audible_time")
    val text = alignment.readingText
    // The leaf normalizes whitespace. Its legacy letter interpolation cannot
    // project a discontinuous jump across a differently sized source gap.
    val normalizedSpacing = text != null && (text.firstOrNull()?.isWhitespace() == true ||
        text.lastOrNull()?.isWhitespace() == true || text.zipWithNext().any { (a, b) -> a.isWhitespace() && b.isWhitespace() })
    val extent = text?.takeUnless { normalizedSpacing }?.let(::EnglishReadingExtent)
    if (extent?.hasInk == false) return fallback("zero_printed_ink")
    val clock = EnglishWashWindow(start, end, audible)
    val flow = EnglishReadingFlow(clock, extent, if (normalizedSpacing) "normalized_spacing" else null,
        englishReadingPace(alignment, segments, clock, extent) { clock.progress(tempo?.forward(it) ?: it) }, tempo)
    val windows = anchors.spans.associate { span ->
        span.position to EnglishWashWindow(
            flow.mediaAt(span.from, afterGap = true), flow.mediaAt(span.to),
            reading = EnglishReadingWindow(flow, span.from, span.to),
        )
    }
    if (windows.values.any { it.endMs <= it.startMs }) return fallback("zero_reading_window")
    val tails = windows.mapValues { (owner, window) ->
        segments.lastOrNull { it.startMs < window.endMs }?.position ?: owner
    }
    return anchors.copy(windows = windows, tailOwners = tails, readingFlow = flow)
}

/** Half-open media intervals, clipped and unioned once before subtracting quiet. */
private fun normalizedIntervals(input: List<LongRange>, start: Long, end: Long): List<LongRange> = buildList {
    for (span in input.sortedBy { it.first }) {
        val from = maxOf(start, span.first)
        val to = minOf(end, span.last)
        if (to <= from) continue
        val previous = lastOrNull()
        if (previous != null && from <= previous.last) this[lastIndex] = previous.first..maxOf(previous.last, to)
        else add(from..to)
    }
}
