package com.beautifulquran.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordGapReachTest {
    // "aa bb " wraps on its space; "cc  dd" is the second line.
    private val text = "aa bb cc  dd"
    private val lineStarts = intArrayOf(0, 6)
    private val lineOf = { offset: Int -> lineStarts.indexOfLast { it <= offset } }

    @Test
    fun `a word takes the gap after it`() {
        assertEquals(2 until 3, trailingWordGap(text, 0, 2, lineOf))
    }

    @Test
    fun `a gap of several spaces is taken whole`() {
        assertEquals(8 until 10, trailingWordGap(text, 6, 8, lineOf))
    }

    @Test
    fun `the space a line wraps on is never taken`() {
        // Its box reaches into the next line: taking it lit words a line below.
        assertNull(trailingWordGap(text, 3, 5, lineOf))
    }

    @Test
    fun `the last word and a range inside a word reach nowhere`() {
        assertNull(trailingWordGap(text, 10, 12, lineOf))
        assertNull(trailingWordGap(text, 0, 1, lineOf))
        assertNull(trailingWordGap("aa ", 0, 2, lineOf))
    }
}
