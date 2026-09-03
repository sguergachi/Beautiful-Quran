package com.beautifulquran.ui.home

import com.beautifulquran.data.ReadingLayout
import com.beautifulquran.data.ReadingMode
import com.beautifulquran.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class WordSearchSourcesTest {
    @Test
    fun `English-only search uses the timed word gloss`() {
        assertEquals(
            com.beautifulquran.domain.WordSearchSources(
                arabic = false,
                wordGloss = true,
                transliteration = false,
                verseTranslation = false,
            ),
            wordSearchSources(Settings(readingMode = ReadingMode.ENGLISH_ONLY)),
        )
    }

    @Test
    fun `mushaf search uses only its Arabic text`() {
        val sources = wordSearchSources(
            Settings(
                readingLayout = ReadingLayout.MUSHAF,
                readingMode = ReadingMode.ARABIC_ONLY,
                showWordGloss = true,
                showTransliteration = true,
                showTranslation = true,
            ),
        )
        assertEquals(true, sources.arabic)
        assertEquals(false, sources.wordGloss)
        assertEquals(false, sources.transliteration)
        assertEquals(false, sources.verseTranslation)
    }

    @Test
    fun `bilingual search follows its visible optional lines`() {
        val sources = wordSearchSources(
            Settings(
                showWordGloss = false,
                showTransliteration = true,
                showTranslation = true,
            ),
        )
        assertEquals(true, sources.arabic)
        assertEquals(false, sources.wordGloss)
        assertEquals(true, sources.transliteration)
        assertEquals(true, sources.verseTranslation)
    }
}
