package com.beautifulquran.ui.theme.paperink

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Remove the old paint at every captured glyph pixel before replacing it.
 * Reusing fractional glyph coverage for DST_OUT leaves some of the old edge
 * underneath the new one and darkens it. Visible antialiasing stays in the
 * shader's original fractional coverage; this mask only clears its support.
 */
internal fun paperInkEraseMask(coverage: ByteArray): ByteArray =
    ByteArray(coverage.size) { if (coverage[it] == 0.toByte()) 0 else 0xFF.toByte() }

/**
 * The paper ink's swelling-channel fields for one word, worked out from the
 * word's own rasterized glyphs. Pure Kotlin: no Android types, so the geometry
 * is unit tested on the JVM. `tools/build_paper_ink_study.py` is the reference
 * implementation for arrival; `tools/paper_ink_outline.py` is the wet contour
 * reference. Keep the implementations in step (see docs/PAPER_INK.md).
 *
 * Coordinates are mask pixels at [scale] samples per device pixel. Distances
 * and the stroke radii are in device pixels, as the shader measures them.
 */
internal object PaperInkField {
    /** Slot-map smoothing, in mean letter widths. */
    const val LETTER_SMOOTHING = 0.45f
    /** Neighbourhood for the local stroke half-width, in px at a 160 px font. */
    const val STROKE_RADIUS = 6f
    /** Cost of one pixel of horizontal drift when finding a stroke's baseline foot. */
    const val FOOTING_DRIFT_COST = 6f
    /** Inside out swell: lag in letter slots, then the core-to-edge profile exponent. */
    const val INSIDE_OUT_LAG = 1.6f
    const val INSIDE_OUT_SHAPE = 0.7f
    const val FLOWING_LAG = 1f

    class Result(
        val width: Int,
        val height: Int,
        /** Arrival phase per mask pixel, already in the word window (see [window]). */
        val insideOut: FloatArray,
        val flowing: FloatArray,
        /** Signed distance to the outline in device px, positive inside the ink. */
        val distance: FloatArray,
        /** Liquid film normal, per mask pixel, encoded later at device resolution. */
        val normalX: FloatArray,
        val normalY: FloatArray,
        /** Feather duration handed to the shader: 2.8 / (letters + 1.8). */
        val letterDuration: Float,
    )

    /**
     * [alpha] is the word's glyph coverage, row-major [width]×[height].
     * [letterCenters] are the x of each letter in reading order (mask px);
     * [baseline] the baseline row (mask px). [fontPx] is the device font size.
     */
    fun generate(
        alpha: FloatArray,
        width: Int,
        height: Int,
        scale: Int,
        letterCenters: FloatArray,
        baseline: Float?,
        rtl: Boolean,
        fontPx: Float,
        /** Letters to space evenly across the ink when [letterCenters] is empty. */
        letterCount: Int = letterCenters.size,
    ): Result? {
        val n = width * height
        require(alpha.size == n)
        val ink = BooleanArray(n) { alpha[it] > 0.5f }
        if (ink.none { it }) return null
        val unit = fontPx / 160f
        val inside = edt(ink, width, height, wantIndex = false).distance
        val outside = edt(BooleanArray(n) { !ink[it] }, width, height, wantIndex = true)
        val distance = FloatArray(n) { i ->
            val a = alpha[i]
            if (a > 0f && a < 1f) {
                (a - 0.5f) / scale
            } else {
                var d = (inside[i] - outside.distance[i]) / scale
                d -= sign(d) / (2f * scale)
                d
            }
        }
        val centers = if (letterCenters.isEmpty() && letterCount > 0) {
            evenCenters(ink, width, letterCount, rtl)
        } else {
            letterCenters
        }
        val letters = centers.size.coerceAtLeast(1)
        val duration = 2.8f / (letters + 1.8f)
        val nearestInk = outside.index!!
        val waves = swellingBoth(ink, distance, width, height, scale, centers, rtl, unit, baseline, nearestInk)
            .map { wave -> FloatArray(n) { 0.12f * duration + (1f - 0.24f * duration) * wave[it] } }
        // Preserve the established depth/arrival map; only the liquid outline
        // and its reflection need the font's fractional edge locations.
        val outline = PaperInkOutline.distance(alpha, width, height, scale, distance)
        val (normalX, normalY) = liquidSurface(outline, width, height, scale, unit)
        return Result(width, height, waves[0], waves[1], outline, normalX, normalY, duration)
    }

    /** [count] letter centres spaced evenly across the ink, in reading order. */
    internal fun evenCenters(ink: BooleanArray, width: Int, count: Int, rtl: Boolean): FloatArray {
        var left = width
        var right = -1
        for (i in ink.indices) if (ink[i]) {
            val x = i % width
            if (x < left) left = x
            if (x > right) right = x
        }
        val start = if (rtl) right else left
        val end = if (rtl) left else right
        return FloatArray(count) { k -> start + (end - start) * (k + 1f) / (count + 1f) }
    }

    /** Swelling channels: see `swelling_channels` in the Python reference. */
    internal fun swellingChannels(
        ink: BooleanArray,
        depth: FloatArray,
        width: Int,
        height: Int,
        scale: Int,
        centers: FloatArray,
        rtl: Boolean,
        flowing: Boolean,
        unit: Float,
        baseline: Float?,
        nearestInk: IntArray? = null,
    ): FloatArray =
        swellingBoth(ink, depth, width, height, scale, centers, rtl, unit, baseline, nearestInk)[if (flowing) 1 else 0]

    /**
     * Both spread methods at once — Inside out, then Flowing ink. The sweep,
     * stroke widths and baseline footing are the same for both; only the swell
     * differs, so they are worked out once.
     */
    internal fun swellingBoth(
        ink: BooleanArray,
        depth: FloatArray,
        width: Int,
        height: Int,
        scale: Int,
        centers: FloatArray,
        rtl: Boolean,
        unit: Float,
        baseline: Float?,
        nearestInk: IntArray? = null,
    ): Array<FloatArray> {
        val n = width * height
        var left = width
        var right = -1
        for (i in 0 until n) if (ink[i]) {
            val x = i % width
            if (x < left) left = x
            if (x > right) right = x
        }
        val start = if (rtl) right else left
        val end = if (rtl) left else right
        val count = centers.size.coerceAtLeast(1)
        val knotX = FloatArray(count + 2)
        val knotV = FloatArray(count + 2)
        knotX[0] = start.toFloat()
        knotV[0] = 0f
        for (k in centers.indices) {
            knotX[k + 1] = centers[k]
            knotV[k + 1] = (k + 0.5f) / count
        }
        if (centers.isEmpty()) {
            knotX[1] = (start + end) / 2f
            knotV[1] = 0.5f
        }
        knotX[count + 1] = end.toFloat()
        knotV[count + 1] = 1f
        val order = knotX.indices.sortedBy { knotX[it] }
        val xs = FloatArray(order.size) { knotX[order[it]] }
        val vs = FloatArray(order.size) { knotV[order[it]] }
        var sweep = FloatArray(width) { interp(it.toFloat(), xs, vs) }
        sweep = gaussian1dNearest(sweep, LETTER_SMOOTHING * (right - left) / count)
        val s0 = sweep[start]
        val s1 = sweep[end]
        val span = if (s1 != s0) s1 - s0 else 1f
        for (x in 0 until width) sweep[x] = (sweep[x] - s0) / span

        val interior = FloatArray(n) { max(0f, depth[it]) }
        val radius = (2 * STROKE_RADIUS * scale * unit).toInt() / 2
        val halfWidth = maximumFilter(interior, width, height, radius)
        val footing = baseline?.let {
            baselineFooting(ink, width, height, it, STROKE_RADIUS * scale * unit * 2f, 1.5f * scale * unit)
        }
        val insideOut = FloatArray(n)
        val flowing = FloatArray(n)
        for (i in 0 until n) {
            val x = i % width
            val column = if (ink[i] && footing != null) footing[i] else x.toFloat()
            val fed = sampleColumns(sweep, column)
            val rim = 1f - (interior[i] / max(halfWidth[i], 1e-6f)).coerceIn(0f, 1f)
            insideOut[i] = fed + INSIDE_OUT_LAG / count * rim.pow(INSIDE_OUT_SHAPE)
            // Flowing ink's core-to-edge profile is linear (the Python reference's exponent of 1).
            flowing[i] = fed + FLOWING_LAG / count * rim
        }
        // Paper takes its nearest ink's arrival, so wet spread past the contour agrees.
        val nearest = nearestInk ?: edt(BooleanArray(n) { !ink[it] }, width, height, wantIndex = true).index!!
        return arrayOf(normalized(insideOut, ink, nearest), normalized(flowing, ink, nearest))
    }

    private fun normalized(arrival: FloatArray, ink: BooleanArray, nearest: IntArray): FloatArray {
        val n = arrival.size
        val extrapolated = FloatArray(n) { arrival[nearest[it]] }
        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        for (i in 0 until n) if (ink[i]) {
            low = min(low, extrapolated[i])
            high = max(high, extrapolated[i])
        }
        val range = if (high > low) high - low else 1f
        return FloatArray(n) { ((extrapolated[it] - low) / range).coerceIn(0f, 1f) }
    }

    /** numpy `interp(x, arange(len), values)`: linear between columns, clamped. */
    internal fun sampleColumns(values: FloatArray, x: Float): Float {
        if (x <= 0f) return values[0]
        val last = values.size - 1
        if (x >= last) return values[last]
        val i = x.toInt()
        return values[i] + (values[i + 1] - values[i]) * (x - i)
    }

    /**
     * x of the baseline where each pixel's own stroke stands. Ink paths strongly
     * prefer vertical steps, so a leaning alif maps its whole height to its foot;
     * the projection fades as drift per rise grows past a stem's lean.
     */
    internal fun baselineFooting(
        ink: BooleanArray,
        width: Int,
        height: Int,
        baseline: Float,
        stem: Float,
        band: Float,
    ): FloatArray {
        val n = width * height
        val footing = FloatArray(n) { (it % width).toFloat() }
        val dist = FloatArray(n) { Float.MAX_VALUE }
        val source = IntArray(n) { -1 }
        val heap = MinHeap(n)
        for (i in 0 until n) if (ink[i] && abs(i / width - baseline) <= band) {
            dist[i] = 0f
            source[i] = i
            heap.push(i, 0f)
        }
        if (heap.isEmpty()) return footing
        val dxs = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
        val dys = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)
        while (!heap.isEmpty()) {
            val node = heap.popNode()
            val d = heap.lastKey
            if (d > dist[node]) continue
            val x = node % width
            val y = node / width
            for (k in 0 until 8) {
                val nx = x + dxs[k]
                val ny = y + dys[k]
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                val next = ny * width + nx
                if (!ink[next]) continue
                val cost = d + abs(dys[k]) + FOOTING_DRIFT_COST * abs(dxs[k])
                if (cost < dist[next]) {
                    dist[next] = cost
                    source[next] = source[node]
                    heap.push(next, cost)
                }
            }
        }
        for (i in 0 until n) {
            val s = source[i]
            if (!ink[i] || s < 0) continue
            val drift = (s % width - i % width).toFloat()
            val rise = abs(s / width - i / width).toFloat()
            val lean = ((abs(drift) / (rise + stem) - 0.25f) / 0.35f).coerceIn(0f, 1f)
            footing[i] += drift * (1f - lean * lean * (3f - 2f * lean))
        }
        return footing
    }

    /** Normal XY of the rounded liquid film (see `liquid_surface`), per mask pixel. */
    internal fun liquidSurface(
        distance: FloatArray,
        width: Int,
        height: Int,
        scale: Int,
        unit: Float,
    ): Pair<FloatArray, FloatArray> {
        val n = width * height
        val film = FloatArray(n) {
            val interior = max(0f, distance[it])
            interior / (interior + 3f * unit) * 3f * unit
        }
        val smooth = gaussian2dReflect(film, width, height, scale * 2f * unit)
        val nx = FloatArray(n)
        val ny = FloatArray(n)
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val gx = when {
                width == 1 -> 0f
                x == 0 -> smooth[i + 1] - smooth[i]
                x == width - 1 -> smooth[i] - smooth[i - 1]
                else -> (smooth[i + 1] - smooth[i - 1]) / 2f
            }
            val gy = when {
                height == 1 -> 0f
                y == 0 -> smooth[i + width] - smooth[i]
                y == height - 1 -> smooth[i] - smooth[i - width]
                else -> (smooth[i + width] - smooth[i - width]) / 2f
            }
            val ax = -gx * scale
            val ay = -gy * scale
            val length = sqrt(ax * ax + ay * ay + 1f)
            nx[i] = ax / length
            ny[i] = ay / length
        }
        return nx to ny
    }

    // ---- numerical building blocks (scipy equivalents) ----------------------

    class Edt(val distance: FloatArray, val index: IntArray?)

    /**
     * Exact Euclidean distance from every pixel to the nearest `false` pixel
     * (scipy `distance_transform_edt`), with that pixel's index when asked.
     * Felzenszwalb–Huttenlocher lower envelopes, columns then rows.
     */
    internal fun edt(feature: BooleanArray, width: Int, height: Int, wantIndex: Boolean): Edt {
        val n = width * height
        val inf = 1e20f
        // Column pass: squared vertical distance to the nearest background row.
        val colD = FloatArray(n)
        val colSite = IntArray(n)
        val f = FloatArray(max(width, height))
        val d = FloatArray(max(width, height))
        val site = IntArray(max(width, height))
        val v = IntArray(max(width, height))
        val z = FloatArray(max(width, height) + 1)
        for (x in 0 until width) {
            for (y in 0 until height) f[y] = if (feature[y * width + x]) inf else 0f
            envelope(f, height, d, site, v, z)
            for (y in 0 until height) {
                colD[y * width + x] = d[y]
                colSite[y * width + x] = site[y]
            }
        }
        val out = FloatArray(n)
        val index = if (wantIndex) IntArray(n) else null
        for (y in 0 until height) {
            for (x in 0 until width) f[x] = colD[y * width + x]
            envelope(f, width, d, site, v, z)
            for (x in 0 until width) {
                val i = y * width + x
                out[i] = sqrt(d[x])
                if (index != null) {
                    val sx = site[x]
                    index[i] = colSite[y * width + sx] * width + sx
                }
            }
        }
        return Edt(out, index)
    }

    /** 1D squared distance transform of sampled function [f] over [count] samples. */
    private fun envelope(f: FloatArray, count: Int, d: FloatArray, site: IntArray, v: IntArray, z: FloatArray) {
        var k = 0
        // Skip leading infinite samples: an all-infinite row has no sites.
        var first = -1
        for (q in 0 until count) if (f[q] < 1e19f) { first = q; break }
        if (first < 0) {
            for (q in 0 until count) { d[q] = 1e20f; site[q] = q }
            return
        }
        v[0] = first
        z[0] = -Float.MAX_VALUE
        z[1] = Float.MAX_VALUE
        for (q in first + 1 until count) {
            if (f[q] >= 1e19f) continue
            var s: Float
            while (true) {
                val p = v[k]
                s = ((f[q] + q.toFloat() * q) - (f[p] + p.toFloat() * p)) / (2f * (q - p))
                if (s <= z[k] && k > 0) k-- else break
            }
            if (s <= z[k]) {
                v[k] = q
                z[k + 1] = Float.MAX_VALUE
            } else {
                k++
                v[k] = q
                z[k] = s
                z[k + 1] = Float.MAX_VALUE
            }
        }
        k = 0
        for (q in 0 until count) {
            while (z[k + 1] < q) k++
            val p = v[k]
            d[q] = (q - p).toFloat() * (q - p) + f[p]
            site[q] = p
        }
    }

    /** Square-window maximum (scipy `maximum_filter`), separable sliding windows. */
    internal fun maximumFilter(values: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return values.copyOf()
        val rows = FloatArray(values.size)
        for (y in 0 until height) slidingMax(values, y * width, 1, width, radius, rows)
        val out = FloatArray(values.size)
        for (x in 0 until width) slidingMax(rows, x, width, height, radius, out)
        return out
    }

    private fun slidingMax(src: FloatArray, offset: Int, stride: Int, count: Int, radius: Int, dst: FloatArray) {
        val deque = IntArray(count)
        var head = 0
        var tail = 0
        var next = 0
        for (i in 0 until count) {
            val reach = min(count - 1, i + radius)
            while (next <= reach) {
                val value = src[offset + next * stride]
                while (tail > head && src[offset + deque[tail - 1] * stride] <= value) tail--
                deque[tail++] = next
                next++
            }
            while (deque[head] < i - radius) head++
            dst[offset + i * stride] = src[offset + deque[head] * stride]
        }
    }

    /** scipy `gaussian_filter1d(mode='nearest')`, truncate 4. */
    internal fun gaussian1dNearest(values: FloatArray, sigma: Float): FloatArray {
        if (sigma <= 0f) return values.copyOf()
        val kernel = gaussianKernel(sigma)
        val r = kernel.size / 2
        val last = values.size - 1
        return FloatArray(values.size) { i ->
            var sum = 0f
            for (k in kernel.indices) sum += kernel[k] * values[(i + k - r).coerceIn(0, last)]
            sum
        }
    }

    /**
     * scipy `gaussian_filter(mode='reflect')`, truncate 4, separable.
     *
     * The hot loop of field generation, and most of a word's box is empty
     * paper. Each row or column is reflect-padded once, then every window only
     * visits its stretch of nonzero samples: the skipped terms are exact zeros,
     * and the rest are summed in the same order, so the result is unchanged
     * to the bit.
     */
    internal fun gaussian2dReflect(values: FloatArray, width: Int, height: Int, sigma: Float): FloatArray {
        if (sigma <= 0f) return values.copyOf()
        val kernel = gaussianKernel(sigma)
        val r = kernel.size / 2
        val line = FloatArray(max(width, height) + 2 * r)
        val rows = FloatArray(values.size)
        for (y in 0 until height) {
            for (j in 0 until width + 2 * r) line[j] = values[y * width + reflect(j - r, width)]
            convolveLine(line, width, kernel, rows, y * width, 1)
        }
        val out = FloatArray(values.size)
        for (x in 0 until width) {
            for (j in 0 until height + 2 * r) line[j] = rows[reflect(j - r, height) * width + x]
            convolveLine(line, height, kernel, out, x, width)
        }
        return out
    }

    /** One padded [line] (count + 2r samples) through [kernel], into [dst]. */
    private fun convolveLine(line: FloatArray, count: Int, kernel: FloatArray, dst: FloatArray, offset: Int, stride: Int) {
        val span = kernel.size
        var first = -1
        var last = -1
        for (j in 0 until count + span - 1) if (line[j] != 0f) {
            if (first < 0) first = j
            last = j
        }
        for (x in 0 until count) {
            var sum = 0f
            if (first >= 0) {
                val from = max(0, first - x)
                val to = min(span - 1, last - x)
                for (k in from..to) {
                    val v = line[x + k]
                    if (v != 0f) sum += kernel[k] * v
                }
            }
            dst[offset + x * stride] = sum
        }
    }

    private fun gaussianKernel(sigma: Float): FloatArray {
        val radius = (4f * sigma + 0.5f).toInt()
        val kernel = FloatArray(2 * radius + 1) { exp(-0.5f * (it - radius).toFloat().pow(2) / (sigma * sigma)) }
        val total = kernel.sum()
        for (i in kernel.indices) kernel[i] /= total
        return kernel
    }

    /** scipy 'reflect': d c b a | a b c d | d c b a. */
    private fun reflect(i: Int, size: Int): Int {
        if (size == 1) return 0
        var j = i
        val period = 2 * size
        j = ((j % period) + period) % period
        return if (j < size) j else period - 1 - j
    }

    /** numpy `interp` over ascending [xs]: clamped at both ends. */
    internal fun interp(x: Float, xs: FloatArray, vs: FloatArray): Float {
        if (x <= xs[0]) return vs[0]
        val last = xs.size - 1
        if (x >= xs[last]) return vs[last]
        var lo = 0
        var hi = last
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (xs[mid] <= x) lo = mid else hi = mid
        }
        val dx = xs[hi] - xs[lo]
        return if (dx <= 0f) vs[hi] else vs[lo] + (vs[hi] - vs[lo]) * (x - xs[lo]) / dx
    }

    private fun sign(value: Float): Float = when {
        value > 0f -> 1f
        value < 0f -> -1f
        else -> 0f
    }

    /** Binary min-heap of (node, key) with lazy deletion. */
    private class MinHeap(capacity: Int) {
        private var nodes = IntArray(capacity.coerceAtLeast(16))
        private var keys = FloatArray(capacity.coerceAtLeast(16))
        private var size = 0
        var lastKey = 0f
            private set

        fun isEmpty() = size == 0

        fun push(node: Int, key: Float) {
            if (size == nodes.size) {
                nodes = nodes.copyOf(size * 2)
                keys = keys.copyOf(size * 2)
            }
            var i = size++
            while (i > 0) {
                val parent = (i - 1) / 2
                if (keys[parent] <= key) break
                nodes[i] = nodes[parent]
                keys[i] = keys[parent]
                i = parent
            }
            nodes[i] = node
            keys[i] = key
        }

        fun popNode(): Int {
            val top = nodes[0]
            lastKey = keys[0]
            size--
            if (size > 0) {
                val node = nodes[size]
                val key = keys[size]
                var i = 0
                while (true) {
                    var child = 2 * i + 1
                    if (child >= size) break
                    if (child + 1 < size && keys[child + 1] < keys[child]) child++
                    if (keys[child] >= key) break
                    nodes[i] = nodes[child]
                    keys[i] = keys[child]
                    i = child
                }
                nodes[i] = node
                keys[i] = key
            }
            return top
        }
    }
}
