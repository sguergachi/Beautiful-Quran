package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Word
import com.beautifulquran.domain.ENGLISH_LEAF_CAPACITY_CHARS
import com.beautifulquran.domain.MushafSourceWord
import com.beautifulquran.domain.buildEnglishBook
import com.beautifulquran.domain.buildMushafCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule is as long as the thing it measures.
 *
 * The pager, the folio and the page dial all number the same book, and the
 * dial did not: it was handed leaf numbers against a 604-page track, so the
 * chapter comb bunched into the middle of the rule and the end of the book
 * could not be reached. Every number [mushafLeafNumber] gives out has to land
 * inside [mushafBookLength].
 */
class MushafBookLengthTest {

    // A page whose English needs several leaves, so the two counts differ.
    private val catalog = buildMushafCatalog(
        (1..12).map { ayah -> source(2, ayah, page = 3) },
    )
    private val book = buildEnglishBook(catalog) { _, _ -> ENGLISH_LEAF_CAPACITY_CHARS / 2 }

    @Test
    fun `the Arabic rule is the mushaf, the English rule is the book`() {
        assertEquals(604, mushafBookLength(null, catalog.pageCount))
        assertEquals(book.leafCount, mushafBookLength(book, catalog.pageCount))
        // The point of the test: they are not the same number.
        assertTrue(book.leafCount != catalog.pageCount)
    }

    @Test
    fun `every leaf the reader can be sent to lands on the rule`() {
        val length = mushafBookLength(book, catalog.pageCount)
        (1..12).forEach { ayah ->
            val leaf = mushafLeafNumber(book, surahId = 2, ayah = ayah, page = 3)
            assertTrue("verse $ayah landed on leaf $leaf of $length", leaf in 1..length)
        }
    }

    @Test
    fun `every chapter's opening leaf lands on the rule too`() {
        // What the dial's coarse comb is built from.
        val length = mushafBookLength(book, catalog.pageCount)
        val opening = mushafLeafNumber(book, surahId = 2, ayah = 1, page = 3)
        assertTrue(opening in 1..length)
        // And the last leaf of the book is reachable: the comb has to be able
        // to stand on it, which a short rule made impossible.
        assertEquals(length, mushafLeafNumber(book, surahId = 2, ayah = 12, page = 3))
    }
}

private fun source(surahId: Int, ayah: Int, page: Int) = MushafSourceWord(
    surahId = surahId,
    ayah = ayah,
    word = Word(
        position = 1,
        arabic = "و",
        translation = "",
        transliteration = "",
        qcfPage = page,
        qcfLine = 1,
    ),
)
