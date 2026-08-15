package com.beautifulquran.domain

import com.beautifulquran.data.model.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MushafCatalogTest {

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
