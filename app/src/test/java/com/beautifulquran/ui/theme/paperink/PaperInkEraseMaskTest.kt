package com.beautifulquran.ui.theme.paperink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperInkEraseMaskTest {
    @Test fun `replacement preserves unread coverage across every antialiased edge level`() {
        val coverage = ByteArray(256) { it.toByte() }
        val erase = paperInkEraseMask(coverage)
        val unread = .2661f
        for (i in coverage.indices) {
            val glyph = i / 255f
            val oldInk = glyph * unread
            val remaining = oldInk * (1f - (erase[i].toInt() and 255) / 255f)
            val replacement = glyph * unread
            val sourceOver = replacement + remaining * (1f - replacement)
            assertEquals("edge coverage $i must not darken at handover", replacement, sourceOver, .000001f)
            assertEquals(i.toByte(), coverage[i])
        }
        // Negative control: a fractional eraser leaves a visible second coat.
        val old = .5f * unread
        assertTrue(old + old * .5f * (1f - old) > old + .05f)
    }

    @Test fun `erasure leaves empty paper untouched and clears even the faintest glyph pixel`() {
        val mask = paperInkEraseMask(byteArrayOf(0, 1, 127, 255.toByte(), 0))
        assertEquals(listOf(0, 255, 255, 255, 0), mask.map { it.toInt() and 255 })
    }
}
