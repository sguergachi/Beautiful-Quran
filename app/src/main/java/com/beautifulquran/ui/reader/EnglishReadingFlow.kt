package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.AudibleSilence
import com.beautifulquran.domain.EnglishAlignment

/** One cumulative audible-time clock across a safe forward reading run. */
internal data class EnglishReadingFlow(val clock: EnglishWashWindow)

/** Geometry partitions share the run's rate; Arabic owners do not restart its speed. */
internal data class EnglishReadingWindow(val flow: EnglishReadingFlow, val from: Float, val to: Float) {
    fun progress(positionMs: Long): Float =
        ((flow.clock.progress(positionMs) - from) / (to - from)).coerceIn(0f, 1f)

    fun mediaAt(progress: Float, afterGap: Boolean): Long =
        flow.clock.mediaAt(from + (to - from) * progress, afterGap)
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
    val flow = EnglishReadingFlow(EnglishWashWindow(start, end, audible))
    val windows = anchors.spans.associate { span ->
        span.position to EnglishWashWindow(
            flow.clock.mediaAt(span.from, afterGap = true), flow.clock.mediaAt(span.to),
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
