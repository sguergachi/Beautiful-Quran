package com.beautifulquran.ui.theme.paperink

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Stable surface-film preferences, prepared alongside a word's arrival field.
 * R favours outward wicking; G gives the same connected fibres pigment body.
 * Opaque ARGB data avoids premultiplication changing either channel. This is
 * a transient material, not a simulation of pigment absorbed permanently.
 */
internal object PaperInkSubstrate {
    fun generate(width: Int, height: Int, unit: Float, seed: Int): IntArray {
        require(width > 0 && height > 0 && unit > 0f && unit.isFinite())
        return IntArray(width * height) { i ->
            val x = (i % width + .5f) / unit
            val y = (i / width + .5f) / unit
            // Rotate and gently bend the substrate before laying down fibres:
            // a straight lattice reads as pixel grain along a fine contour.
            val u = x * .917f + y * .398f
            val v = y * .917f - x * .398f
            val px = u + (noise(u / 42f, v / 42f, seed xor 0x71b49a3d) - .5f) * 18f
            val py = v + (noise((u + 211f) / 37f, (v - 137f) / 37f, seed xor 0x29a6d52b) - .5f) * 18f
            val body = noise(px / 18f, py / 18f, seed)
            val fibre = noise(px / 28f, (py + px * .12f) / 5f, seed xor 0x51ed270b)
            val grain = noise((px + py * .31f) / 8f, (py - px * .27f) / 8f, seed xor 0x3c85a791)
            val wick = ((body * .58f + fibre * .3f + grain * .12f - .5f) * 1.65f + .5f).coerceIn(0f, 1f)
            val density = wick * wick * (3f - 2f * wick)
            val r = (wick * 255f).roundToInt()
            val g = (density * 255f).roundToInt()
            (0xFF shl 24) or (r shl 16) or (g shl 8)
        }
    }

    private fun noise(x: Float, y: Float, seed: Int): Float {
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        fun ease(v: Float) = v * v * v * (v * (v * 6f - 15f) + 10f)
        val fx = ease(x - ix)
        val fy = ease(y - iy)
        val a = hash(ix, iy, seed)
        val b = hash(ix + 1, iy, seed)
        val c = hash(ix, iy + 1, seed)
        val d = hash(ix + 1, iy + 1, seed)
        return (a + (b - a) * fx) * (1f - fy) + (c + (d - c) * fx) * fy
    }

    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = seed xor (x * 0x1f123bb5) xor (y * 0x5f356495)
        h = (h xor (h ushr 16)) * 0x45d9f3b
        h = (h xor (h ushr 16)) * 0x45d9f3b
        return ((h xor (h ushr 16)) ushr 8) / 16777215f
    }
}
