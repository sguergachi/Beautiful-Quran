package com.beautifulquran.domain

import com.beautifulquran.data.model.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishLeafTest {

    @Test
    fun `a verse belongs to the leaf it begins on, whole`() {
        // 2:2 runs from page 3 onto page 4. A sentence cannot be cut at a page
        // break, so it is set entire on the leaf it starts on.
        val catalog = buildMushafCatalog(
            listOf(
                source(2, 2, 1, page = 3, line = 1),
                source(2, 2, 2, page = 4, line = 1),
                source(2, 3, 1, page = 4, line = 1),
            ),
        )
        assertEquals(listOf(2 to 2), englishLeafVerseKeys(catalog.page(3)!!))
        assertEquals(listOf(2 to 3), englishLeafVerseKeys(catalog.page(4)!!))

        assertEquals(
            listOf(2),
            englishLeaf(catalog.page(3)!!) { _, ayah -> "verse $ayah" }.verses.map { it.ayah },
        )
        assertEquals(
            listOf(3),
            englishLeaf(catalog.page(4)!!) { _, ayah -> "verse $ayah" }.verses.map { it.ayah },
        )
    }

    @Test
    fun `verses keep the page's own order`() {
        val catalog = buildMushafCatalog(
            listOf(
                source(2, 5, 1, page = 3, line = 1),
                source(2, 6, 1, page = 3, line = 1),
                source(2, 7, 1, page = 3, line = 2),
            ),
        )
        assertEquals(
            listOf(5, 6, 7),
            englishLeaf(catalog.page(3)!!) { _, ayah -> "verse $ayah" }.verses.map { it.ayah },
        )
    }

    @Test
    fun `verses run on as one paragraph, chapters do not`() {
        val catalog = buildMushafCatalog(
            listOf(
                source(1, 7, 1, page = 1, line = 14),
                source(2, 1, 1, page = 1, line = 15),
            ),
        )
        // Two chapters on one leaf — never on one line, which is why a chapter
        // opening is a boundary between blocks and not something inside one.
        val leaf = englishLeaf(catalog.page(1)!!) { _, ayah -> "verse $ayah" }
        assertEquals(
            listOf("Prose", "ChapterOpening", "Prose"),
            leaf.blocks.map { it::class.simpleName },
        )
        val opening = leaf.blocks[1] as EnglishLeafBlock.ChapterOpening
        assertEquals(2, opening.surahId)
        assertTrue(opening.basmalah)
    }

    @Test
    fun `Fatihah and Tawbah open without a basmalah preface`() {
        listOf(1, 9).forEach { surahId ->
            val catalog = buildMushafCatalog(listOf(source(surahId, 1, 1, page = 4, line = 1)))
            val opening = englishLeaf(catalog.page(4)!!) { _, ayah -> "verse $ayah" }
                .blocks
                .filterIsInstance<EnglishLeafBlock.ChapterOpening>()
                .single()
            assertFalse(opening.basmalah)
        }
    }

    @Test
    fun `a verse with no text of ours is dropped, not set as a hole`() {
        val catalog = buildMushafCatalog(
            listOf(source(2, 5, 1, page = 3, line = 1), source(2, 6, 1, page = 3, line = 1)),
        )
        val leaf = englishLeaf(catalog.page(3)!!) { _, ayah ->
            if (ayah == 5) "" else "verse $ayah"
        }
        assertEquals(listOf(6), leaf.verses.map { it.ayah })
    }

    @Test
    fun `the source's own line breaks are closed up - a page does not break a sentence`() {
        val catalog = buildMushafCatalog(listOf(source(2, 5, 1, page = 3, line = 1)))
        val leaf = englishLeaf(catalog.page(3)!!) { _, _ -> "  Alif\nLam   Mim  " }
        assertEquals("Alif Lam Mim", leaf.verses.single().text)
    }

    @Test
    fun `parentheticals come off when the reader has asked for it`() {
        val catalog = buildMushafCatalog(listOf(source(2, 5, 1, page = 3, line = 1)))
        val leaf = englishLeaf(catalog.page(3)!!, hideParentheticals = true) { _, _ ->
            "Alif [Lam] (Mim) Sad"
        }
        assertEquals("Alif Sad", leaf.verses.single().text)
    }

    @Test
    fun `prose mass counts each verse and the mark that closes it`() {
        val catalog = buildMushafCatalog(
            listOf(source(2, 5, 1, page = 3, line = 1), source(2, 6, 1, page = 3, line = 1)),
        )
        val leaf = englishLeaf(catalog.page(3)!!) { _, _ -> "abcd" }
        assertEquals(2 * (4 + ENGLISH_LEAF_MARK_CHARS), leaf.prose)
    }

    @Test
    fun `the reading page of a straddling verse is the leaf it began on`() {
        val catalog = buildMushafCatalog(
            listOf(
                source(2, 2, 1, page = 3, line = 1),
                source(2, 2, 2, page = 4, line = 1),
            ),
        )
        // The Arabic leaf follows the word: the second word is printed on 4.
        assertEquals(4, catalog.readingPageOf(2, 2, 2, wholeVerses = false))
        // The English leaf set the whole sentence on 3, so that is where the
        // reader is while it is being recited.
        assertEquals(3, catalog.readingPageOf(2, 2, 2, wholeVerses = true))
    }
}

private fun source(
    surahId: Int,
    ayah: Int,
    position: Int,
    page: Int,
    line: Int,
) = MushafSourceWord(
    surahId = surahId,
    ayah = ayah,
    word = Word(
        position = position,
        arabic = "و",
        translation = "",
        transliteration = "",
        qcfPage = page,
        qcfLine = line,
    ),
)
