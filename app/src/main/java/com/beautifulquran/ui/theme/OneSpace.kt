package com.beautifulquran.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces

/*
 * Gradient stops in one colour space.
 *
 * Compose 1.13 hands each gradient stop to the framework as a colour long in
 * its own colour space, and LinearGradient / RadialGradient / SweepGradient
 * throw IllegalArgumentException when the stops' spaces differ. On a
 * wide-gamut window some accents are Display P3 (Theme.kt) while their
 * gradient partners — goldBright, Color.Transparent, a scheme colour — are
 * sRGB, so a gilt or ruby gradient crashed on first draw. Only a wide-gamut
 * phone ever builds such a brush; the emulator's sRGB panel never does.
 *
 * Every Brush.*Gradient in the app passes its stops through [oneSpace]
 * (GradientStopsShareOneSpaceTest holds the source to that). Stops already in
 * one space come back as the very same list or array, so an sRGB window
 * builds exactly the brush it did before and allocates nothing. Mixed stops
 * are moved together into Display P3 when one of them is P3 (a superset of
 * sRGB, so no stop is clipped), otherwise into sRGB.
 */

/** [colors] in one colour space: the same list when they already share one. */
fun oneSpace(colors: List<Color>): List<Color> {
    val target = commonSpace(colors.size) { colors[it] } ?: return colors
    return colors.map { it.convert(target) }
}

/** [stops] in one colour space: the same array when they already share one. */
fun oneSpace(vararg stops: Pair<Float, Color>): Array<Pair<Float, Color>> {
    val target = commonSpace(stops.size) { stops[it].second }
    if (target == null) {
        // A vararg parameter is a fresh Array<Pair<Float, Color>> at runtime.
        @Suppress("UNCHECKED_CAST")
        return stops as Array<Pair<Float, Color>>
    }
    return Array(stops.size) { stops[it].first to stops[it].second.convert(target) }
}

/** The space to move mixed stops into, or null when they already share one. */
private inline fun commonSpace(count: Int, color: (Int) -> Color): ColorSpace? {
    if (count < 2) return null
    val first = color(0).colorSpace
    var mixed = false
    var anyP3 = first == ColorSpaces.DisplayP3
    for (i in 1 until count) {
        val space = color(i).colorSpace
        if (space != first) mixed = true
        if (space == ColorSpaces.DisplayP3) anyP3 = true
    }
    if (!mixed) return null
    return if (anyP3) ColorSpaces.DisplayP3 else ColorSpaces.Srgb
}
