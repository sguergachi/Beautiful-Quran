package com.beautifulquran.ui.reader

import androidx.compose.animation.core.CubicBezierEasing

/**
 * Timing for the cover-sheet search-hit breath: an eased orange directional
 * wash, a short full-ink crest, then an eased release and quiet gap.
 */
object SearchHitFlash {
    /** Pause after the initial ayah focus so the word is on-screen first. */
    const val START_DELAY_MS = 140L

    const val SWEEP_MS = 240
    const val CREST_MS = 80L
    const val FADE_OUT_MS = 300
    const val REST_MS = 40L
    const val PULSES = 4

    /** Symmetric ease-in-out keeps each inhale and exhale soft at both ends. */
    val EASING = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

    /** Tight glyph-following spread that makes the orange fill read heavier. */
    const val EMPHASIS_GLOW_ALPHA = 0.92f
    const val EMPHASIS_GLOW_RADIUS = 1.2f

    /** One eased wash-in, full-ink crest, and release. */
    fun cycleMs(): Long = SWEEP_MS + CREST_MS + FADE_OUT_MS

    /** Total animation time after [START_DELAY_MS]. */
    fun totalMs(): Long = PULSES * cycleMs() + (PULSES - 1) * REST_MS

    /** The scrolling reader and Mushaf have different focus authorities. */
    internal fun isTargetSettled(
        mushafMode: Boolean,
        scrollingVerseSettled: Boolean,
        mushafLeafSettled: Boolean,
    ): Boolean = if (mushafMode) mushafLeafSettled else scrollingVerseSettled
}
