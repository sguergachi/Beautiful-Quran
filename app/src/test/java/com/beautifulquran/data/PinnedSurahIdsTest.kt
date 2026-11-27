package com.beautifulquran.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins still keep an order — it is the order the Home band shows them in. */
class PinnedSurahIdsTest {
    @Test
    fun `legacy unordered pins keep the book order the band showed`() {
        assertEquals(listOf(2, 36, 67), readPinnedSurahIds(null, setOf("67", "2", "36")))
        assertEquals(listOf(67, 2), readPinnedSurahIds("67,2,x,67", setOf("1")))
    }
}
