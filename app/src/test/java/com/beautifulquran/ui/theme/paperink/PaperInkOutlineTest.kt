package com.beautifulquran.ui.theme.paperink

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperInkOutlineTest {
    private val width = 80
    private val height = 70

    private fun binary(alpha: FloatArray): FloatArray {
        val inside = PaperInkField.edt(BooleanArray(alpha.size) { alpha[it] > .5f }, width, height, false).distance
        val outside = PaperInkField.edt(BooleanArray(alpha.size) { alpha[it] <= .5f }, width, height, false).distance
        return FloatArray(alpha.size) { i ->
            val d = inside[i] - outside[i]
            if (alpha[i] > 0f && alpha[i] < 1f) alpha[i] - .5f else d - if (d > 0f) .5f else -.5f
        }
    }

    private fun checkContour(shape: (Float, Float) -> Float) {
        val alpha = FloatArray(width * height) { i ->
            val x = i % width
            val y = i / width
            var inside = 0
            for (dy in 0 until 16) for (dx in 0 until 16) {
                if (shape(x + (dx + .5f) / 16 - .5f, y + (dy + .5f) / 16 - .5f) >= 0f) inside++
            }
            inside / 256f
        }
        val old = binary(alpha)
        val outline = PaperInkOutline.distance(alpha, width, height, 1, old)
        var oldError = 0f
        var newError = 0f
        var count = 0
        for (y in 6 until height - 6) for (x in 6 until width - 6) {
            val expected = shape(x.toFloat(), y.toFloat())
            if (abs(expected) >= 4f) continue
            val i = y * width + x
            oldError += (old[i] - expected) * (old[i] - expected)
            newError += (outline[i] - expected) * (outline[i] - expected)
            count++
        }
        assertTrue("independent analytic contour: $newError / $oldError", newError < oldError * .15f)
        assertTrue("mean squared contour error ${newError / count}", newError / count < .01f)
    }

    @Test fun fractionalCirclesKeepTheirSmoothArcWhenExpanded() {
        for (radius in listOf(12.23f, 23.4f)) checkContour { x, y ->
            val dx = x - 39.64f
            val dy = y - 35.17f
            radius - sqrt(dx * dx + dy * dy)
        }
    }

    @Test fun angledStrokesRetainFractionalEdgePositions() {
        for (degrees in listOf(0, 12, 27, 45, 69, 87)) {
            val angle = Math.toRadians(degrees.toDouble()).toFloat()
            checkContour { x, y -> (x - 40.18f) * cos(angle) + (y - 35.47f) * sin(angle) }
        }
    }

    @Test fun saddleCornersRemainFiniteAndKeepBothSigns() {
        val alpha = floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val distance = PaperInkOutline.distance(alpha, 3, 3, 2, FloatArray(9))
        for (i in alpha.indices) {
            assertTrue(distance[i].isFinite())
            assertEquals(alpha[i] > .5f, distance[i] > 0f)
        }
    }

    @Test fun aUniformMaskKeepsItsExistingFallbackDistance() {
        val fallback = FloatArray(16) { 3.5f }
        assertTrue(PaperInkOutline.distance(FloatArray(16) { 1f }, 4, 4, 2, fallback) === fallback)
    }
}
