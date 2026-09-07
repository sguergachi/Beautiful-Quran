package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Word
import com.beautifulquran.domain.ENGLISH_LEAF_CAPACITY_CHARS
import com.beautifulquran.domain.MushafSourceWord
import com.beautifulquran.domain.buildEnglishBook
import com.beautifulquran.domain.buildMushafCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The leaf opens only onto a finished book.
 *
 * The catalog builds empty-but-non-null until the runtime snapshot loads, so
 * nullness alone cannot gate the leaf: an empty catalog and an unleaved book
 * must both hold the cover (and the reader) until the root finishes them.
 */
class MushafBookReadyTest {

    private val emptyCatalog = buildMushafCatalog(emptyList())
    private val emptyBook = buildEnglishBook(emptyCatalog) { _, _ -> 0 }
    private val catalog = buildMushafCatalog(
        (1..12).map { ayah -> source(2, ayah, page = 3) },
    )
    private val book = buildEnglishBook(catalog) { _, _ ->
        ENGLISH_LEAF_CAPACITY_CHARS / 2
    }

    @Test
    fun `no book is not ready`() {
        assertFalse(mushafBookReady(null, englishOnly = false))
        assertFalse(mushafBookReady(null, englishOnly = true))
    }

    @Test
    fun `empty catalog is not ready`() {
        val mushaf = MushafUi(emptyCatalog, emptyMap(), emptyBook)
        assertFalse(mushafBookReady(mushaf, englishOnly = false))
        assertFalse(mushafBookReady(mushaf, englishOnly = true))
    }

    @Test
    fun `set arabic leaf is ready without leaves`() {
        val mushaf = MushafUi(catalog, emptyMap(), emptyBook)
        assertTrue(mushafBookReady(mushaf, englishOnly = false))
        assertFalse(mushafBookReady(mushaf, englishOnly = true))
    }

    @Test
    fun `english waits for the measured book, not the estimate`() {
        val estimate = MushafUi(catalog, emptyMap(), book, measured = false)
        assertTrue(mushafBookReady(estimate, englishOnly = false))
        assertFalse(mushafBookReady(estimate, englishOnly = true))
    }

    @Test
    fun `set english leaf is ready`() {
        val mushaf = MushafUi(catalog, emptyMap(), book, measured = true)
        assertTrue(mushafBookReady(mushaf, englishOnly = false))
        assertTrue(mushafBookReady(mushaf, englishOnly = true))
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
}
