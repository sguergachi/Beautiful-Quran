package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What is set ahead of a turn, and which words the turn picture leaves to draw live. */
class MushafLeafWarmupTest {

    @Test
    fun `at rest the leaves either side are set nearest first`() {
        // Indices 0-based, pages 1-based: resting on index 10 (page 11).
        assertEquals(
            listOf(11, 12, 10, 13, 9, 14, 8),
            mushafWarmOrder(settledIndex = 10, currentIndex = 10, targetIndex = 10, pageCount = 604),
        )
    }

    @Test
    fun `a turn sets the leaves past its target before the ones behind it`() {
        val order = mushafWarmOrder(settledIndex = 10, currentIndex = 11, targetIndex = 11, pageCount = 604)
        assertEquals(listOf(12, 13, 14, 15), order.take(4))
        // The resting leaf's neighbours still follow.
        assertTrue(order.containsAll(listOf(9, 10, 11, 13)))
    }

    @Test
    fun `a run of flips heading back sets the earlier leaves first`() {
        val order = mushafWarmOrder(settledIndex = 20, currentIndex = 18, targetIndex = 17, pageCount = 604)
        assertEquals(listOf(18, 17, 16, 15), order.take(4))
    }

    @Test
    fun `pages outside the book are never asked for`() {
        assertEquals(
            listOf(1, 2, 3, 4),
            mushafWarmOrder(settledIndex = 0, currentIndex = 0, targetIndex = 0, pageCount = 604),
        )
        val last = mushafWarmOrder(settledIndex = 603, currentIndex = 603, targetIndex = 603, pageCount = 604)
        assertTrue(last.all { it in 1..604 })
        assertEquals(604, last.first())
    }

    @Test
    fun `no page is set twice`() {
        val order = mushafWarmOrder(settledIndex = 30, currentIndex = 33, targetIndex = 34, pageCount = 604)
        assertEquals(order.size, order.toSet().size)
    }

    @Test
    fun `a word too wide or tall for the glyph atlas draws live over the picture`() {
        assertTrue(mushafCellDrawnAsPath(MushafCell(300f, 0f, 280f, 120f)))
        assertTrue(mushafCellDrawnAsPath(MushafCell(100f, 0f, 90f, 240f)))
        assertFalse(mushafCellDrawnAsPath(MushafCell(200f, 4f, 196f, 180f)))
    }
}
