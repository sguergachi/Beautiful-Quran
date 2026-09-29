package com.beautifulquran.ui.theme

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pause ligatures in Hafs (صلى U+06D6, قلى U+06D7) ink above the em square.
 * The faded-word rect stops at the line, so those tips stayed full ink.
 * The cover is the ligature's own ink, just above the line.
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
    fun `cover sits on the ligature and stops at the line`() {
        val ink = raisedMarkInk(0x06D6)!!
        val cover = raisedMarkCoverRect(
            cursorA = 100f,
            cursorB = 110f,
            lineTop = 200f,
            fontPx = 100f,
            ink = ink,
        )

        assertEquals(200f, cover.bottom, 0f)
        // yMax clears the em square by 0.143em, plus the 0.04em edge margin.
        assertEquals(200f - (ink.aboveEm * 100f + 4f), cover.top, 0.01f)
        assertTrue(cover.top > 200f - 30f)
        assertTrue(cover.left < 100f)
        assertTrue(cover.right > 110f)
        assertFalse(cover.overlaps(Rect(0f, 200.5f, 500f, 280f)))
    }

    @Test
    fun `no font size grows no cover`() {
        val ink = raisedMarkInk(0x06D7)!!
        assertTrue(
            raisedMarkCoverRect(0f, 10f, 200f, fontPx = 0f, ink = ink).isEmpty,
        )
    }
}
