package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.EnglishAlignment
import com.beautifulquran.domain.EnglishPhrase
import com.beautifulquran.domain.EnglishWordSpan
import com.beautifulquran.domain.AudibleSilence

/** One immutable whole-verse presentation, shared by paint and carried-leaf follow. */
internal data class EnglishWashPlan(
    val spans: List<EnglishWordSpan>,
    val windows: Map<Int, EnglishWashWindow>,
    /** These blocks retain legacy occurrence fences rather than wash through an internal rewind. */
    val repeatFallbacks: Int = 0,
    val tailOwners: Map<Int, Int> = emptyMap(),
    /** A source-overlapping partition retains the whole verse's legacy occurrence bands. */
    val sourceOverlapFallbacks: Int = 0,
    val readingFlow: EnglishReadingFlow? = null,
    val readingFlowFallback: String? = null,
)

/** Exposure endpoints of one whole printed word, on the same clock as its soft edge. */
internal fun englishWordExposure(plan: EnglishWashPlan, from: Float, to: Float): EnglishWashWindow? {
    plan.readingFlow?.clock?.let {
        return EnglishWashWindow(it.mediaAt(from, afterGap = true), it.mediaAt(to))
    }
    val span = plan.spans.firstOrNull { to <= it.to + .00001f } ?: return null
    val window = plan.windows[span.position] ?: return null
    fun share(value: Float) = ((value - span.from) / (span.to - span.from)).coerceIn(0f, 1f)
    return EnglishWashWindow(window.mediaAt(share(from), afterGap = true), window.mediaAt(share(to)))
}

/** Published prose shares a steady audible-time clock; locked occurrence modes keep their policy. */
internal fun englishPresentationPlan(
    alignment: EnglishAlignment,
    segments: List<Segment>,
    voiceStartMs: Long = segments.firstOrNull()?.startMs ?: 0L,
    silence: List<AudibleSilence> = emptyList(),
): EnglishWashPlan = englishReadingPlan(alignment, segments,
    englishPhrasePlan(alignment, segments, voiceStartMs), silence, voiceStartMs)

/** Semantic phrase/occurrence anchors; the reading clock joins connected speech over these. */
internal fun englishPhrasePlan(
    alignment: EnglishAlignment,
    segments: List<Segment>,
    voiceStartMs: Long = segments.firstOrNull()?.startMs ?: 0L,
): EnglishWashPlan {
    // Word-card English deliberately retains its existing linear occurrence
    // clock, including silence before the next owner's onset.
    if (alignment.phrases == null) {
        val windows = englishWashWindows(alignment.washSpans.map { it.position }, segments)
            .mapValues { (owner, window) ->
                if (owner == 1) window.copy(startMs = maxOf(window.startMs, voiceStartMs).coerceAtMost(window.endMs))
                else window
            }
        val tailOwners = windows.mapValues { (owner, window) ->
            val first = segments.indexOfFirst { it.position >= owner }
            segments.drop(first).takeWhile { it.startMs < window.endMs }.lastOrNull()?.position ?: owner
        }
        return EnglishWashPlan(alignment.washSpans, windows, tailOwners = tailOwners)
    }
    var highWater = -1
    val repeated = segments.map { segment ->
        (segment.position <= highWater).also { highWater = maxOf(highWater, segment.position) }
    }
    var fallbacks = 0
    // Weak next-clause text can still share a closed phrase's source tail
    // (2:175). Its attachment is ambiguous: retain occurrence fences rather
    // than merge across the clause or clamp that text to an exhausted source.
    val sourceOverlap = alignment.presentationSpans.zipWithNext().any { (previous, next) ->
        previous.lastPosition >= next.position
    }
    val partition = if (sourceOverlap) {
        alignment.washSpans.map { EnglishPhrase(it.from, it.to, it.position) }
    } else alignment.presentationSpans
    val phrases = partition.flatMap { phrase ->
        val first = segments.indexOfFirst { it.position >= phrase.position }
        val last = segments.indexOfFirst { it.position >= phrase.lastPosition }
        if (phrase.lastPosition > phrase.position && first >= 0 && last > first &&
            (first + 1..last).any { repeated[it] }) {
            fallbacks++
            alignment.washSpans.mapNotNull { span ->
                val from = maxOf(span.from, phrase.from)
                val to = minOf(span.to, phrase.to)
                if (to > from) EnglishPhrase(from, to, span.position) else null
            }
        } else listOf(phrase)
    }
    val windows = LinkedHashMap<Int, EnglishWashWindow>()
    val tailOwners = HashMap<Int, Int>()
    var previousEnd = 0L
    phrases.forEachIndexed { index, phrase ->
        val first = segments.indexOfFirst { it.position >= phrase.position }
        if (first < 0) return@forEachIndexed
        val boundary = phrases.getOrNull(index + 1)?.position?.let { maxOf(it, phrase.lastPosition + 1) }
        val next = boundary?.let { at -> segments.indexOfFirst { it.position >= at }.takeIf { it >= 0 } }
            ?: segments.size
        val repeat = (first + 1 until next).firstOrNull { repeated[it] }
        val limit = repeat ?: next
        val fence = maxOf(previousEnd, segments.getOrNull(limit)?.startMs ?: segments.last().endMs)
        val start = maxOf(previousEnd, segments[first].startMs,
            if (phrase.position == 1) voiceStartMs else Long.MIN_VALUE)
        val owners = alignment.spans.filter { it.from < phrase.to && it.to > phrase.from }.map { it.position }.toSet()
        // The terminal residual retains the established verse-tail completion
        // policy (24:2). Ambiguous partitions keep all speech before their
        // legacy fence; ordinary bands use only their represented owners.
        val terminal = index == phrases.lastIndex
        val source = segments.subList(first, limit).filter {
            terminal || sourceOverlap || if (phrase.lastPosition > phrase.position) {
                it.position in phrase.position..phrase.lastPosition
            } else it.position in owners
        }
        val end = if (terminal || sourceOverlap) maxOf(start, fence)
            else minOf(fence, source.maxOfOrNull { it.endMs } ?: start).coerceAtLeast(start)
        val voiced = englishSpokenUnion(source, start, end)
        windows[phrase.position] = EnglishWashWindow(start, end, voiced)
        tailOwners[phrase.position] = source.lastOrNull()?.position ?: phrase.position
        previousEnd = end
    }
    return EnglishWashPlan(
        spans = phrases.map { EnglishWordSpan(it.from, it.to, it.position) },
        windows = windows,
        repeatFallbacks = fallbacks,
        tailOwners = tailOwners,
        sourceOverlapFallbacks = if (sourceOverlap) 1 else 0,
    )
}

/** Segment overlap counts once; gaps remain flat portions of the presentation clock. */
private fun englishSpokenUnion(segments: List<Segment>, start: Long, end: Long): List<LongRange> = buildList {
    for (segment in segments.sortedBy { it.startMs }) {
        val from = maxOf(start, segment.startMs)
        val to = minOf(end, segment.endMs)
        if (to <= from) continue
        val previous = lastOrNull()
        if (previous != null && from <= previous.last) this[lastIndex] = previous.first..maxOf(previous.last, to)
        else add(from..to)
    }
}

/** The same normalized edge used by drawing, without a page-local clock. */
internal fun englishWashThrough(plan: EnglishWashPlan, positionMs: Long): Float? {
    for (span in plan.spans) {
        val window = plan.windows[span.position] ?: return null
        val progress = window.progress(positionMs)
        if (progress < 1f) return span.from + (span.to - span.from) * progress
    }
    return 1f
}
