package com.beautifulquran.ui.theme.paperink

import kotlin.math.pow

/**
 * Wet spread in device pixels. Below a 48 dp font, a gentle optical correction
 * keeps added weight off small letterforms. Density changes resolution, not
 * the amount of ink relative to the font; dry glyph coverage is independent.
 */
internal fun paperInkPoolingWeight(pooling: Float, fontPx: Float, density: Float): Float {
    val optical = (fontPx / density / 48f).coerceIn(0f, 1f).pow(.25f)
    return pooling * 3.5f * (fontPx / 160f) * optical
}
