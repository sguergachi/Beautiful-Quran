package com.beautifulquran.ui.home

import com.beautifulquran.ui.PageTurnSounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueInkTest {

    @Test
    fun `a full wipe lasts the sweep spike`() {
        assertEquals(237, continueWipeMs(spikeMs = 237, dry = 0f))
    }

    @Test
    fun `a resumed wipe covers only what is left`() {
        assertEquals(100, continueWipeMs(spikeMs = 200, dry = 0.5f))
        assertEquals(0, continueWipeMs(spikeMs = 237, dry = 1f))
    }

    @Test
    fun `sweep spike ends are the whoosh, not the quiet tail`() {
        val ends = PageTurnSounds.FLIPS.map { it.name to it.sweepSpikeEndMs }
        assertEquals(
            listOf(
                "Flip 2 (crisp)" to 148,
                "Flip 8 (busy)" to 237,
                "Flip 9 (soft sweep)" to 200,
            ),
            ends,
        )
        ends.forEach { (_, ms) ->
            assertTrue("$ms ms spike is shorter than the 460 ms stem tail", ms < 460)
        }
    }
}
