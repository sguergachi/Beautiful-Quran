package com.beautifulquran.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Accepted pulses breathe from darker opaque ink to white, inside the existing wash. */
internal fun glintPulseColor(base: Color, peak: Float, brightness: Float = 1f, inkStrength: Float = 0f): Color {
    val valley = base.copy(red = base.red * 0.45f, green = base.green * 0.45f, blue = base.blue * 0.45f)
    return lerp(lerp(base, valley, inkStrength.coerceIn(0f, 1f)), Color.White,
        (peak.coerceIn(0f, 1f) * brightness.coerceIn(0f, 2f)).coerceIn(0f, 1f))
}

/** A trough needs opaque tint coverage, otherwise bright parchment ink shows through. */
internal fun glintContrastAlpha(alpha: Float, inkStrength: Float): Float =
    alpha + (1f - alpha) * inkStrength.coerceIn(0f, 1f)

/** Smooths audio hops over 30 ms without averaging away a 6–10 Hz pulse. */
internal class GlintColorTransition {
    private var white = 0f
    private var lastFrame: Long? = null

    fun next(peak: Float, brightness: Float, frameNanos: Long): Float {
        val step = lastFrame?.let { (frameNanos - it).coerceAtLeast(0L) / 30_000_000f } ?: 0f
        lastFrame = frameNanos
        val target = (peak.coerceIn(0f, 1f) * brightness.coerceIn(0f, 2f)).coerceIn(0f, 1f)
        white += (target - white).coerceIn(-step, step)
        return white
    }
}
