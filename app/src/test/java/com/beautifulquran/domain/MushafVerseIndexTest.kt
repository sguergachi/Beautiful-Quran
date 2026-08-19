package com.beautifulquran.domain

import com.beautifulquran.data.model.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The book numbered as one run of verses — what the scrub dial actually holds.
 */
class MushafVerseIndexTest {

    @Test
    fun `numbers verses in mushaf order, one ordinal each`() {
        val index = buildMushafVerseIndex(
            buildMushafCatalog(
                listOf(
                    word(1, 1, 1, page = 1, line = 1),
                    word(1, 1, 2, page = 1, line = 1),
                    word(1, 2, 1, page = 1, line = 2),
                    word(2, 1, 1, page = 2, line = 1),
                    word(2, 2, 1, page = 2, line = 2),
                ),
            ),
        )
        assertEquals(4, index.count)
        assertEquals(1 to 1, index.keyOf(1))
        assertEquals(1 to 2, index.keyOf(2))
        assertEquals(2 to 1, index.keyOf(3))
        assertEquals(2 to 2, index.keyOf(4))
        assertNull(index.keyOf(0))
        assertNull(index.keyOf(5))
    }

    @Test
    fun `a verse belongs to the leaf it is set on`() {
        val index = buildMushafVerseIndex(
            buildMushafCatalog(
                listOf(
                    word(1, 1, 1, page = 1, line = 1),
                    word(1, 2, 1, page = 2, line = 1),
                ),
            ),
        )
        assertEquals(1, index.pageOf(1))
        assertEquals(2, index.pageOf(2))
        // Off either end, the dial still gets a leaf it can turn to.
        assertEquals(1, index.pageOf(0))
        assertEquals(2, index.pageOf(9_999))
    }

    @Test
    fun `a leaf answers with the first verse that starts on it`() {
        // The top line of a leaf is usually finishing the verse before. The
        // thumb belongs where the reading begins, not on the carry-over.
        val index = buildMushafVerseIndex(
            buildMushafCatalog(
                listOf(
                    word(2, 1, 1, page = 1, line = 1),
                    word(2, 1, 2, page = 2, line = 1),
                    word(2, 2, 1, page = 2, line = 2),
                    word(2, 3, 1, page = 3, line = 1),
                ),
            ),
        )
        assertEquals(1, index.firstVerseOfPage(1))
        assertEquals(2, index.firstVerseOfPage(2))
        assertEquals(3, index.firstVerseOfPage(3))
    }

    @Test
    fun `a leaf inside one long verse borrows the verse it is continuing`() {
        val index = buildMushafVerseIndex(
            buildMushafCatalog(
                listOf(
                    word(2, 282, 1, page = 1, line = 1),
                    word(2, 282, 2, page = 2, line = 1),
                    word(2, 283, 1, page = 3, line = 1),
                ),
            ),
        )
        assertEquals(1, index.firstVerseOfPage(1))
        assertEquals(1, index.firstVerseOfPage(2))
        assertEquals(2, index.firstVerseOfPage(3))
    }

    @Test
    fun `chapter openings are the ordinals the dial stands tall`() {
        val index = buildMushafVerseIndex(
            buildMushafCatalog(
                listOf(
                    word(1, 1, 1, page = 1, line = 1),
                    word(1, 2, 1, page = 1, line = 2),
                    word(2, 1, 1, page = 2, line = 1),
                    word(3, 1, 1, page = 3, line = 1),
                ),
            ),
        )
        assertEquals(setOf(1, 3, 4), index.chapterStarts)
    }

    @Test
    fun `every leaf of an empty catalog still answers`() {
        val index = buildMushafVerseIndex(buildMushafCatalog(emptyList()))
        assertEquals(0, index.count)
        for (page in 1..MushafCatalog.MUSHAF_PAGE_COUNT) {
            assertTrue(index.firstVerseOfPage(page) >= 0)
        }
    }
}

private fun word(surahId: Int, ayah: Int, position: Int, page: Int, line: Int) =
    MushafSourceWord(
        surahId = surahId,
        ayah = ayah,
        word = Word(
            position = position,
            arabic = "ـ",
            translation = "",
            transliteration = "",
            qcfPage = page,
            qcfLine = line,
        ),
    )
