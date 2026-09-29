package com.beautifulquran.ui.theme

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pause ligatures in Hafs (صلى U+06D6, قلى U+06D7) ink above the line box.
 * The faded-word rect stops at the line, so the ligature stayed full ink.
 * The cover runs from the baseline up to the glyph top.
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
    fun `cover reaches the glyph top above the line`() {
        val ink = raisedMarkInk(0x06D6)!!
        // Line ascent is 0.8em. The ligature top is 1.14em above the baseline,
        // so it clears the line by about a third of an em — the whole mark.
        val cover = raisedMarkCoverRect(
            cursorA = 100f,
            cursorB = 110f,
            lineTop = 200f,
            baseline = 280f,
            fontPx = 100f,
            ink = ink,
        )

        assertEquals(200f, cover.bottom, 0f)
        assertEquals(280f - ink.yMaxEm * 100f - 6f, cover.top, 0.01f)
        assertTrue(cover.height > 30f)
        assertTrue(cover.left < 100f)
        assertTrue(cover.right > 110f)
        assertFalse(cover.overlaps(Rect(0f, 200.5f, 500f, 360f)))
    }

    @Test
    fun `a mark inside the line grows no cover`() {
        val ink = raisedMarkInk(0x06D6)!!
        val cover = raisedMarkCoverRect(
            cursorA = 100f,
            cursorB = 110f,
            lineTop = 200f,
            baseline = 320f,
            fontPx = 100f,
            ink = ink,
        )
        assertTrue(cover.isEmpty)
    }

    @Test
    fun `no font size grows no cover`() {
        val ink = raisedMarkInk(0x06D7)!!
        assertTrue(
            raisedMarkCoverRect(
                0f, 10f, 200f, baseline = 280f, fontPx = 0f, ink = ink,
            ).isEmpty,
        )
    }
}
