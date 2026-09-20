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
}
