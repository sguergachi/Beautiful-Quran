package com.beautifulquran.domain

import com.beautifulquran.data.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerseOpeningTest {

    private fun seg(start: Long, end: Long) = Segment(position = 1, startMs = start, endMs = end)

    @Test
    fun `silence before a word seeks to that word`() {
        // Hani 4:148 opens at 1951ms. 4:147 opens at 220ms.
        val nisa148 = listOf(seg(1_951, 4_790), seg(4_790, 5_510))
        assertEquals(1_951L, nextVoicedMs(nisa148, positionMs = 0L))
        assertEquals(220L, nextVoicedMs(listOf(seg(220, 800)), positionMs = 0L))
        // A pause between two words. Hani 3:15 sits 2050ms between words 17 and 18.
        val paused = listOf(seg(0, 1_000), seg(3_050, 4_000))
        assertEquals(3_050L, nextVoicedMs(paused, positionMs = 1_200L))
    }

    @Test
    fun `a sounding word and a tiny gap stay`() {
        val verse = listOf(seg(0, 1_000), seg(1_020, 2_000))
        assertNull(nextVoicedMs(verse, positionMs = 400L))
        assertNull(nextVoicedMs(verse, positionMs = 1_000L))
        assertNull(nextVoicedMs(listOf(seg(30, 500)), positionMs = 0L))
    }

    @Test
    fun `trailing silence is the end of the verse`() {
        val verse = listOf(seg(100, 5_000))
        assertFalse(isAfterLastWord(verse, positionMs = 4_900L))
        assertFalse(isAfterLastWord(verse, positionMs = 5_010L))
        assertTrue(isAfterLastWord(verse, positionMs = 5_040L))
        assertNull(nextVoicedMs(verse, positionMs = 5_200L))
    }

    @Test
    fun `the verse after a finished one follows repeat`() {
        assertEquals(149, nextVerseAyah(148, 176, repeatOne = false, repeatAll = false, range = null, opensWithBasmalah = true))
        assertEquals(1, nextVerseAyah(0, 176, repeatOne = false, repeatAll = false, range = null, opensWithBasmalah = true))
        assertEquals(12, nextVerseAyah(12, 176, repeatOne = true, repeatAll = false, range = null, opensWithBasmalah = true))
        assertEquals(10, nextVerseAyah(12, 176, repeatOne = false, repeatAll = false, range = 10..12, opensWithBasmalah = true))
        assertNull(nextVerseAyah(176, 176, repeatOne = false, repeatAll = false, range = null, opensWithBasmalah = true))
        assertEquals(0, nextVerseAyah(176, 176, repeatOne = false, repeatAll = true, range = null, opensWithBasmalah = true))
        assertEquals(1, nextVerseAyah(129, 129, repeatOne = false, repeatAll = true, range = null, opensWithBasmalah = false))
    }
}
