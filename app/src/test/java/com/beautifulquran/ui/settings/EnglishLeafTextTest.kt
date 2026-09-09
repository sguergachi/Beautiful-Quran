package com.beautifulquran.ui.settings

import com.beautifulquran.data.EnglishLeafText
import com.beautifulquran.data.ReadingLayout
import com.beautifulquran.data.ReadingMode
import com.beautifulquran.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The leaf may be set from the published translation or from the word-by-word
 * gloss, and the reader chooses. See `EnglishLeafText`.
 */
class EnglishLeafTextTest {

    @Test
    fun `the book reads as a book until the reader asks otherwise`() {
        assertEquals(EnglishLeafText.TRANSLATION, Settings().englishLeafText)
    }

    @Test
    fun `both readings are offered, and only those two`() {
        assertEquals(EnglishLeafText.entries.toList(), ENGLISH_LEAF_TEXTS)
    }

    @Test
    fun `choosing an English is not choosing a layout or a language`() {
        // The control sits under View, but it must not disturb what it sits
        // under: a reader switching between the two Englishes stays on the
        // leaf they were reading, in the language they were reading it in.
        val leaf = Settings(
            readingLayout = ReadingLayout.MUSHAF,
            readingMode = ReadingMode.ENGLISH_ONLY,
        )
        val swapped = leaf.copy(englishLeafText = EnglishLeafText.GLOSS)
        assertEquals(leaf.readingLayout, swapped.readingLayout)
        assertEquals(leaf.readingMode, swapped.readingMode)
        assertTrue(swapped.readingMode in MUSHAF_VIEW_MODES)
    }
}
