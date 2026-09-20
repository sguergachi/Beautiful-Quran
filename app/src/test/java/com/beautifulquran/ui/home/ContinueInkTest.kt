package com.beautifulquran.ui.home

import com.beautifulquran.ui.PageTurnSounds
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueInkTest {

    @Test
    fun `wipe stays wet through the lift`() {
        assertEquals(0f, continueWipeProgress(0f))
        assertEquals(0f, continueWipeProgress(PageTurnSounds.SWEEP_AT))
    }

    @Test
    fun `wipe finishes the frame the drop — the ending — begins`() {
        assertEquals(1f, continueWipeProgress(PageTurnSounds.DROP_AT))
        assertEquals(1f, continueWipeProgress(1f))
    }

    @Test
    fun `wipe is half done midway from sweep to drop`() {
        val mid = (PageTurnSounds.SWEEP_AT + PageTurnSounds.DROP_AT) / 2f
        assertEquals(0.5f, continueWipeProgress(mid), 0.0001f)
    }

    @Test
    fun `a leftover wipe covers only what is left`() {
        assertEquals(212, continueWipeMs(dry = 0f))
        assertEquals(106, continueWipeMs(dry = 0.5f))
        assertEquals(0, continueWipeMs(dry = 1f))
    }

    @Test
    fun `Continue tap or a swipe into that chapter floods the row`() {
        assertEquals(
            true,
            continueInkShouldFlood(tapped = true, fromReader = false, toContinueChapter = false),
        )
        assertEquals(
            true,
            continueInkShouldFlood(tapped = false, fromReader = false, toContinueChapter = true),
        )
        assertEquals(
            false,
            continueInkShouldFlood(tapped = false, fromReader = false, toContinueChapter = false),
        )
        assertEquals(
            false,
            continueInkShouldFlood(tapped = true, fromReader = true, toContinueChapter = true),
        )
    }

    @Test
    fun `a swipe into the Continue chapter is a Continue turn`() {
        assertEquals(true, continueInkToContinueChapter(openSurahId = 18, continueSurahId = 18))
        assertEquals(false, continueInkToContinueChapter(openSurahId = 2, continueSurahId = 18))
        assertEquals(false, continueInkToContinueChapter(openSurahId = 18, continueSurahId = 0))
    }

    @Test
    fun `a return wipe only runs after a Continue-owned reader`() {
        assertEquals(true, continueInkShouldWipe(tapped = false, fromReader = true))
        assertEquals(false, continueInkShouldWipe(tapped = false, fromReader = false))
    }

    @Test
    fun `opening another chapter does not keep the row wet under the reader`() {
        assertEquals(
            false,
            continueInkShouldFill(tapped = false, spread = 0f, toContinueChapter = false),
        )
        assertEquals(
            true,
            continueInkShouldFill(tapped = true, spread = 0f, toContinueChapter = false),
        )
        assertEquals(
            true,
            continueInkShouldFill(tapped = false, spread = 1f, toContinueChapter = false),
        )
        assertEquals(
            true,
            continueInkShouldFill(tapped = false, spread = 0f, toContinueChapter = true),
        )
    }
}
