package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment

/**
 * Pure transport skip rules for the reader fast-forward control.
 *
 * Long ayahs (≥ [LONG_AYAH_MIN_WORDS] segments) offer a one-shot midpoint skip
 * to the first word at/after half the timed span. The midpoint is consumed by
 * **intent** (we already issued that seek), not by waiting for [positionMs] to
 * catch up — Media3 seeks are async, so a second tap while position is still
 * pre-midpoint must advance to the next ayah rather than re-seeking the same
 * midpoint forever.
 *
 * "Past halfway" uses the same time-based midpoint so natural play past half
 * the ayah advances to the next verse on the first FF (#560).
 */
internal object FastForwardPolicy {

    sealed class Action {
        data class SeekToMidpoint(val ayah: Int, val positionMs: Long) : Action()
        data class SeekToAyah(val ayah: Int) : Action()
        data object None : Action()
    }

    fun action(
        ayah: Int,
        positionMs: Long,
        ayahCount: Int,
        midpointMs: Long?,
        /** Ayah number that already received a midpoint skip, or 0. */
        midpointConsumedForAyah: Int,
        graceMs: Long = MIDPOINT_SEEK_GRACE_MS,
    ): Action {
        if (ayah < 1) return Action.None
        if (
            midpointMs != null &&
            midpointConsumedForAyah != ayah &&
            positionMs < midpointMs - graceMs
        ) {
            return Action.SeekToMidpoint(ayah, midpointMs)
        }
        if (ayah < ayahCount) return Action.SeekToAyah(ayah + 1)
        return Action.None
    }

    /** After [action], the ayah marked as midpoint-consumed (0 if cleared). */
    fun nextConsumedAyah(action: Action): Int = when (action) {
        is Action.SeekToMidpoint -> action.ayah
        is Action.SeekToAyah, Action.None -> 0
    }

    /**
     * Start of the first segment at or after half the ayah's timed span, or
     * null when the ayah is too short for a mid-skip.
     */
    fun midpointMs(segments: List<Segment>): Long? {
        if (segments.size < LONG_AYAH_MIN_WORDS) return null
        val end = segments.maxOf { it.endMs }
        if (end <= 0L) return segments[segments.size / 2].startMs
        val half = end / 2L
        return segments.firstOrNull { it.startMs >= half }?.startMs
            ?: segments.last().startMs
    }

    const val LONG_AYAH_MIN_WORDS = 20
    const val MIDPOINT_SEEK_GRACE_MS = 1_000L
}
