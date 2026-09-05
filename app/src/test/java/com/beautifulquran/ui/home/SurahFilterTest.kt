package com.beautifulquran.ui.home

import com.beautifulquran.data.model.Surah
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SurahFilterTest {

    private val surahs = listOf(
        Surah(1, "الفاتحة", "Al-Fatihah", "The Opener", "meccan", 7),
        Surah(2, "البقرة", "Al-Baqarah", "The Cow", "medinan", 286),
        Surah(114, "الناس", "An-Nas", "Mankind", "meccan", 6),
    )

    @Test
    fun `blank query lists everything`() {
        val result = filterSurahs(surahs, "")
        assertEquals(surahs, result.surahs)
        assertNull(result.ayahTarget)
    }

    @Test
    fun `name matches are case-insensitive over transliteration and translation`() {
        assertEquals(listOf(surahs[1]), filterSurahs(surahs, "baqara").surahs)
        assertEquals(listOf(surahs[1]), filterSurahs(surahs, "cow").surahs)
    }

    @Test
    fun `misspelled name falls back to fuzzy matching`() {
        assertEquals(listOf(surahs[1]), filterSurahs(surahs, "baqrah").surahs)
        assertEquals(listOf(surahs[0]), filterSurahs(surahs, "opner").surahs)
        assertEquals(emptyList<Surah>(), filterSurahs(surahs, "\"baqrah\"").surahs)
    }

    @Test
    fun `arabic name matches exactly`() {
        assertEquals(listOf(surahs[2]), filterSurahs(surahs, "الناس").surahs)
    }

    @Test
    fun `bare surah number matches that surah`() {
        assertEquals(listOf(surahs[2]), filterSurahs(surahs, "114").surahs)
    }

    @Test
    fun `valid reference resolves surah and ayah target`() {
        val result = filterSurahs(surahs, "2:255")
        assertEquals(listOf(surahs[1]), result.surahs)
        assertEquals(255, result.ayahTarget)
    }

    @Test
    fun `surah-only reference has no ayah target`() {
        val result = filterSurahs(surahs, "114:")
        assertEquals(listOf(surahs[2]), result.surahs)
        assertNull(result.ayahTarget)
    }

    @Test
    fun `out-of-range ayah yields no results`() {
        val result = filterSurahs(surahs, "2:999")
        assertEquals(emptyList<Surah>(), result.surahs)
        assertNull(result.ayahTarget)
    }

    @Test
    fun `unknown surah reference yields no results`() {
        assertEquals(emptyList<Surah>(), filterSurahs(surahs, "3:1").surahs)
    }

    @Test
    fun `over-long reference numbers degrade gracefully instead of crashing`() {
        // Regression: these used to throw NumberFormatException and break the
        // whole search flow. A number too large to be a real reference simply
        // matches nothing.
        val result = filterSurahs(surahs, "99999999999:1")
        assertEquals(emptyList<Surah>(), result.surahs)
        assertNull(result.ayahTarget)
        assertEquals(emptyList<Surah>(), filterSurahs(surahs, "2:99999999999").surahs)
    }

    @Test
    fun `surrounding whitespace does not hide a name match`() {
        assertEquals(listOf(surahs[1]), filterSurahs(surahs, "  baqara  ").surahs)
        assertEquals(listOf(surahs[1]), filterSurahs(surahs, " cow ").surahs)
        assertEquals(listOf(surahs[2]), filterSurahs(surahs, " 114 ").surahs)
    }
}
