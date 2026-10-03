package com.beautifulquran.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** The accepted vocal crest turns wet ink white; its falling edge restores the base hue. */
internal fun glintPulseColor(base: Color, peak: Float, brightness: Float = 1f): Color =
    lerp(base, Color.White, (peak.coerceIn(0f, 1f) * brightness.coerceIn(0f, 2f)).coerceIn(0f, 1f))

/** Limits hue travel to one full gold–white transition per 120 ms; vocal alpha stays direct. */
internal class GlintColorTransition {
    private var white = 0f
    private var lastFrame: Long? = null

    fun next(peak: Float, brightness: Float, frameNanos: Long): Float {
        val step = lastFrame?.let { (frameNanos - it).coerceAtLeast(0L) / 120_000_000f } ?: 0f
        lastFrame = frameNanos
        val target = (peak.coerceIn(0f, 1f) * brightness.coerceIn(0f, 2f)).coerceIn(0f, 1f)
        white += (target - white).coerceIn(-step, step)
        return white
    }
}
