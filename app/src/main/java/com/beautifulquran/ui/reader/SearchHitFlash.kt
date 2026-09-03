package com.beautifulquran.ui.reader

import androidx.compose.animation.core.CubicBezierEasing

/**
 * Timing for the cover-sheet search-hit locator: distinct directional orange
 * wipes with a brief clear interval, so even a small word remains conspicuous.
 */
object SearchHitFlash {
    /** Pause after the initial ayah focus so the word is on-screen first. */
    const val START_DELAY_MS = 140L

    const val SWEEP_MS = 360
    const val RELEASE_MS = 120
    const val REST_MS = 80L
    const val WIPES = 5

    /** Sine-like ease keeps the traveling edge soft at both ends. */
    val EASING = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

    /** Tight glyph-following spread that makes the orange fill read heavier. */
    const val EMPHASIS_GLOW_ALPHA = 0.92f
    const val EMPHASIS_GLOW_RADIUS = 1.2f

    /** One visible side wipe and its clearing dissolve. */
    fun wipeMs(): Long = (SWEEP_MS + RELEASE_MS).toLong()

    /** Total animation time after [START_DELAY_MS]. */
    fun totalMs(): Long = WIPES * wipeMs() + (WIPES - 1) * REST_MS

    /** The scrolling reader and Mushaf have different focus authorities. */
    internal fun isTargetSettled(
        mushafMode: Boolean,
        scrollingVerseSettled: Boolean,
        mushafLeafSettled: Boolean,
    ): Boolean = if (mushafMode) mushafLeafSettled else scrollingVerseSettled
}
