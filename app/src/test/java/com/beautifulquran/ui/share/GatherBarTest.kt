package com.beautifulquran.ui.share

import org.junit.Assert.assertEquals
import org.junit.Test

class GatherBarTest {
    @Test
    fun `nothing gathered teaches the gesture`() {
        assertEquals(GatherBarMiddle.Hint, gatherBarMiddle(count = 0, error = null, busy = false))
    }

    @Test
    fun `anything gathered names the ways it can go`() {
        assertEquals(GatherBarMiddle.Options, gatherBarMiddle(count = 1, error = null, busy = false))
        assertEquals(GatherBarMiddle.Options, gatherBarMiddle(count = 7, error = null, busy = false))
        // Preparing keeps the options up (each shows its own spinner).
        assertEquals(GatherBarMiddle.Options, gatherBarMiddle(count = 1, error = null, busy = true))
    }

    @Test
    fun `an error takes the slot until the next export starts`() {
        assertEquals(GatherBarMiddle.Error, gatherBarMiddle(count = 2, error = "Couldn't prepare", busy = false))
        assertEquals(GatherBarMiddle.Error, gatherBarMiddle(count = 0, error = "Couldn't prepare", busy = false))
        assertEquals(GatherBarMiddle.Options, gatherBarMiddle(count = 2, error = "Couldn't prepare", busy = true))
        assertEquals(GatherBarMiddle.Options, gatherBarMiddle(count = 2, error = "  ", busy = false))
    }
}
