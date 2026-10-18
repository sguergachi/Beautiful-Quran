package com.beautifulquran.domain

import com.beautifulquran.data.model.Segment

/**
 * Silence shorter than this is left alone. A seek that close to a word
 * costs more than the quiet it removes.
 */
internal const val SILENCE_SLACK_MS = 40L

/** Rubʿ al-ḥizb mark in Uthmani text (۞). */
internal const val HIZB_MARKER = '\u06DE'

/** Murattal gaps at a ۞ are silence. Mujawwad waqf and Muallim pauses stay. */
internal fun silenceSkipAllowed(style: String): Boolean = style == "Murattal"

internal fun isHizbStop(ayahText: String): Boolean = HIZB_MARKER in ayahText

/**
 * Where a hizb verse's opening should land, or null when this playhead
 * should keep going. Ordinary verses return null: their breath plays through.
 * The landing is the first word's voice, on the file clock.
 */
internal fun hizbOpeningSeekMs(
    hizbStop: Boolean,
    positionMs: Long,
    segments: List<Segment>,
    silence: List<AudibleSilence>,
): Long? {
    if (!hizbStop) return null
    val voice = firstWordHighlightMs(segments, silence)
    val lastEnd = segments.lastOrNull()?.endMs ?: return null
    if (voice >= lastEnd) return null
    if (positionMs + SILENCE_SLACK_MS >= voice) return null
    return voice
}

/**
 * Quiet that the word timings still call a sounding word.
 *
 * The onset scan stores a few-millisecond click as the first letter, and the
 * verse itself starts seconds later, inside that word. A pause drawn across
 * a word boundary has the same shape. [endMs] is the next voiced sample.
 */
internal data class AudibleSilence(val startMs: Long, val endMs: Long)

/** `surah * 1000 + ayah` for the measured-silence table. Ayahs stop at 286. */
internal fun silenceKey(surah: Int, ayah: Int): Int = surah * 1_000 + ayah

/**
 * One reciter's table. Lines are `surah ayah startMs endMs`. A blank or `#`
 * line is ignored. Malformed lines are skipped so a torn asset cannot throw
 * on the playback poll.
 */
internal fun parseAudibleSilence(text: String): Map<Int, List<AudibleSilence>> {
    val out = HashMap<Int, MutableList<AudibleSilence>>()
    for (line in text.lineSequence()) {
        if (line.isEmpty() || line[0] == '#') continue
        val parts = line.split(' ')
        if (parts.size != 4) continue
        val surah = parts[0].toIntOrNull() ?: continue
        val ayah = parts[1].toIntOrNull() ?: continue
        val start = parts[2].toLongOrNull() ?: continue
        val end = parts[3].toLongOrNull() ?: continue
        if (end <= start) continue
        out.getOrPut(silenceKey(surah, ayah)) { mutableListOf() }
            .add(AudibleSilence(start, end))
    }
    return out
}

/** The silence span that contains [timeMs], if one does. */
internal fun spanCovering(silence: List<AudibleSilence>, timeMs: Long): AudibleSilence? {
    var found: AudibleSilence? = null
    for (span in silence) {
        val current = found
        if (timeMs >= span.startMs && timeMs < span.endMs &&
            (current == null || span.endMs > current.endMs)
        ) {
            found = span
        }
    }
    return found
}

/**
 * When the first word's highlight starts.
 *
 * The stored start is a click when an opening silence span still covers it
 * and the voice returns before that word ends. The wash then runs from the
 * voice to the same hold the timing table already uses. A word clock that
 * already meets the voice, or a voice that returns in a later word, is left
 * alone.
 */
internal fun firstWordHighlightMs(
    segments: List<Segment>,
    silence: List<AudibleSilence>,
): Long {
    val first = segments.firstOrNull() ?: return 0L
    val quiet = spanCovering(silence, first.startMs) ?: return first.startMs
    val voice = quiet.endMs
    if (voice <= first.startMs + SILENCE_SLACK_MS) return first.startMs
    val holdEnd = segments.getOrNull(1)?.startMs ?: first.endMs
    if (voice >= first.endMs || voice >= holdEnd) return first.startMs
    return voice
}

/**
 * Wash start for the word at [positionMs], or null while the playhead is
 * still in the quiet before the first word's voice. Later words keep their
 * stored starts.
 */
internal fun openingWashStartMs(
    startMs: Long,
    position: Int,
    segments: List<Segment>,
    positionMs: Long,
    voiceMs: Long,
): Long? {
    val first = segments.firstOrNull() ?: return startMs
    val opening = position == first.position && startMs == first.startMs
    if (!opening) return startMs
    if (positionMs < voiceMs) return null
    return voiceMs
}
