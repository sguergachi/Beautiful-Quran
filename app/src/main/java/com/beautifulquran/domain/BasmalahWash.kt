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
     * Widest wash feather this artwork can carry, as a fraction of its width.
     *
     * `letterFadeIn` runs its gradient one feather *ahead* of the solid front,
     * so the faint edge first touches the far end of the element at progress
     * `1 / (1 + feather)`. A verse word's feather is 1.6× the word — deliberately
     * wider than the word, so a word reads as a breath rather than a wipe. This
     * artwork is **four words plus a kashida** wide, and at 1.6 that first touch
     * lands at 38 % of the clip: the whole basmalah is inked while the reciter is
     * still on ٱللَّهِ, which is why the wash read as a crossfade and looked
     * finished long before the voice was. Capping the feather here puts the first
     * touch of the far end exactly where ٱلرَّحِيمِ begins, so no part of the
     * calligraphy is even faintly washed before its word's turn.
     */
    val MAX_FEATHER: Float = 1f / WORD_END_PROGRESS[WORD_END_PROGRESS.size - 2] - 1f

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
        clipDurationMs: Long = 0L,
        hold: TajweedPacing.Hold? = TajweedPacing.Hold(maddAaridWaqf = true),
    ): Float? {
        if (!timesTheWholeBasmalah(segments)) return null
        val rowMs = onRowClock(positionMs, segments, clipDurationMs)
        val last = segments.last()
        if (rowMs >= last.endMs) return 1f
        val index = segments.indexOfLast { rowMs >= it.startMs }
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
            else ((rowMs - segment.startMs).toFloat() / holdMs).coerceIn(0f, 1f)

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
     * [positionMs] translated onto the timing row's clock, for rows that run
     * past the end of the clip they describe.
     *
     * Hani Ar-Rifai's Al-Fatihah 1:1 row ends 945 ms after his own `001001.mp3`
     * does: a source take slower than the file the app streams, shifted onto the
     * measured onset. Left alone the calligraphy would stall around 87 % with the
     * audio already finished. The onset is measured from *this* file, so it is
     * kept; the rest of the row is stretched across what remains of the clip, so
     * the wash still lands its last word as the voice stops. Rows that fit their
     * audio — every other reciter — pass through untouched.
     */
    private fun onRowClock(
        positionMs: Long,
        segments: List<Segment>,
        clipDurationMs: Long,
    ): Long {
        val startMs = segments.first().startMs
        val rowEndMs = segments.last().endMs
        if (clipDurationMs <= 0L || rowEndMs <= clipDurationMs) return positionMs
        val clipSpanMs = clipDurationMs - startMs
        if (clipSpanMs <= 0L || positionMs <= startMs) return positionMs
        return startMs + ((positionMs - startMs) * (rowEndMs - startMs) / clipSpanMs)
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
