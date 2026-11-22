package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Ayah
import com.beautifulquran.data.model.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reader's search marks the word searched for, in English or Arabic. */
class InSurahSearchTest {

    // 1:1 as the reader holds it: Uthmani Arabic with word glosses.
    private val basmala = Ayah(
        surahId = 1,
        number = 1,
        text = "بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ",
        translation = "In the name of Allah, the Most Gracious, the Most Merciful.",
        words = listOf(
            Word(1, "بِسْمِ", "In (the) name", ""),
            Word(2, "ٱللَّهِ", "(of) Allah", ""),
            Word(3, "ٱلرَّحْمَٰنِ", "the Most Gracious", ""),
            Word(4, "ٱلرَّحِيمِ", "the Most Merciful", ""),
        ),
    )

    @Test
    fun `an English query marks the word whose gloss holds it`() {
        assertEquals(setOf(4), InSurahSearch.markedWords(basmala, "merciful"))
        assertEquals(setOf(3, 4), InSurahSearch.markedWords(basmala, "most"))
    }

    @Test
    fun `an English phrase marks every word it runs across`() {
        assertEquals(setOf(3, 4), InSurahSearch.markedWords(basmala, "gracious the most"))
        assertEquals(setOf(1, 2), InSurahSearch.markedWords(basmala, "name (of) allah"))
    }

    @Test
    fun `typed Arabic without tashkeel marks the Uthmani word`() {
        assertEquals(setOf(4), InSurahSearch.markedWords(basmala, "الرحيم"))
        // Hamza-less alef and the wasla alef both fold to a bare alef.
        assertEquals(setOf(2), InSurahSearch.markedWords(basmala, "الله"))
        assertEquals(setOf(3, 4), InSurahSearch.markedWords(basmala, "الرحمن الرحيم"))
        assertEquals(setOf(3, 4), InSurahSearch.markedWords(basmala, "الرحمنالرحيم"))
    }

    @Test
    fun `no query and no match mark nothing`() {
        assertTrue(InSurahSearch.markedWords(basmala, null).isEmpty())
        assertTrue(InSurahSearch.markedWords(basmala, "").isEmpty())
        assertTrue(InSurahSearch.markedWords(basmala, "throne").isEmpty())
        assertTrue(InSurahSearch.markedWords(basmala, "كرسي").isEmpty())
    }

    @Test
    fun `an ayah matches on a marked word or on its translation alone`() {
        assertTrue(InSurahSearch.matches(basmala, "merciful", setOf(4)))
        // "Allah, the" is only in the translation's punctuation.
        assertTrue(InSurahSearch.matches(basmala, "allah, the", emptySet()))
        assertFalse(InSurahSearch.matches(basmala, "throne", emptySet()))
    }
}
