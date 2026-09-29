package com.beautifulquran.ui.theme

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pause ligatures in Hafs (صلى U+06D6, قلى U+06D7) paint above the line box.
 * The faded-word rect stops at the line, so those tips stayed full ink.
 * The bands are where the extra punch is allowed to look.
 */
class RaisedMarkOverhangTest {

    private val line = Rect(left = 100f, top = 200f, right = 400f, bottom = 280f)

    @Test
    fun `pause ligatures count, the sukun mark does not`() {
        assertTrue(hasRaisedQuranMark("شَيۡـٔٗاۖ", 0, "شَيۡـٔٗاۖ".length))
        assertTrue(hasRaisedQuranMark("بِهَاۗ", 0, "بِهَاۗ".length))
        // U+06E1, the small high dotless head. It sits inside the line.
        assertFalse(hasRaisedQuranMark("ٱلۡقِسۡطَ", 0, "ٱلۡقِسۡطَ".length))
        assertFalse(hasRaisedQuranMark("وَنَضَعُ", 0, "وَنَضَعُ".length))
    }

    @Test
    fun `only the spanned range is inspected`() {
        val text = "اۖب"
        assertFalse(hasRaisedQuranMark(text, 0, 1))
        assertTrue(hasRaisedQuranMark(text, 1, 2))
    }

    @Test
    fun `bands sit outside the line and meet it`() {
        val bands = raisedMarkBands(line, reach = 20f, horizontalPad = 8f)

        assertEquals(2, bands.size)
        val above = bands[0]
        assertEquals(line.top - 20f, above.top, 0f)
        assertEquals(line.top, above.bottom, 0f)
        assertEquals(line.left - 8f, above.left, 0f)
        assertEquals(line.right + 8f, above.right, 0f)
        val below = bands[1]
        assertEquals(line.bottom, below.top, 0f)
        assertEquals(line.bottom + 20f, below.bottom, 0f)
        assertTrue(above.bottom <= line.top)
        assertTrue(below.top >= line.bottom)
        assertFalse(above.overlaps(Rect(line.left, line.top + 1f, line.right, line.bottom - 1f)))
    }

    @Test
    fun `no reach grows no band`() {
        assertTrue(raisedMarkBands(line, reach = 0f, horizontalPad = 8f).isEmpty())
    }
}
