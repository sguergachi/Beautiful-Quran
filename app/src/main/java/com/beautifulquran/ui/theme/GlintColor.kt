package com.beautifulquran.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.lerp
import kotlin.math.exp
import kotlin.math.pow

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
 *  - Glare is linear in the light that causes it. The page is composited in
 *    gamma space, where a layer's alpha is not its light: twenty percent more
 *    alpha is about fifty percent more luminance. So every swing here is
 *    stated in linear light and converted ([glintGlowAlpha]) — the first cut
 *    scaled alpha directly and a "ten percent" pulse measured sixty.
 *  - A light at full has nowhere to go but white. The glint rests a breath
 *    under its colour ([GLINT_REST_LIGHT]) so a crest is the same hue, brighter.
 */

/** Resting light of the glyphs, as a fraction of the glint colour: the headroom
 * a crest rises into. No channel is ever scaled past the colour itself, so the
 * hue cannot drift toward white. */
internal const val GLINT_REST_LIGHT = 0.96f

/** Display gamma: luminance of a layer goes as its alpha to this power. */
private const val GLOW_GAMMA = 2.2f

/** How far the swing may be pushed past shipped: [brightness] above 1 counts half. */
internal fun glintSwingScale(brightness: Float): Float {
    val b = brightness.coerceIn(0f, 2f)
    return if (b <= 1f) b else 1f + (b - 1f) * 0.5f
}

/** Brightness of the word's light for [glow] (−1..1, the smoothed pulse):
 * 1 at rest, up to 1 + [rise] on a crest, down to 1 − [fall] in a trough.
 * [brightness] is the per-reciter scale of the swing; it never moves rest. */
internal fun glintLightLevel(glow: Float, brightness: Float, rise: Float, fall: Float): Float {
    val swing = if (glow >= 0f) rise * glow else fall * glow
    return 1f + glintSwingScale(brightness) * swing
}

/** Linear-light scale of the glyphs at [level]; full is the colour itself. */
internal fun glintLetterLight(level: Float): Float =
    (GLINT_REST_LIGHT * level).coerceIn(0f, 1f)

/** [base] at [level] times its resting light, scaled in linear light so the hue holds. */
internal fun glintLightColor(base: Color, level: Float): Color {
    val scale = glintLetterLight(level)
    if (scale == 1f) return base
    val linear = base.convert(ColorSpaces.LinearSrgb)
    return Color(
        red = linear.red * scale,
        green = linear.green * scale,
        blue = linear.blue * scale,
        alpha = base.alpha,
        colorSpace = ColorSpaces.LinearSrgb,
    ).convert(ColorSpaces.Srgb)
}

/**
 * Strength of one glow layer at [level]. The glow's light swings [gain] times
 * as far as the glyphs': it is where the eye reads brightness. The layer is
 * drawn in [glintLightColor], which already carries the glyphs' share, so the
 * alpha supplies only the rest — through the display gamma, so the swing is
 * the one stated and not its 2.2th power.
 */
internal fun glintGlowAlpha(resting: Float, level: Float, gain: Float): Float {
    val letters = glintLetterLight(level) / GLINT_REST_LIGHT
    if (letters <= 0f) return 0f
    val glow = (1f + gain * (level - 1f)).coerceAtLeast(0f)
    return (resting * (glow / letters).pow(1f / GLOW_GAMMA)).coerceIn(0f, 1f)
}

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
