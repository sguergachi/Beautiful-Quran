package com.beautifulquran.ui.theme.paperink

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The page's paper, shared by every word: one seamless tile of surface-film
 * preference in px of the 160 px reference font, favouring outward wicking.
 * Stored as an alpha-only bitmap, so premultiplication cannot touch it. This
 * is a transient material, not a simulation of pigment absorbed permanently.
 *
 * Each word samples it at its own offset ([PaperInk]), so the same word
 * printed twice does not bleed in the same shape.
 */
internal object PaperInkSubstrate {
    /** Edge of the square tile, in reference px. Every lattice period divides it. */
    const val TILE = 720
    const val SEED = 0x2d1b5a37

    /**
     * The tile's wicking preference, one byte per reference px, row-major.
     * Pigment density is the same fibre's smoothstep of it, so the shader
     * derives it instead of storing a second channel.
     */
    fun tile(seed: Int = SEED): ByteArray {
        val paper = Paper(seed)
        val out = ByteArray(TILE * TILE)
        for (y in 0 until TILE) {
            val row = y * TILE
            for (x in 0 until TILE) out[row + x] = paper.wick(x + .5f, y + .5f).toByte()
        }
        return out
    }

    /**
     * The tile's wicking at reference position ([x], [y]), 0–255; periodic
     * in [TILE] along both axes. Every grain runs on an integer rotation
     * (2,1), (3,1) or (1,2) of the tile, so a whole tile is a whole number of
     * cells along each rotated axis and the pattern wraps without a seam. The
     * rotations also keep the fibres off the pixel grid: a straight lattice
     * reads as pixel grain along a fine contour.
     */
    fun sample(x: Float, y: Float, seed: Int = SEED): Int = Paper(seed).wick(x, y)

    private class Paper(seed: Int) {
        private val warpX = Lattice(8, 8, seed xor 0x71b49a3d)
        private val warpY = Lattice(9, 9, seed xor 0x29a6d52b)
        private val body = Lattice(18, 18, seed)
        private val fibre = Lattice(8, 45, seed xor 0x51ed270b)
        private val grain = Lattice(40, 40, seed xor 0x3c85a791)

        fun wick(x: Float, y: Float): Int {
            // Body grain, 26.6° and √5 larger than reference px.
            val u = 2f * x + y
            val v = 2f * y - x
            // Gently bend the whole substrate before laying down fibres.
            val dx = (warpX.noise(u / 90f, v / 90f) - .5f) * 40f
            val dy = (warpY.noise((u + 211f) / 80f, (v - 137f) / 80f) - .5f) * 40f
            val pools = body.noise((u + dx) / 40f, (v + dy) / 40f)
            // Long fibres on a shallower grain, 18.4° and √10 larger.
            val fibres = fibre.noise((3f * x + y + dx * 1.41f) / 90f, (3f * y - x + dy * 1.41f) / 16f)
            // A weak finer grain across both, 63.4°.
            val fine = grain.noise((x + 2f * y + dx) / 18f, (y - 2f * x + dy) / 18f)
            val wick = ((pools * .58f + fibres * .3f + fine * .12f - .5f) * 1.65f + .5f).coerceIn(0f, 1f)
            return (wick * 255f).roundToInt()
        }
    }

    /** Value noise on a lattice that repeats every [periodX] × [periodY] cells,
     * its corner values hashed once up front. */
    private class Lattice(private val periodX: Int, private val periodY: Int, seed: Int) {
        private val values = FloatArray(periodX * periodY) { hash(it % periodX, it / periodX, seed) }

        fun noise(x: Float, y: Float): Float {
            val ix = floor(x).toInt()
            val iy = floor(y).toInt()
            val fx = ease(x - ix)
            val fy = ease(y - iy)
            val x0 = Math.floorMod(ix, periodX)
            val x1 = if (x0 + 1 == periodX) 0 else x0 + 1
            val r0 = Math.floorMod(iy, periodY) * periodX
            val r1 = if (r0 + periodX == periodX * periodY) 0 else r0 + periodX
            val a = values[r0 + x0]
            val b = values[r0 + x1]
            val c = values[r1 + x0]
            val d = values[r1 + x1]
            return (a + (b - a) * fx) * (1f - fy) + (c + (d - c) * fx) * fy
        }

        private fun ease(v: Float) = v * v * v * (v * (v * 6f - 15f) + 10f)
    }

    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = seed xor (x * 0x1f123bb5) xor (y * 0x5f356495)
        h = (h xor (h ushr 16)) * 0x45d9f3b
        h = (h xor (h ushr 16)) * 0x45d9f3b
        return ((h xor (h ushr 16)) ushr 8) / 16777215f
    }
}
