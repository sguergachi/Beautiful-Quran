package com.beautifulquran.domain

import com.beautifulquran.data.model.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MushafCatalogTest {

    @Test
    fun `word count spans a verse carried over a page`() {
        val catalog = buildMushafCatalog(
            listOf(
                source(2, 5, 1, "a", page = 2, line = 15),
                source(2, 5, 2, "b", page = 2, line = 15),
                source(2, 5, 3, "c", page = 3, line = 1),
                source(2, 6, 1, "d", page = 3, line = 1),
            ),
        )
        assertEquals(3, catalog.wordCountOf(2, 5))
        assertEquals(1, catalog.wordCountOf(2, 6))
        assertEquals(0, catalog.wordCountOf(2, 7))
    }

    @Test
    fun `drops unmatched page 0 and builds 1-based pages`() {
        val catalog = buildMushafCatalog(
            listOf(
                source(1, 1, 1, "بِسۡمِ", page = 0, line = 0),
                source(1, 1, 1, "بِسۡمِ", page = 1, line = 2),
                source(1, 1, 2, "ٱللَّهِ", page = 1, line = 2),
                source(2, 1, 1, "الٓمٓ", page = 2, line = 3),
            ),
        )
        assertNull(catalog.page(0))
        assertEquals(1, catalog.page(1)?.lines?.size)
        assertEquals(2, catalog.page(1)?.lines?.first()?.tokens?.size)
        assertEquals(2, catalog.firstPageOf(2))
        assertEquals(1, catalog.pageOf(1, 1, 2))
    }

    @Test
    fun `groups words by line and marks the ayah-final token`() {
        val catalog = buildMushafCatalog(
            listOf(
                source(1, 1, 1, "بِسۡمِ", page = 1, line = 2),
                source(1, 1, 2, "ٱللَّهِ", page = 1, line = 2),
                source(1, 2, 1, "ٱلۡحَمۡدُ", page = 1, line = 3),
                source(1, 2, 2, "لِلَّهِ", page = 1, line = 3),
            ),
        )
        val page = catalog.page(1)!!
        assertEquals(listOf(2, 3), page.lines.map { it.number })
        assertFalse(page.lines[0].tokens[0].endsAyah)
        assertTrue(page.lines[0].tokens[1].endsAyah)
        assertTrue(page.lines[1].tokens[1].endsAyah)
    }

    @Test
    fun `records a surah opening on the line that starts ayah 1`() {
        val catalog = buildMushafCatalog(
            listOf(
                source(1, 7, 9, "ٱلضَّآلِّينَ", page = 1, line = 8),
                source(2, 1, 1, "الٓمٓ", page = 2, line = 3),
                source(2, 2, 1, "ذَٰلِكَ", page = 2, line = 3),
            ),
        )
        assertTrue(catalog.page(1)!!.surahStarts.isEmpty())
        val start = catalog.page(2)!!.surahStarts.single()
        assertEquals(2, start.surahId)
        assertEquals(0, start.beforeLineIndex)
    }

    @Test
    fun `ayah that crosses a page break splits across two pages`() {
        val catalog = buildMushafCatalog(
            listOf(
                source(2, 5, 1, "أُوْلَـٰٓئِكَ", page = 2, line = 7),
                source(2, 5, 8, "ٱلۡمُفۡلِحُونَ", page = 2, line = 8),
                source(2, 6, 1, "إِنَّ", page = 3, line = 1),
            ),
        )
        assertEquals(2, catalog.pageOf(2, 5, 8))
        assertEquals(3, catalog.pageOf(2, 6, 1))
        assertEquals(setOf(2 to 5), catalog.page(2)!!.ayahKeys)
        assertEquals(setOf(2 to 6), catalog.page(3)!!.ayahKeys)
    }

    @Test
    fun `unknown word falls back to the ayah then the surah opening page`() {
        val catalog = buildMushafCatalog(
            listOf(source(114, 1, 1, "قُلۡ", page = 604, line = 15)),
        )
        assertEquals(604, catalog.pageOf(114, 1, 99))
        assertEquals(604, catalog.firstPageOf(114))
        assertEquals(1, catalog.firstPageOf(50))
    }

    @Test
    fun `display reflow shares extra rows without moving words between pages or chapters`() {
        val page = buildMushafCatalog(
            listOf(
                source(1, 7, 8, "one", page = 5, line = 1),
                source(1, 7, 9, "two", page = 5, line = 1),
                source(2, 1, 1, "three", page = 5, line = 2),
                source(2, 1, 2, "four", page = 5, line = 2),
                source(2, 1, 3, "five", page = 5, line = 2),
                source(2, 2, 1, "six", page = 5, line = 3),
                source(2, 2, 2, "seven", page = 5, line = 3),
                source(2, 2, 3, "eight", page = 5, line = 3),
            ),
        ).page(5)!!

        val reflowed = reflowMushafPage(page) { if (it.surahId == 1) 10f else 1f }

        assertEquals(5, reflowed.lines.size)
        assertEquals(
            page.lines.flatMap { it.tokens }.map { it.word.arabic },
            reflowed.lines.flatMap { it.tokens }.map { it.word.arabic },
        )
        assertEquals(2, reflowed.surahStarts.single().beforeLineIndex)
        assertEquals(2, reflowed.surahStarts.single().surahId)
    }

    @Test
    fun `a full leaf gains two rows and keeps every token in order`() {
        val page = buildMushafCatalog(
            (1..150).map { source(2, 17, it, "word$it", page = 4, line = (it - 1) / 10 + 1) },
        ).page(4)!!
        val reflowed = reflowMushafPage(page) { 1f }

        assertEquals(17, reflowed.lines.size)
        assertEquals(page.lines.flatMap { it.tokens }, reflowed.lines.flatMap { it.tokens })
        assertTrue(reflowed.lines.all { it.tokens.isNotEmpty() })
        val last = reflowed.lines.last().tokens.last()
        assertTrue(!mushafLineMayStandShort(4, last, surahAyahCount = 286))
        assertTrue(mushafLineMayStandShort(4, last, surahAyahCount = 17))
        assertTrue(!mushafLineMayStandShort(4, last.copy(endsAyah = false), surahAyahCount = 17))
        assertTrue(mushafLineMayStandShort(1, last, surahAyahCount = 286))
    }

    @Test
    fun `a section with no spare words does not spend an extra row`() {
        val page = buildMushafCatalog(
            listOf(
                source(112, 4, 1, "heavy", page = 600, line = 1),
                source(113, 1, 1, "one", page = 600, line = 2),
                source(113, 1, 2, "two", page = 600, line = 2),
                source(113, 1, 3, "three", page = 600, line = 2),
            ),
        ).page(600)!!
        val reflowed = reflowMushafPage(page) { if (it.surahId == 112) 100f else 1f }

        assertEquals(4, reflowed.lines.size)
        assertEquals(1, reflowed.surahStarts.single().beforeLineIndex)
        assertEquals(page.lines.flatMap { it.tokens }, reflowed.lines.flatMap { it.tokens })
        assertTrue(reflowed.lines.all { it.tokens.isNotEmpty() })
    }

    @Test
    fun `framed opening leaves keep the print's lines`() {
        val page = buildMushafCatalog(
            listOf(
                source(1, 1, 1, "bismillah", page = 1, line = 2),
                source(1, 1, 2, "allah", page = 1, line = 2),
                source(1, 2, 1, "alhamdu", page = 1, line = 3),
                source(1, 2, 2, "lillahi", page = 1, line = 3),
            ),
        ).page(1)!!
        val reflowed = reflowMushafPage(page) { 10f }
        assertEquals(page.lines.map { it.tokens.map { t -> t.word.arabic } },
            reflowed.lines.map { it.tokens.map { t -> t.word.arabic } })
        assertEquals(2, reflowed.lines.size)
    }
}

private fun source(
    surahId: Int,
    ayah: Int,
    position: Int,
    arabic: String,
    page: Int,
    line: Int,
) = MushafSourceWord(
    surahId = surahId,
    ayah = ayah,
    word = Word(
        position = position,
        arabic = arabic,
        translation = "",
        transliteration = "",
        qcfPage = page,
        qcfLine = line,
    ),
)
