package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ReciterSyncTest {
    @Test
    fun `Yasser highlight runs 200 ms ahead`() {
        assertEquals(200L, ReciterSync.highlightAdvanceMs(9))
    }

    @Test
    fun `other reciters keep their source clock`() {
        assertEquals(0L, ReciterSync.highlightAdvanceMs(1))
        assertEquals(0L, ReciterSync.highlightAdvanceMs(19))
    }
}
