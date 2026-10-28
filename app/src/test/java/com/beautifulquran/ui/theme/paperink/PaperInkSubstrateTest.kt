package com.beautifulquran.ui.theme.paperink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PaperInkSubstrateTest {
    @Test fun stableOpaqueDataHasConnectedCorrelatedPreferences() {
        val pixels = PaperInkSubstrate.generate(160, 80, 1f, 731)
        assertArrayEquals(pixels, PaperInkSubstrate.generate(160, 80, 1f, 731))
        assertFalse(pixels.contentEquals(PaperInkSubstrate.generate(160, 80, 1f, 732)))
        fun wick(i: Int) = (pixels[i] ushr 16) and 255
        for (i in pixels.indices) {
            assertEquals(255, pixels[i] ushr 24)
            assertEquals(0, pixels[i] and 255)
            val r = wick(i)
            val g = (pixels[i] ushr 8) and 255
            // Density shares the same fibre preference, without independent speckle.
            assertTrue(if (r >= 128) g >= r - 1 else g <= r + 1)
            // Keep coarse connected structure at the reference scale instead
            // of independent full-range changes between neighboring pixels.
            if (i % 160 != 0) assertTrue(abs(r - wick(i - 1)) <= 95)
        }
        assertTrue(pixels.map { (it ushr 16) and 255 }.distinct().size > 200)
    }

    @Test fun invalidScaleCannotCreateAnUnboundedOrNonfiniteField() {
        for (unit in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertTrue(runCatching { PaperInkSubstrate.generate(2, 2, unit, 0) }.isFailure)
        }
    }
}
