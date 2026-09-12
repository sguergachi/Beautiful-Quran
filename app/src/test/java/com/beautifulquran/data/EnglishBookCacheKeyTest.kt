package com.beautifulquran.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EnglishBookCacheKeyTest {
    @Test
    fun `content key is stable across map order`() {
        val forward = linkedMapOf(1L to "In the name", 2L to "The Book")
        val reverse = linkedMapOf(2L to "The Book", 1L to "In the name")

        assertEquals(
            englishBookContentKey("layout", forward),
            englishBookContentKey("layout", reverse),
        )
    }

    @Test
    fun `content key changes with the QF prose`() {
        assertNotEquals(
            englishBookContentKey("layout", mapOf(1L to "the Book")),
            englishBookContentKey("layout", mapOf(1L to "the Scripture")),
        )
    }
}
