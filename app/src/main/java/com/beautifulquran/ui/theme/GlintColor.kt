package com.beautifulquran.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import kotlin.math.exp

/*
 * The tarjīʿ pulse is painted as light, not ink: the word is lit by a flame
 * the voice feeds. [glow] is that flame, signed around the ordinary wet sheen —
 * +1 a full flare (white, halo at its widest), −1 an ember (the light all but
 * out), 0 the sheen every fresh word carries. Nothing moves until the voice
 * does, so an accepted hold never announces itself by darkening first.
 */

/** The lit colour of [base] ink under [glow]. Brightness scales how far the
 * same pulse travels toward white and toward the ember, never where rest is. */
internal fun glintPulseColor(base: Color, glow: Float, brightness: Float = 1f): Color {
    val reach = (abs(glow) * brightness.coerceIn(0f, 2f)).coerceIn(0f, 1f)
    return lerp(base, if (glow >= 0f) Color.White else glintEmber(base), reach)
}

/** [base] with the light lowered: a little dimmer and a little warmer — a low
 * flame reddens. Deliberately mild: at a third of the page's range the pulse
 * took the eye off the verse; the word should only seem to answer the voice. */
internal fun glintEmber(base: Color): Color =
    base.copy(red = base.red * 0.88f, green = base.green * 0.84f, blue = base.blue * 0.76f)

/** Tint coverage: the ordinary sheen at rest, opaque as the light moves either
 * way — a flare must not be thinned and an ember must cover the bright ink. */
internal fun glintLitAlpha(resting: Float, glow: Float, brightness: Float = 1f): Float {
    val reach = (abs(glow) * brightness.coerceIn(0f, 2f)).coerceIn(0f, 1f)
    return resting + (1f - resting) * reach
}

/** Halo strength: it swells to full on a flare and all but goes out in an
 * ember. The halo is the light itself, so it swings harder than the glyphs. */
internal fun glintHaloAlpha(resting: Float, glow: Float, brightness: Float = 1f): Float {
    val reach = (abs(glow) * brightness.coerceIn(0f, 2f)).coerceIn(0f, 1f)
    return if (glow >= 0f) resting + (1f - resting) * reach else resting * (1f - 0.5f * reach)
}

/**
 * How a flame answers the voice: it catches a rising pulse at once and lets a
 * falling one go the way hot light cools. The sharp rise is what ties each
 * flare to the reverberation that caused it; the short fall keeps the troughs
 * of a fast vibrato (10 Hz) from filling in.
 */
internal class GlintFlame {
    private var glow = 0f
    private var lastFrame: Long? = null

    fun next(light: Float, frameNanos: Long): Float {
        val elapsedMs = lastFrame?.let { (frameNanos - it).coerceAtLeast(0L) / 1_000_000f } ?: 0f
        lastFrame = frameNanos
        val target = light.coerceIn(-1f, 1f)
        glow = if (target >= glow) {
            glow + (target - glow).coerceAtMost(2f * elapsedMs / RISE_MS)
        } else {
            target + (glow - target) * exp(-elapsedMs / FALL_MS)
        }
        return glow
    }

    private companion object {
        /** Full ember-to-flare travel: two frames at 60 Hz — prompt enough to
         * land with the voice, not so abrupt that it strobes. */
        const val RISE_MS = 30f
        /** Cooling time constant: the flare lingers a little and the troughs
         * of a fast vibrato soften rather than snap. */
        const val FALL_MS = 45f
    }
}
