package com.beautifulquran.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class EnglishTypesetTest {
    @Test
    fun `curls quotes and apostrophes and dashes a spaced hyphen`() {
        assertEquals(
            "And of the people are some who say, “We believe in Allah and the Last Day,” but they are not believers",
            EnglishTypography.typeset(
                "And of the people are some who say, \"We believe in Allah and the Last Day,\" but they are not believers",
            ),
        )
        assertEquals(
            "Indeed, those who disbelieve – it is all the same for them",
            EnglishTypography.typeset("Indeed, those who disbelieve - it is all the same for them"),
        )
        assertEquals("Allah’s Ever-Living", EnglishTypography.typeset("Allah's Ever-Living"))
    }

    @Test
    fun `closes the quotations the source left open, inner first`() {
        assertEquals(
            "they say, “We are but reformers”",
            EnglishTypography.typeset("they say, \"We are but reformers"),
        )
        assertEquals(
            "and say, “Say, ‘Relieve us’”",
            EnglishTypography.typeset("and say, \"Say, 'Relieve us"),
        )
        assertEquals(
            "they say, “We are but reformers",
            EnglishTypography.typeset("they say, \"We are but reformers", quoteContinues = true),
        )
    }

    @Test
    fun `a quotation continues when the next verse opens by closing it`() {
        assertTrue(EnglishTypography.quoteContinuesInto("all of them,\" He said"))
        assertFalse(EnglishTypography.quoteContinuesInto("He said, \"Indeed"))
        assertFalse(EnglishTypography.quoteContinuesInto("No quotation here"))
        assertFalse(EnglishTypography.quoteContinuesInto(null))
    }

    @Test
    fun `fold restores the stored text character for character`() {
        val raw = "And [recall] when We said, \"Enter - and say, 'Relieve us.' We will"
        val set = EnglishTypography.typeset(raw, quoteContinues = true)
        assertEquals(raw.length, set.length)
        assertEquals(raw, EnglishTypography.fold(set))
        assertEquals("Ali ’Imran", EnglishTypography.typesetName("Ali 'Imran"))
    }

    /** Every verse: indices preserved, no straight mark left, closers only at the end. */
    @Test
    fun `the whole translation typesets without moving a character`() {
        assumeTrue("Corpus check needs sqlite3", runCatching {
            ProcessBuilder("sqlite3", "-version").start().waitFor() == 0
        }.getOrDefault(false))
        val rows = ProcessBuilder(
            "sqlite3", "-separator", "\u001f", "-newline", "\u001e", repoFile("data/quran.db").path,
            "SELECT surah_id, ayah_number, translation_en FROM ayahs ORDER BY surah_id, ayah_number;",
        ).redirectErrorStream(true).start().inputStream.bufferedReader().readText()
            .split('\u001e').filter { it.isNotBlank() }.map { it.split('\u001f') }
        assertEquals(6236, rows.size)
        var closed = 0
        var continuing = 0
        rows.forEachIndexed { i, (surah, _, raw) ->
            val next = rows.getOrNull(i + 1)?.takeIf { it[0] == surah }?.get(2)
            val continues = EnglishTypography.quoteContinuesInto(next)
            if (continues) continuing++
            val set = EnglishTypography.typeset(raw, continues)
            assertEquals(raw, EnglishTypography.fold(set.substring(0, raw.length)))
            val tail = set.substring(raw.length)
            assertTrue(tail.all { it == '”' || it == '’' })
            if (tail.isNotEmpty()) closed++
            assertFalse(set.any { it == '"' || it == '\'' })
            assertFalse(" - " in set)
        }
        assertTrue("closed $closed", closed in 1_100..1_200)
        assertTrue("continuing $continuing", continuing in 20..40)
    }

    private fun repoFile(path: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("Could not find $path above ${File("").absolutePath}")
    }

    @Test
    fun `word initial ayn stays an apostrophe without adding a closer`() {
        listOf(
            "the family of 'Imran over the worlds" to "the family of ’Imran over the worlds",
            "Kaf, Ha, Ya, 'Ayn, Sad" to "Kaf, Ha, Ya, ’Ayn, Sad",
            "Unquestionably, 'Aad denied their Lord" to "Unquestionably, ’Aad denied their Lord",
            "the wife of al-'Azeez" to "the wife of al-’Azeez",
            "the righteous is in 'illiyyun" to "the righteous is in ’illiyyun",
            "Allah 's favour" to "Allah ’s favour",
            "say, 'We are" to "say, ‘We are’",
            "your 'partners' that" to "your ‘partners’ that",
        ).forEach { (raw, expected) -> assertEquals(expected, EnglishTypography.typeset(raw)) }
    }

    @Test
    fun `a leading closer does not cancel a later open quotation`() {
        assertEquals(
            "religion.” Say, “Indeed, the bounty”",
            EnglishTypography.typeset("religion.\" Say, \"Indeed, the bounty"),
        )
    }
}
