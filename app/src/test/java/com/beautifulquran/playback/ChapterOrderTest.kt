package com.beautifulquran.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterOrderTest {
    private val all = (1..114).toList()

    @Test
    fun `the next chapter is the book's next`() {
        assertEquals(37, chapterAfter(36, all))
        assertEquals(2, chapterAfter(1, all))
    }

    @Test
    fun `the book's final chapter has no next`() {
        assertNull(chapterAfter(114, all))
    }

    @Test
    fun `an unknown chapter has no next`() {
        assertNull(chapterAfter(0, all))
    }
}
