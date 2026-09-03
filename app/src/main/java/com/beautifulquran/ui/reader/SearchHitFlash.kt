package com.beautifulquran.ui.reader

/**
 * Timing for the cover-sheet search-hit flash: the same orange directional
 * wash the repeat overlay uses — wash in, dissolve out — twice. Durations
 * come from [InkEngine.tuning] so the pulse stays in lockstep with real
 * repetition highlighting.
 */
object SearchHitFlash {
    /** Pause after the initial ayah focus so the word is on-screen first. */
    const val START_DELAY_MS = 140L

    /** How many wash-in / dissolve cycles to run. */
    const val PULSES = 2

    /** One wash-in + fade-out cycle, matching the real repeat overlay. */
    fun cycleMs(): Int =
        InkEngine.tuning.repeatSweepMs + InkEngine.tuning.repeatFadeOutMs

    /** Total animation time after [START_DELAY_MS] (both pulses). */
    fun totalMs(): Long = PULSES.toLong() * cycleMs()

    /** The scrolling reader and Mushaf have different focus authorities. */
    internal fun isTargetSettled(
        mushafMode: Boolean,
        scrollingVerseSettled: Boolean,
        mushafLeafSettled: Boolean,
    ): Boolean = if (mushafMode) mushafLeafSettled else scrollingVerseSettled
}
