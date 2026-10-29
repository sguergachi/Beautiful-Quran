package com.beautifulquran.ui.theme.paperink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PaperInkSubstrateTest {
    private val tile = PaperInkSubstrate.TILE

    @Test fun stableOpaqueDataHasConnectedCorrelatedPreferences() {
        val pixels = PaperInkSubstrate.tile()
        assertEquals(tile * tile, pixels.size)
        assertArrayEquals(pixels, PaperInkSubstrate.tile())
        assertFalse(pixels.contentEquals(PaperInkSubstrate.tile(PaperInkSubstrate.SEED + 1)))
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
            if (i % tile != 0) assertTrue(abs(r - wick(i - 1)) <= 95)
        }
        assertTrue(pixels.map { (it ushr 16) and 255 }.distinct().size > 200)
    }

    @Test fun tileWrapsWithoutASeam() {
        // Every grain's lattice is a whole number of cells along the tile, so
        // a word's patch can start anywhere and run off any edge.
        for ((x, y) in listOf(.5f to .5f, 37.25f to 401.5f, 719.5f to 12f, 250f to 719.75f)) {
            val at = PaperInkSubstrate.sample(x, y)
            assertEquals(at, PaperInkSubstrate.sample(x + tile, y))
            assertEquals(at, PaperInkSubstrate.sample(x, y + tile))
            assertEquals(at, PaperInkSubstrate.sample(x - tile, y + 2 * tile))
        }
        val pixels = PaperInkSubstrate.tile()
        fun wick(x: Int, y: Int) = (pixels[y * tile + x] ushr 16) and 255
        // Across the wrap, neighbours stay as close as neighbours inside.
        for (y in 0 until tile step 7) {
            assertTrue(abs(wick(tile - 1, y) - wick(0, y)) <= 95)
            assertTrue(abs(wick(y, tile - 1) - wick(y, 0)) <= 95)
        }
    }

    @Test fun neighbouringPlacementsStartOnUnrelatedPaper() {
        val offsets = (0 until 64).map(::paperInkFibreOffset)
        for ((x, y) in offsets) {
            assertTrue(x in 0f..tile.toFloat() && y in 0f..tile.toFloat())
        }
        assertEquals(offsets.size, offsets.distinct().size)
        assertEquals(paperInkFibreOffset(41), paperInkFibreOffset(41))
        // Consecutive placements are far apart, not a small walk along the tile.
        val close = offsets.zipWithNext().count { (a, b) ->
            abs(a.first - b.first) < 60f && abs(a.second - b.second) < 60f
        }
        assertTrue(close <= 2)
    }
}
