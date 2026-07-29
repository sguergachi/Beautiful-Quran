package com.beautifulquran.domain

import com.beautifulquran.data.model.Segment

/**
 * Where the ink wash across the basmalah calligraphy stands at a given moment
 * of the lead-in clip — the same word↔voice lock the verse words have, applied
 * to a single piece of artwork.
 *
 * The clip is Al-Fatihah 1:1 (`001001.mp3`), so its four word timings are
 * already in the DB and are the honest clock: the wash crosses ٱللَّهِ when the
 * reciter says "-llāhi", not a fixed fraction of the file. Two things make that
 * more than a linear ramp:
 *
 * - **Word bands.** The Naskh composition is not proportional to time. Its
 *   elongated sīn (the long kashida) gives بِسۡمِ over half the width for a
 *   ~0.5 s syllable, while ٱلرَّحِيمِ — where the reciter spends more than half
 *   the clip — occupies less than a quarter. Each word therefore owns the band
 *   of artwork its glyphs actually cover ([WORD_END_PROGRESS]); the edge
 *   sweeps out along the kashida and then ambles through the dense cluster.
 * - **Tajweed inside the band.** With a [TajweedPacing.Hold], the wash parks
 *   where the voice parks: the madd of ٱلرَّحۡمَٰنِ, and the madd ʿāriḍ of the
 *   closing ٱلرَّحِيمِ, which is most of the clip's dwell
 *   ([TajweedPacing.Hold.maddAaridWaqf]).
 *
 * Pure Kotlin, unit-tested on the JVM. Progress is monotone in [positionMs]:
 * the bands ascend and [TajweedPacing.Curve] is monotone, so no frame can move
 * the wash backwards (docs/DESIGN.md — the wash never resets mid-animation).
 */
object BasmalahWash {

    /** The four timed words of the basmalah, right to left (Al-Fatihah 1:1). */
    val WORDS: List<String> = BASMALAH_UTHMANI.split(' ')

    /**
     * Left edge of each word's ink in the calligraphy, in artwork viewport
     * units (`R.drawable.basmalah_naskh` / the web SVG, `viewportWidth` 608).
     * Reading is right to left, so x 608 is where بِسۡمِ starts and x 0 is where
     * ٱلرَّحِيمِ ends. Measured off the rendered artwork by cropping at each
     * candidate boundary until every band read as exactly one word; the
     * calligraphy's connecting strokes overlap by a unit or two, which the wide
     * wash feather absorbs.
     */
    val WORD_LEFT_EDGE_X = intArrayOf(260, 220, 140, 0)

    /** Artwork width in the same viewport units. */
    const val ARTWORK_WIDTH_X = 608

    /**
     * Wash progress (0 = right edge of the artwork, 1 = left) at which each
     * word's band ends — derived from [WORD_LEFT_EDGE_X].
     */
    val WORD_END_PROGRESS: FloatArray = FloatArray(WORD_LEFT_EDGE_X.size) { i ->
        (ARTWORK_WIDTH_X - WORD_LEFT_EDGE_X[i]).toFloat() / ARTWORK_WIDTH_X
    }

    /**
     * Wash progress 0..1 at [positionMs] of the lead-in clip, or null when
     * [segments] cannot time the four words (missing / repeated / non-monotone
     * timings) and the caller should fall back to the plain clip ramp.
     *
     * [hold] paces each word's band by tajweed; null takes the plain sweep
     * inside every band (Ink Lab's pacing toggle off).
     * [TajweedPacing.Hold.isAyahFinal] is set per word here — only ٱلرَّحِيمِ
     * closes the clip.
     */
    fun progress(
        positionMs: Long,
        segments: List<Segment>,
        hold: TajweedPacing.Hold? = TajweedPacing.Hold(maddAaridWaqf = true),
    ): Float? {
        if (!timesTheWholeBasmalah(segments)) return null
        val last = segments.last()
        if (positionMs >= last.endMs) return 1f
        val index = segments.indexOfLast { positionMs >= it.startMs }
        // Before the voice: encoded opening silence, and any reciter whose
        // basmalah starts a second into the file (audio_onset_ms).
        if (index < 0) return 0f

        val segment = segments[index]
        val bandStart = if (index == 0) 0f else WORD_END_PROGRESS[index - 1]
        val bandEnd = WORD_END_PROGRESS[index]
        // Karaoke hold: a word owns the gap until the next word starts, so the
        // ink settles into its band rather than stalling short of it.
        val holdEndMs = segments.getOrNull(index + 1)?.startMs ?: segment.endMs
        val holdMs = (holdEndMs - segment.startMs).coerceAtLeast(0L)
        val phase =
            if (holdMs <= 0L) 1f
            else ((positionMs - segment.startMs).toFloat() / holdMs).coerceIn(0f, 1f)

        val curve = hold?.let {
            TajweedPacing.curve(
                arabic = WORDS[index],
                spokenFraction =
                    if (holdMs <= 0L) 1f
                    else ((segment.endMs - segment.startMs).toFloat() / holdMs).coerceIn(0f, 1f),
                hold = it.copy(isAyahFinal = index == segments.lastIndex),
                prevArabic = WORDS.getOrNull(index - 1),
            )
        }
        val inBand = (curve?.at(phase) ?: phase).coerceIn(0f, 1f)
        return bandStart + (bandEnd - bandStart) * inBand
    }

    /**
     * Whether [segments] are the four basmalah words in order, once each, on a
     * non-decreasing clock — the shape [progress] maps onto the artwork. A
     * reciter who repeats a word inside the basmalah, or timings that are still
     * loading, take the plain ramp instead of a wash that jumps bands.
     */
    private fun timesTheWholeBasmalah(segments: List<Segment>): Boolean {
        if (segments.size != WORDS.size) return false
        var previousEnd = Long.MIN_VALUE
        for ((i, segment) in segments.withIndex()) {
            if (segment.position != i + 1) return false
            if (segment.startMs < previousEnd || segment.endMs < segment.startMs) return false
            previousEnd = segment.endMs
        }
        return true
    }
}
