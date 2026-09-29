package com.beautifulquran.ui.theme

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pause ligatures (صلى U+06D6, قلى U+06D7) are drawn off the cursor.
 * The line paper already covers the part of the stroke that sits in the
 * word. The trace keeps only the painted pixels outside that paper.
 */
class RaisedMarkOverhangTest {

    @Test
    fun `pause ligatures count, the sukun mark does not`() {
        assertTrue(hasRaisedQuranMark("شَيۡـٔٗاۖ", 0, "شَيۡـٔٗاۖ".length))
        assertTrue(hasRaisedQuranMark("بِهَاۗ", 0, "بِهَاۗ".length))
        // U+06E1, the small high dotless head. It sits inside the em square.
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
    fun `the lone word is blitted on whole pixels at the line's subpixel phase`() {
        val blit = raisedMarkTraceBlit(
            lineLeft = 100.4f,
            lineBaseline = 280.7f,
            wordLeft = 0f,
            wordBaseline = 80f,
            inset = 40f,
        )
        assertEquals(60f, blit.left, 0f)
        assertEquals(160f, blit.top, 0f)
        assertEquals(40.4f, blit.translateX, 0.001f)
        assertEquals(40.7f, blit.translateY, 0.001f)
    }

    @Test
    fun `ink beside the word is kept solid and ink inside the paper is cleared`() {
        // Pixel centres: x = 89.5 + x + 0.5, y = 189.5 + y + 0.5.
        val width = 30
        val height = 61
        val pixels = IntArray(width * height)
        val ink = 0xFFDCC8A0.toInt()
        val thin = 0x66DCC8A0
        val tipAbove = 0 // (0, 0) → (90, 190), above the line
        val capAbove = 20 // (20, 0) → (110, 190), above the line over the word
        val beside = 60 * width // (0, 60) → (90, 250), level with the body
        val body = 60 * width + 20 // (20, 60) → (110, 250), inside the paper
        pixels[tipAbove] = ink
        pixels[capAbove] = thin
        pixels[beside] = ink
        pixels[body] = ink
        val paper = Rect(100f, 200f, 400f, 360f)

        val keep = clearCoveredTracePixels(
            pixels = pixels,
            width = width,
            originX = 89.5f,
            originY = 189.5f,
            paper = paper,
        )

        assertTrue(keep)
        val solid = 0xFFFFFFFF.toInt()
        assertEquals(solid, pixels[tipAbove])
        assertEquals(solid, pixels[capAbove])
        assertEquals(solid, pixels[beside])
        assertEquals(0, pixels[body])
    }
}
