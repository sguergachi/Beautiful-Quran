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
    fun `a click stored as the first word seeks to the real voice`() {
        // Hani 4:148. The onset scan heard a click at 1951ms. Speech starts at 4283.
        val verse = listOf(Segment(1, 1_951, 4_790), Segment(2, 4_790, 5_510))
        val silence = listOf(AudibleSilence(0, 4_283))
        assertEquals(4_283L, playbackSkipMs(0L, verse, silence))
        assertEquals(4_283L, playbackSkipMs(1_951L, verse, silence))
        assertNull(playbackSkipMs(4_300L, verse, silence))
    }

    @Test
    fun `a lead that ends at the word start keeps the word clock`() {
        // Hani 4:58. Speech and the first word meet. The lead is not inside a word.
        val verse = listOf(Segment(1, 5_600, 7_080))
        assertEquals(5_600L, playbackSkipMs(0L, verse, emptyList()))
    }

    @Test
    fun `silence inside a word seeks to the next voice`() {
        // Hani 4:88. Words 9 and 10 are timed back to back across 1.2s of quiet.
        val verse = listOf(Segment(9, 14_750, 20_110), Segment(10, 20_130, 22_000))
        val silence = listOf(AudibleSilence(19_360, 20_560))
        assertEquals(20_560L, playbackSkipMs(19_400L, verse, silence))
        assertNull(playbackSkipMs(18_000L, verse, silence))
    }

    @Test
    fun `the silence table ignores a torn line`() {
        val parsed = parseAudibleSilence(
            """
            # comment
            4 148 0 4283
            bad
            4 88 19360 20560
            4 88 1 1

            """.trimIndent(),
        )
        assertEquals(listOf(AudibleSilence(0, 4_283)), parsed[silenceKey(4, 148)])
        assertEquals(listOf(AudibleSilence(19_360, 20_560)), parsed[silenceKey(4, 88)])
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
