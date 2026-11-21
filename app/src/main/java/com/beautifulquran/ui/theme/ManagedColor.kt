package com.beautifulquran.ui.theme

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAndroidColorSpace
import androidx.compose.ui.graphics.toColorLong

/*
 * Colour handed past Compose to the framework keeps its colour space.
 *
 * Compose's own paints and gradients already do (colour longs on API 29+). The
 * holes are the paths that take bare components: an AGSL uniform declared as a
 * plain float3 is read in the *destination's* colour space, so an sRGB colour
 * written into it lands too saturated on a Display P3 window; and
 * `android.graphics.Color.valueOf(r, g, b, a)` labels any components sRGB,
 * while `toArgb()` clips a wide colour to sRGB. Every such path goes through
 * one of the two calls below instead.
 *
 * An sRGB colour is written as its exact 8-bit components, so for every token
 * the app shipped before wide gamut this is bit-for-bit the old input. A
 * Display P3 colour travels with its space and the framework converts it to
 * the destination's; anything else is converted to sRGB first.
 */

/** A non-sRGB colour in a space the framework draws: Display P3 as it is, anything else in sRGB. */
private fun Color.wideColor(): Color = if (colorSpace == ColorSpaces.DisplayP3) this else convert(ColorSpaces.Srgb)

/**
 * Sets a `layout(color) uniform float4` to [color] (at [alpha]), colour-managed
 * into the shader's working space. An sRGB colour at its own alpha goes through
 * the int overload, which passes its components unconverted — the same floats
 * `setFloatUniform` would have been given.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun RuntimeShader.setManagedColorUniform(name: String, color: Color, alpha: Float = color.alpha) {
    when {
        color.colorSpace != ColorSpaces.Srgb -> {
            val wide = color.wideColor()
            setColorUniform(
                name,
                android.graphics.Color.valueOf(
                    wide.red, wide.green, wide.blue, alpha, wide.colorSpace.toAndroidColorSpace(),
                ),
            )
        }
        alpha == color.alpha -> setColorUniform(name, color.toArgb())
        else -> setColorUniform(name, android.graphics.Color.valueOf(color.red, color.green, color.blue, alpha))
    }
}

/** Sets [color] on a framework paint without clipping it to sRGB. */
fun android.graphics.Paint.setManagedColor(color: Color) {
    if (color.colorSpace == ColorSpaces.Srgb) {
        this.color = color.toArgb()
    } else {
        setColor(color.wideColor().toColorLong())
    }
}
