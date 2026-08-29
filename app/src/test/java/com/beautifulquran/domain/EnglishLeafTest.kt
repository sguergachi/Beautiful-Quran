package com.beautifulquran.domain

import com.beautifulquran.data.model.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishLeafTest {

    /** The leaf `EnglishBook` would make of one Madinah page's verses. */
    private fun leafOf(
        catalog: MushafCatalog,
        page: Int,
        hideParentheticals: Boolean = false,
        translation: (Int, Int) -> String,
    ) = englishLeaf(
        page = page,
        verses = englishLeafVerseKeys(catalog.page(page)!!),
        hideParentheticals = hideParentheticals,
        translation = translation,
    )

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
            leafOf(catalog, 3) { _, ayah -> "verse $ayah" }.verses.map { it.ayah },
        )
        assertEquals(
            listOf(3),
            leafOf(catalog, 4) { _, ayah -> "verse $ayah" }.verses.map { it.ayah },
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
            leafOf(catalog, 3) { _, ayah -> "verse $ayah" }.verses.map { it.ayah },
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
        // Two chapters on one leaf. The opening is a block of its own, so the
        // panel can never fall inside the paragraph above it.
        val leaf = leafOf(catalog, 1) { _, ayah -> "verse $ayah" }
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
            val opening = leafOf(catalog, 4) { _, ayah -> "verse $ayah" }
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
        val leaf = leafOf(catalog, 3) { _, ayah ->
            if (ayah == 5) "" else "verse $ayah"
        }
        assertEquals(listOf(6), leaf.verses.map { it.ayah })
    }

    @Test
    fun `the source's own line breaks are closed up - a page does not break a sentence`() {
        val catalog = buildMushafCatalog(listOf(source(2, 5, 1, page = 3, line = 1)))
        val leaf = leafOf(catalog, 3) { _, _ -> "  Alif\nLam   Mim  " }
        assertEquals("Alif Lam Mim", leaf.verses.single().text)
    }

    @Test
    fun `parentheticals come off when the reader has asked for it`() {
        val catalog = buildMushafCatalog(listOf(source(2, 5, 1, page = 3, line = 1)))
        val leaf = leafOf(catalog, 3, hideParentheticals = true) { _, _ ->
            "Alif [Lam] (Mim) Sad"
        }
        assertEquals("Alif Sad", leaf.verses.single().text)
    }

    @Test
    fun `prose mass counts each verse and the mark that closes it`() {
        val catalog = buildMushafCatalog(
            listOf(source(2, 5, 1, page = 3, line = 1), source(2, 6, 1, page = 3, line = 1)),
        )
        val leaf = leafOf(catalog, 3) { _, _ -> "abcd" }
        assertEquals(2 * (4 + ENGLISH_LEAF_MARK_CHARS), leaf.prose)
    }

    @Test
    fun `a leaf may carry verses from either side of a page break`() {
        // The English book paginates itself, so a run of verses handed to the
        // leaf is set as one page whether or not the Arabic broke inside it.
        val leaf = englishLeaf(
            page = 3,
            verses = listOf(2 to 5, 2 to 6, 2 to 7),
        ) { _, ayah -> "verse $ayah" }
        assertEquals(3, leaf.page)
        assertEquals(listOf(5, 6, 7), leaf.verses.map { it.ayah })
        assertEquals(listOf("Prose"), leaf.blocks.map { it::class.simpleName })
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
