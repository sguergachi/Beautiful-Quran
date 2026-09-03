package com.beautifulquran.ui.reader

import androidx.compose.animation.core.CubicBezierEasing

/**
 * Timing for the cover-sheet search-hit breath: one directional orange fill,
 * then full-word ink strength inhaling and exhaling without resetting the wash.
 */
object SearchHitFlash {
    /** Pause after the initial ayah focus so the word is on-screen first. */
    const val START_DELAY_MS = 140L

    const val SWEEP_MS = 240
    const val INHALE_MS = 230
    const val CREST_MS = 90L
    const val EXHALE_MS = 260
    const val REST_MS = 50L
    const val FINAL_FADE_MS = 180
    const val PULSES = 4
    const val REST_ALPHA = 0.32f

    /** Symmetric ease-in-out keeps each inhale and exhale soft at both ends. */
    val EASING = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

    /** Tight glyph-following spread that makes the orange fill read heavier. */
    const val EMPHASIS_GLOW_ALPHA = 0.92f
    const val EMPHASIS_GLOW_RADIUS = 1.2f

    /** One full-word inhale, crest, exhale, and quiet rest. */
    fun breathMs(): Long = INHALE_MS + CREST_MS + EXHALE_MS + REST_MS

    /** Total animation time after [START_DELAY_MS]. */
    fun totalMs(): Long = SWEEP_MS + PULSES * (CREST_MS + EXHALE_MS) +
        (PULSES - 1) * (REST_MS + INHALE_MS) + FINAL_FADE_MS

    /** The scrolling reader and Mushaf have different focus authorities. */
    internal fun isTargetSettled(
        mushafMode: Boolean,
        scrollingVerseSettled: Boolean,
        mushafLeafSettled: Boolean,
    ): Boolean = if (mushafMode) mushafLeafSettled else scrollingVerseSettled
}
