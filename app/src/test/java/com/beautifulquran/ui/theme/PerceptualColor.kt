package com.beautifulquran.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow

/**
 * The two colour metrics the palette is designed against.
 *
 * [apca] is lightness contrast as perceived, and unlike a WCAG ratio it scores
 * dark-on-light and light-on-dark differently — which is the only reason a rung
 * of the ink ladder can be said to "weigh the same" on cream and on charcoal.
 * [oklch] is used to check that a theme's sheet, rules and ink belong to one
 * hue family.
 */
object PerceptualColor {

    /** Flattens [color] onto the opaque [over], which is what the screen does. */
    fun composite(color: Color, over: Color): Color {
        val a = color.alpha
        return Color(
            red = color.red * a + over.red * (1 - a),
            green = color.green * a + over.green * (1 - a),
            blue = color.blue * a + over.blue * (1 - a),
        )
    }

    private const val R_CO = 0.2126729
    private const val G_CO = 0.7151522
    private const val B_CO = 0.0721750
    private const val NORM_BG = 0.56
    private const val NORM_TXT = 0.57
    private const val REV_TXT = 0.62
    private const val REV_BG = 0.65
    private const val BLK_THRS = 0.022
    private const val BLK_CLMP = 1.414
    private const val SCALE = 1.14
    private const val OFFSET = 0.027
    private const val LO_CLIP = 0.1
    private const val DELTA_Y_MIN = 0.0005

    private fun screenLuminance(c: Color): Double {
        val y = R_CO * c.red.toDouble().pow(2.4) +
            G_CO * c.green.toDouble().pow(2.4) +
            B_CO * c.blue.toDouble().pow(2.4)
        return if (y < BLK_THRS) y + (BLK_THRS - y).pow(BLK_CLMP) else y
    }

    /**
     * APCA 0.1.9 lightness contrast, as an unsigned magnitude. Roughly: 90 is
     * body text set to be read at length, 45 the floor below which type stops
     * being readable, 30 the floor for a rule or an icon, 15 invisibility.
     */
    fun apca(text: Color, background: Color): Double {
        val yt = screenLuminance(text)
        val yb = screenLuminance(background)
        if (abs(yb - yt) < DELTA_Y_MIN) return 0.0
        return if (yb > yt) {
            val s = (yb.pow(NORM_BG) - yt.pow(NORM_TXT)) * SCALE
            if (s < LO_CLIP) 0.0 else (s - OFFSET) * 100
        } else {
            val s = (yb.pow(REV_BG) - yt.pow(REV_TXT)) * SCALE
            if (s > -LO_CLIP) 0.0 else abs((s + OFFSET) * 100)
        }
    }

    /** Contrast of [text] once composited onto [background]. */
    fun contrastOn(text: Color, background: Color): Double =
        apca(composite(text, background), background)

    data class Oklch(val lightness: Double, val chroma: Double, val hue: Double)

    private fun linear(u: Float): Double {
        val c = u.toDouble()
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    fun oklch(c: Color): Oklch {
        val r = linear(c.red)
        val g = linear(c.green)
        val b = linear(c.blue)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val lightness = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        val hue = Math.toDegrees(atan2(bb, a)).let { if (it < 0) it + 360 else it }
        return Oklch(lightness, hypot(a, bb), hue)
    }

    /** Smallest turn between two hue angles, in degrees. */
    fun hueDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }
}
