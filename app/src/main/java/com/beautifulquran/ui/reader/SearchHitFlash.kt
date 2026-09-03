package com.beautifulquran.ui.reader

/**
 * Timing for the cover-sheet search-hit flash: the same orange directional
 * wash the repeat overlay uses, but quicker and repeated enough to work as a
 * clear locator rather than being mistaken for recitation ink.
 */
object SearchHitFlash {
    /** Pause after the initial ayah focus so the word is on-screen first. */
    const val START_DELAY_MS = 140L

    const val SWEEP_MS = 240
    const val FADE_OUT_MS = 280
    const val PULSES = 4

    /** One quick directional wash-in + fade-out cycle. */
    fun cycleMs(): Int = SWEEP_MS + FADE_OUT_MS

    /** Total animation time after [START_DELAY_MS]. */
    fun totalMs(): Long = PULSES.toLong() * cycleMs()

    /** The scrolling reader and Mushaf have different focus authorities. */
    internal fun isTargetSettled(
        mushafMode: Boolean,
        scrollingVerseSettled: Boolean,
        mushafLeafSettled: Boolean,
    ): Boolean = if (mushafMode) mushafLeafSettled else scrollingVerseSettled
}
