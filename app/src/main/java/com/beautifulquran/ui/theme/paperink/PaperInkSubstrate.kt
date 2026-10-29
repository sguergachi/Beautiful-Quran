package com.beautifulquran.ui.theme.paperink

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The page's paper, shared by every word: one seamless tile of surface-film
 * preferences in px of the 160 px reference font. R favours outward wicking;
 * G gives the same connected fibres pigment body. Opaque ARGB data avoids
 * premultiplication changing either channel. This is a transient material,
 * not a simulation of pigment absorbed permanently.
 *
 * Each word samples it at its own offset ([PaperInk]), so the same word
 * printed twice does not bleed in the same shape.
 */
internal object PaperInkSubstrate {
    /** Edge of the square tile, in reference px. Every lattice period divides it. */
    const val TILE = 720
    const val SEED = 0x2d1b5a37

    fun tile(seed: Int = SEED): IntArray =
        IntArray(TILE * TILE) { i -> sample(i % TILE + .5f, i / TILE + .5f, seed) }

    /**
     * The tile's pixel at reference position ([x], [y]); periodic in [TILE]
     * along both axes. Every grain runs on an integer rotation (2,1), (3,1) or
     * (1,2) of the tile, so a whole tile is a whole number of cells along each
     * rotated axis and the pattern wraps without a seam. The rotations also
     * keep the fibres off the pixel grid: a straight lattice reads as pixel
     * grain along a fine contour.
     */
    fun sample(x: Float, y: Float, seed: Int = SEED): Int {
        // Body grain, 26.6° and √5 larger than reference px.
        val u = 2f * x + y
        val v = 2f * y - x
        // Gently bend the whole substrate before laying down fibres.
        val dx = (noise(u / 90f, v / 90f, 8, 8, seed xor 0x71b49a3d) - .5f) * 40f
        val dy = (noise((u + 211f) / 80f, (v - 137f) / 80f, 9, 9, seed xor 0x29a6d52b) - .5f) * 40f
        val body = noise((u + dx) / 40f, (v + dy) / 40f, 18, 18, seed)
        // Long fibres on a shallower grain, 18.4° and √10 larger.
        val a = 3f * x + y + dx * 1.41f
        val b = 3f * y - x + dy * 1.41f
        val fibre = noise(a / 90f, b / 16f, 8, 45, seed xor 0x51ed270b)
        // A weak finer grain across both, 63.4°.
        val c = x + 2f * y + dx
        val d = y - 2f * x + dy
        val grain = noise(c / 18f, d / 18f, 40, 40, seed xor 0x3c85a791)
        val wick = ((body * .58f + fibre * .3f + grain * .12f - .5f) * 1.65f + .5f).coerceIn(0f, 1f)
        val density = wick * wick * (3f - 2f * wick)
        val r = (wick * 255f).roundToInt()
        val g = (density * 255f).roundToInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8)
    }

    /** Value noise whose lattice repeats every [periodX] × [periodY] cells. */
    private fun noise(x: Float, y: Float, periodX: Int, periodY: Int, seed: Int): Float {
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        fun ease(v: Float) = v * v * v * (v * (v * 6f - 15f) + 10f)
        val fx = ease(x - ix)
        val fy = ease(y - iy)
        val x0 = Math.floorMod(ix, periodX)
        val x1 = Math.floorMod(ix + 1, periodX)
        val y0 = Math.floorMod(iy, periodY)
        val y1 = Math.floorMod(iy + 1, periodY)
        val a = hash(x0, y0, seed)
        val b = hash(x1, y0, seed)
        val c = hash(x0, y1, seed)
        val d = hash(x1, y1, seed)
        return (a + (b - a) * fx) * (1f - fy) + (c + (d - c) * fx) * fy
    }

    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = seed xor (x * 0x1f123bb5) xor (y * 0x5f356495)
        h = (h xor (h ushr 16)) * 0x45d9f3b
        h = (h xor (h ushr 16)) * 0x45d9f3b
        return ((h xor (h ushr 16)) ushr 8) / 16777215f
    }
}
