package com.beautifulquran.ui.theme.paperink

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Subpixel outline distance for wet stroke expansion. Thresholding a glyph's
 * coverage first snaps its edge to the mask grid; filtering that distance later
 * cannot remove the resulting bumps. Interpolate the half-coverage crossings
 * instead and measure to their contour segments. The original captured glyph
 * and the separate arrival/depth fields remain authoritative for dry ink and
 * pacing. All work happens once, on the field generator's background thread.
 */
internal object PaperInkOutline {
    fun distance(alpha: FloatArray, width: Int, height: Int, scale: Int, fallback: FloatArray): FloatArray {
        val offsets = IntArray(alpha.size) { -1 }
        val counts = ByteArray(alpha.size)
        // Plain floats, grown by doubling: one boxed Float per contour point
        // used to dominate this pass.
        var segments = FloatArray(1024)
        var used = 0
        // Clockwise cell edges: top, right, bottom, left.
        val cornerX = floatArrayOf(0f, 1f, 1f, 0f)
        val cornerY = floatArrayOf(0f, 0f, 1f, 1f)
        val values = FloatArray(4)
        val points = FloatArray(8)
        for (y in 0 until height - 1) for (x in 0 until width - 1) {
            val i = y * width + x
            val a = alpha[i]
            val b = alpha[i + 1]
            val c = alpha[i + width + 1]
            val d = alpha[i + width]
            if (min(min(a, b), min(c, d)) >= .5f || max(max(a, b), max(c, d)) < .5f) continue
            values[0] = a
            values[1] = b
            values[2] = c
            values[3] = d
            var n = 0
            for (edge in 0..3) {
                val next = (edge + 1) % 4
                if ((values[edge] >= .5f) == (values[next] >= .5f)) continue
                val t = (.5f - values[edge]) / (values[next] - values[edge])
                points[n++] = x + cornerX[edge] + t * (cornerX[next] - cornerX[edge])
                points[n++] = y + cornerY[edge] + t * (cornerY[next] - cornerY[edge])
            }
            offsets[i] = used
            counts[i] = (n / 4).toByte()
            if (used + n > segments.size) segments = segments.copyOf(segments.size * 2)
            if (n == 8 && ((a + b + c + d) >= 2f) != (a >= .5f)) {
                // A saddle contains two separate segments. Keep the centre on
                // its coverage side instead of bridging diagonal corners.
                for (j in SADDLE_ORDER) segments[used++] = points[j]
            } else {
                for (j in 0 until n) segments[used++] = points[j]
            }
        }
        if (used == 0) return fallback
        val nearest = PaperInkField.edt(BooleanArray(alpha.size) { offsets[it] < 0 }, width, height, true).index!!
        val lines = segments
        return FloatArray(alpha.size) { i ->
            val x = i % width
            val y = i / width
            val cellX = nearest[i] % width
            val cellY = nearest[i] / width
            var best = Float.MAX_VALUE
            // The nearest cell centre need not contain the closest segment;
            // include its neighbours before choosing the contour distance.
            for (cy in max(0, cellY - 1)..min(height - 2, cellY + 1)) {
                for (cx in max(0, cellX - 1)..min(width - 2, cellX + 1)) {
                    val cell = cy * width + cx
                    var j = offsets[cell]
                    repeat(counts[cell].toInt()) {
                        val dx = lines[j + 2] - lines[j]
                        val dy = lines[j + 3] - lines[j + 1]
                        val px = x - lines[j]
                        val py = y - lines[j + 1]
                        val length = dx * dx + dy * dy
                        val t = if (length > 0f) ((px * dx + py * dy) / length).coerceIn(0f, 1f) else 0f
                        val ex = px - t * dx
                        val ey = py - t * dy
                        best = min(best, ex * ex + ey * ey)
                        j += 4
                    }
                }
            }
            sqrt(best) / scale * if (alpha[i] >= .5f) 1f else -1f
        }
    }

    private val SADDLE_ORDER = intArrayOf(0, 1, 6, 7, 2, 3, 4, 5)
}
