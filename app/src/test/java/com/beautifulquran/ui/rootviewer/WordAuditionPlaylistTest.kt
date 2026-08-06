package com.beautifulquran.ui.rootviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Root-viewer word audition must request the full chapter length so exit →
 * Play advances past the auditioned verse. Truncating to the word's ayah
 * left a dead-end playlist that stopped at the end of that verse.
 */
class WordAuditionPlaylistTest {

    @Test
    fun `playlist ayah count is the full chapter not the word ayah`() {
        assertEquals(7, wordAuditionPlaylistAyahCount(surahAyahCount = 7, wordAyah = 3))
        assertEquals(286, wordAuditionPlaylistAyahCount(surahAyahCount = 286, wordAyah = 1))
        assertEquals(7, wordAuditionPlaylistAyahCount(surahAyahCount = 7, wordAyah = 7))
    }

    @Test
    fun `unknown chapter metadata still covers the auditioned ayah`() {
        assertEquals(5, wordAuditionPlaylistAyahCount(surahAyahCount = 0, wordAyah = 5))
        assertEquals(1, wordAuditionPlaylistAyahCount(surahAyahCount = 0, wordAyah = 0))
    }

    @Test
    fun `full chapter count leaves room for later ayahs after a mid-surah word`() {
        val wordAyah = 3
        val count = wordAuditionPlaylistAyahCount(surahAyahCount = 7, wordAyah = wordAyah)
        assertTrue(
            "queue length must continue past the auditioned ayah",
            count > wordAyah,
        )
        // The old bug: ayahCount = wordAyah made the playlist end here.
        assertTrue(count != wordAyah || wordAyah == 7)
        assertEquals(7, count)
    }
}
