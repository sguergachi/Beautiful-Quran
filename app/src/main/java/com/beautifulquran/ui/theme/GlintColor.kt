package com.beautifulquran.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.lerp
import kotlin.math.exp

/*
 * The tarjīʿ pulse is light, and only light: the word's brightness rises a
 * little with the voice and falls back a little less. Nothing changes hue.
 *
 * Why it is built this way (docs/GLIMMER.md has the references):
 *  - The eye is at its most sensitive to flicker at exactly the rates tarjīʿ
 *    lives at; a fraction of a percent of modulation is already visible. A
 *    swing of ten percent is plenty, and anything like "dark to white" is a
 *    strobe.
 *  - What makes a thing look bright is the glow around it, not its own
 *    colour — the glyphs are near white already. So the glow carries more of
 *    the swing than the glyphs do.
 *  - Glare is linear in the light that causes it: every layer of the glow
 *    scales by the same level, in linear light, at a constant hue.
 */

/** Brightness of the word's light for [glow] (−1..1, the smoothed pulse):
 * 1 at rest, up to 1 + [rise] on a crest, down to 1 − [fall] in a trough.
 * [brightness] is the per-reciter scale of the swing; it never moves rest. */
internal fun glintLightLevel(glow: Float, brightness: Float, rise: Float, fall: Float): Float {
    val swing = if (glow >= 0f) rise * glow else fall * glow
    return 1f + brightness.coerceIn(0f, 2f) * swing
}

/** [base] at [level] times its light, scaled in linear light so the hue holds
 * (a channel that reaches full simply stays there, as an overexposed light does). */
internal fun glintLightColor(base: Color, level: Float): Color {
    if (level == 1f) return base
    val linear = base.convert(ColorSpaces.LinearSrgb)
    return Color(
        red = (linear.red * level).coerceIn(0f, 1f),
        green = (linear.green * level).coerceIn(0f, 1f),
        blue = (linear.blue * level).coerceIn(0f, 1f),
        alpha = base.alpha,
        colorSpace = ColorSpaces.LinearSrgb,
    ).convert(ColorSpaces.Srgb)
}

/** Strength of one glow layer at [level]. The glow swings [gain] times as far
 * as the glyphs: it is where the eye reads brightness. */
internal fun glintGlowAlpha(resting: Float, level: Float, gain: Float): Float =
    (resting * (1f + gain * (level - 1f))).coerceIn(0f, 1f)

/** The widest glow layer's colour: the eye's own scatter warms toward its edge. */
internal fun glintVeilColor(base: Color, warmth: Float): Color =
    lerp(base, GlintVeilWarm, warmth.coerceIn(0f, 1f))

private val GlintVeilWarm = Color(0xFFFFC98A)

/**
 * The light's response to the voice: a plain low-pass, the same up as down, so
 * the brightness moves as a smooth swell and never snaps. Its lag is known
 * ([smoothMs]) and the reader reads the voice that far ahead of the ear to
 * cancel it — the tap leads the speaker, so the future is in hand.
 */
internal class GlintLight {
    private var glow = 0f
    private var lastFrame: Long? = null

    fun next(light: Float, frameNanos: Long, smoothMs: Float): Float {
        val elapsedMs = lastFrame?.let { (frameNanos - it).coerceAtLeast(0L) / 1_000_000f } ?: 0f
        lastFrame = frameNanos
        val target = light.coerceIn(-1f, 1f)
        glow = if (smoothMs <= 0f) target else target + (glow - target) * exp(-elapsedMs / smoothMs)
        return glow
    }
}
