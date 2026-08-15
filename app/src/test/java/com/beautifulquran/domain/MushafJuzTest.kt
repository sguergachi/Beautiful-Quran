package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MushafJuzTest {

    @Test
    fun `the book opens in juz one`() {
        assertEquals(1, juzOf(1, 1))
        assertEquals(1, juzOf(2, 141))
    }

    @Test
    fun `each juz opening lands on its own juz`() {
        assertEquals(2, juzOf(2, 142))
        assertEquals(3, juzOf(2, 253))
        assertEquals(15, juzOf(17, 1))
        assertEquals(30, juzOf(78, 1))
    }

    @Test
    fun `the ayah before an opening stays in the previous juz`() {
        assertEquals(1, juzOf(2, 141))
        assertEquals(2, juzOf(2, 252))
        assertEquals(29, juzOf(77, 50))
    }

    @Test
    fun `the last ayah of the book is juz thirty`() {
        assertEquals(30, juzOf(114, 6))
    }

    @Test
    fun `juz never leaves the one to thirty range`() {
        val surahAyahs = listOf(1 to 7, 2 to 286, 18 to 110, 36 to 83, 55 to 78, 114 to 6)
        surahAyahs.forEach { (surah, ayahs) ->
            (1..ayahs).forEach { ayah ->
                val juz = juzOf(surah, ayah)
                assert(juz in 1..MUSHAF_JUZ_COUNT) { "$surah:$ayah gave juz $juz" }
            }
        }
    }

    @Test
    fun `juz never runs backwards through the book`() {
        var previous = 0
        (1..114).forEach { surah ->
            val juz = juzOf(surah, 1)
            assert(juz >= previous) { "surah $surah dropped from juz $previous to $juz" }
            previous = juz
        }
    }
}
