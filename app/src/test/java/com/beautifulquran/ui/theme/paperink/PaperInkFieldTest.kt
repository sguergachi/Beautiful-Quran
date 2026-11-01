package com.beautifulquran.ui.theme.paperink

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin generator against the Python reference (tools/build_paper_ink_study.py),
 * via fixtures from tools/export_paper_ink_fixture.py, plus the geometry rules
 * the paper ink spread must keep.
 */
class PaperInkFieldTest {
    private class Fixture(
        val width: Int,
        val height: Int,
        val scale: Int,
        val fontPx: Float,
        val centers: FloatArray,
        val baseline: Float,
        val letterDuration: Float,
        val arrays: List<FloatArray>,
    )

    private fun fixture(name: String): Fixture {
        val meta = javaClass.classLoader!!.getResource("paper-ink/$name.json")!!.readText()
        fun number(key: String) = Regex("\"$key\": ([-0-9.e]+)").find(meta)!!.groupValues[1].toFloat()
        val centers = Regex("\"centers\": \\[([^]]*)]").find(meta)!!.groupValues[1]
            .split(',').map { it.trim().toFloat() }.toFloatArray()
        val width = number("width").toInt()
        val height = number("height").toInt()
        val bytes = javaClass.classLoader!!.getResource("paper-ink/$name.bin")!!.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val arrays = List(6) { FloatArray(width * height).also { buffer.get(it) } }
        return Fixture(width, height, number("scale").toInt(), number("fontPx"), centers,
            number("baseline"), number("letterDuration"), arrays)
    }

    private fun generate(f: Fixture) = PaperInkField.generate(
        f.arrays[0], f.width, f.height, f.scale, f.centers, f.baseline, rtl = true, fontPx = f.fontPx,
    )!!

    /** Share of ink pixels within [tolerance] of the reference. */
    private fun agreement(expected: FloatArray, actual: FloatArray, alpha: FloatArray, tolerance: Float): Float {
        var ink = 0
        var close = 0
        for (i in expected.indices) if (alpha[i] > 0.5f) {
            ink++
            if (abs(expected[i] - actual[i]) <= tolerance) close++
        }
        return close.toFloat() / ink
    }

    @Test fun matchesThePythonReferenceOnRealHafsWords() {
        for (name in listOf("allah", "alrahman")) {
            val f = fixture(name)
            val result = generate(f)
            assertEquals(f.letterDuration, result.letterDuration, 1e-5f)
            val alpha = f.arrays[0]
            // Distances are exact; arrivals may differ where Dijkstra ties break differently.
            assertTrue(name, agreement(f.arrays[3], result.distance, alpha, 1e-3f) > 0.999f)
            assertTrue(name, agreement(f.arrays[1], result.insideOut, alpha, 0.01f) > 0.99f)
            assertTrue(name, agreement(f.arrays[2], result.flowing, alpha, 0.01f) > 0.99f)
            assertTrue(name, agreement(f.arrays[4], result.normalX, alpha, 0.01f) > 0.99f)
            assertTrue(name, agreement(f.arrays[5], result.normalY, alpha, 0.01f) > 0.99f)
        }
    }

    private fun rect(width: Int, height: Int, vararg boxes: IntArray): FloatArray {
        val alpha = FloatArray(width * height)
        for (b in boxes) for (y in b[1] until b[3]) for (x in b[0] until b[2]) alpha[y * width + x] = 1f
        return alpha
    }

    @Test fun anUprightStrokeFillsTogetherNotFromTheBottomUp() {
        val w = 300
        val h = 200
        val alpha = rect(w, h, intArrayOf(220, 10, 240, 190), intArrayOf(20, 160, 220, 190))
        for (flowing in listOf(false, true)) {
            val depth = PaperInkField.edt(BooleanArray(w * h) { alpha[it] > .5f }, w, h, false).distance
                .map { it / 4f }.toFloatArray()
            val field = PaperInkField.swellingChannels(
                BooleanArray(w * h) { alpha[it] > .5f }, depth, w, h, 4, floatArrayOf(230f, 120f),
                rtl = true, flowing = flowing, unit = 1f, baseline = null,
            )
            val rows = (30 until 130).map { y -> (222 until 238).map { field[y * w + it] }.average() }
            assertTrue(rows.maxOf { abs(it - rows[50]) } < 1e-6)
            assertTrue(field[175 * w + 230] < field[175 * w + 100])
            assertTrue(field[175 * w + 100] < field[175 * w + 30])
        }
    }

    @Test fun aLeaningStemIsFedFromItsFoot() {
        val w = 400
        val h = 220
        val alpha = FloatArray(w * h)
        for (y in 10 until 190) {
            val x = 250 - (190 - y) / 6
            for (c in x - 10 until x + 10) alpha[y * w + c] = 1f
        }
        for (y in 170 until 200) for (x in 20 until 300) alpha[y * w + x] = 1f
        val ink = BooleanArray(w * h) { alpha[it] > .5f }
        val depth = PaperInkField.edt(ink, w, h, false).distance.map { it / 4f }.toFloatArray()
        for (flowing in listOf(false, true)) {
            fun core(field: FloatArray, y: Int): Float {
                val xs = (0 until w).filter { ink[y * w + it] }
                return field[y * w + xs[xs.size / 2]]
            }
            val plain = PaperInkField.swellingChannels(ink, depth, w, h, 4, floatArrayOf(250f, 120f), true, flowing, 1f, null)
            val footed = PaperInkField.swellingChannels(ink, depth, w, h, 4, floatArrayOf(250f, 120f), true, flowing, 1f, 185f)
            assertTrue(core(plain, 30) - core(plain, 120) > .02f)
            assertTrue(abs(core(footed, 30) - core(footed, 120)) < .01f)
        }
    }

    @Test fun strokesSwellFromTheirCoreToBothEdges() {
        val w = 400
        val h = 60
        val alpha = rect(w, h, intArrayOf(10, 10, 390, 50))
        val ink = BooleanArray(w * h) { alpha[it] > .5f }
        val depth = PaperInkField.edt(ink, w, h, false).distance.map { it / 4f }.toFloatArray()
        val lags = listOf(false, true).map { flowing ->
            val field = PaperInkField.swellingChannels(
                ink, depth, w, h, 4, floatArrayOf(300f, 200f, 100f), false, flowing, 1f, null)
            fun row(y: Int) = (100 until 300).map { field[y * w + it] }.average()
            assertTrue(row(29) < row(10))
            assertEquals(row(10), row(49), 1e-6)
            row(10) - row(29)
        }
        assertTrue(lags[0] > lags[1])
    }

    @Test fun eachLetterGetsAnEqualTimeSlot() {
        val w = 420
        val h = 40
        val alpha = rect(w, h, intArrayOf(10, 10, 410, 30))
        val ink = BooleanArray(w * h) { alpha[it] > .5f }
        val depth = PaperInkField.edt(ink, w, h, false).distance.map { it / 4f }.toFloatArray()
        val paced = PaperInkField.swellingChannels(ink, depth, w, h, 4, floatArrayOf(390f, 350f, 170f), true, true, 1f, null)
        val even = PaperInkField.swellingChannels(ink, depth, w, h, 4, floatArrayOf(343f, 210f, 77f), true, true, 1f, null)
        assertTrue(paced[20 * w + 340] > 1.5f * even[20 * w + 340])
    }

    @Test fun anEmptyMaskHasNoField() {
        assertEquals(null, PaperInkField.generate(FloatArray(16), 4, 4, 2, floatArrayOf(1f), null, true, 40f))
        assertNotNull(PaperInkField.generate(FloatArray(16) { 1f }, 4, 4, 2, floatArrayOf(1f), null, true, 40f))
    }
}
