package com.beautifulquran.ui.home

import com.beautifulquran.data.ReadingLayout
import com.beautifulquran.data.ReadingMode
import com.beautifulquran.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class WordSearchSourcesTest {
    @Test
    fun `scroll search uses the timed English word gloss`() {
        assertEquals(
            com.beautifulquran.domain.WordSearchSources(
                arabic = false,
                wordGloss = true,
                transliteration = false,
                verseTranslation = false,
            ),
            wordSearchSources(Settings()),
        )
    }

    @Test
    fun `mushaf search uses only its flowing English translation`() {
        val sources = wordSearchSources(
            Settings(
                readingLayout = ReadingLayout.MUSHAF,
                showWordGloss = true,
                showTransliteration = true,
                showTranslation = true,
            ),
        )
        assertEquals(false, sources.arabic)
        assertEquals(false, sources.wordGloss)
        assertEquals(false, sources.transliteration)
        assertEquals(true, sources.verseTranslation)
    }

    @Test
    fun `scroll search remains English gloss regardless of reading chrome`() {
        val sources = wordSearchSources(
            Settings(
                readingMode = ReadingMode.ARABIC_ONLY,
                showWordGloss = false,
                showTransliteration = true,
                showTranslation = true,
            ),
        )
        assertEquals(false, sources.arabic)
        assertEquals(true, sources.wordGloss)
        assertEquals(false, sources.transliteration)
        assertEquals(false, sources.verseTranslation)
    }
}
