package com.beautifulquran.playback

import com.beautifulquran.data.model.Segment

/**
 * A recording of the decimated analysis stream produced by
 * [VoiceEnergy]'s PCM tap — the exact hops the tarjīʿ detector eats, kept
 * for offline replay and re-analysis in the Tarjīʿ Lab.
 *
 * Hops are stored as concatenated [hopSamples]-sized chunks in [pcm] at
 * [sampleRate] (≈8 kHz, one hop ≈ 20 ms of content — see [Tarji.HOP_MS]).
 * [hopContentMs] holds the tap-session content time at the *end* of each
 * hop, so media positions are recoverable by mapping the first hop once
 * (see [TarjiLabTrim]) and offsetting linearly from there; the analysis is
 * hop-domain and does not need them at all.
 *
 * Pure data: produced by the audio thread, consumed (and re-analyzed) by
 * the lab — no Android dependencies.
 */
class TarjiLabCapture(
    val sampleRate: Int,
    val hopSamples: Int,
    /** Content ms at the end of each hop, relative to the tap session start. */
    val hopContentMs: FloatArray,
    /** Concatenated decimated hop chunks; size = [hopCount] × [hopSamples]. */
    val pcm: FloatArray,
) {
    val hopCount: Int
        get() = hopContentMs.size

    /** The capture's total content duration (ms). */
    val totalContentMs: Float
        get() = if (hopCount == 0) 0f else hopContentMs[hopCount - 1] - hopContentMs[0] +
            hopContentDurationMs()

    /** Content duration of a single hop, measured between neighbouring
     * timestamps when available (the decimated stream may not be exactly
     * [Tarji.HOP_MS] — 44.1 kHz ÷ 6 = 7.35 kHz). */
    fun hopContentDurationMs(): Float = when {
        hopCount >= 2 -> hopContentMs[1] - hopContentMs[0]
        hopSamples > 0 -> hopSamples * 1000f / sampleRate
        else -> Tarji.HOP_MS.toFloat()
    }

    /** Media-clock position of hop [i], given the first hop's position. */
    fun hopMediaMs(i: Int, firstHopMediaMs: Double): Double =
        firstHopMediaMs + (hopContentMs[i] - hopContentMs[0])

    /** A copy holding only hops [range] (closed). Used by the lab to trim a
     * capture to the word span before looping. */
    fun slice(range: IntRange): TarjiLabCapture {
        val first = range.first.coerceIn(0, hopCount - 1)
        val last = range.last.coerceIn(first, hopCount - 1)
        val n = last - first + 1
        val slicedContent = FloatArray(n)
        System.arraycopy(hopContentMs, first, slicedContent, 0, n)
        val slicedPcm = FloatArray(n * hopSamples)
        System.arraycopy(pcm, first * hopSamples, slicedPcm, 0, n * hopSamples)
        return TarjiLabCapture(sampleRate, hopSamples, slicedContent, slicedPcm)
    }
}

/**
 * Pure trim helpers for the Tarjīʿ Lab: where a word lives in the captured
 * stream, and which capture hops fall inside it.
 */
object TarjiLabTrim {

    /** The word's full spoken span on the media clock: from its first mark's
     * start to its last mark's end, widened by [leadMs] before and [tailMs]
     * after. Repeats of the same word are included (the whole re-say).
     *
     * A word without marks spans the gap between its neighbours' marks, so
     * the lab can still capture it; with no marks at all in the ayah there is
     * nothing to anchor to and the span is null. */
    fun wordSpanMs(
        segments: List<Segment>,
        wordPosition: Int,
        leadMs: Long,
        tailMs: Long,
    ): LongRange? {
        val marks = segments.filter { it.position == wordPosition }
        if (marks.isNotEmpty()) {
            val start = marks.minOf { it.startMs } - leadMs
            val end = marks.maxOf { it.endMs } + tailMs
            if (end <= start) return null
            return start..end
        }
        val before = segments.filter { it.position < wordPosition }.maxByOrNull { it.endMs }
        val after = segments.filter { it.position > wordPosition }.minByOrNull { it.startMs }
        if (before == null && after == null) return null
        val s = before?.endMs ?: (after!!.startMs - 1_500L)
        val e = after?.startMs ?: (before!!.endMs + 1_500L)
        if (e <= s) return null
        return (s - leadMs)..(e + tailMs)
    }

    /** Closed hop range of the capture whose media positions fall inside
     * [spanMs] (given the first hop's media position). Empty when no hop
     * lands in the span; a zero-length capture returns an empty range. */
    fun hopRangeInSpan(
        capture: TarjiLabCapture,
        firstHopMediaMs: Double,
        spanMs: LongRange,
    ): IntRange {
        if (capture.hopCount == 0) return IntRange.EMPTY
        var first = -1
        var last = -1
        for (i in capture.hopContentMs.indices) {
            val media = capture.hopMediaMs(i, firstHopMediaMs)
            if (media >= spanMs.first) {
                if (first < 0) first = i
                if (media <= spanMs.last) last = i
            }
        }
        return if (first < 0 || last < 0) IntRange.EMPTY else first..last
    }
}
