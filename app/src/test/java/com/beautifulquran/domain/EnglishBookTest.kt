package com.beautifulquran.domain

import com.beautifulquran.data.model.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The leaf is not the page. A Madinah page takes as many leaves as its English
 * needs at a legible size — see `EnglishBook.kt` for why that is what buys the
 * type its size.
 */
class EnglishBookTest {

    private fun book(vararg pages: Pair<Int, List<Pair<Int, Int>>>, mass: (Int, Int) -> Int) =
        buildEnglishBook(
            buildMushafCatalog(
                pages.flatMap { (page, verses) ->
                    verses.map { (s, a) -> source(s, a, page) }
                },
            ),
        ) { s, a -> mass(s, a) }

    @Test
    fun `a page that fits is one leaf, and that leaf is the page`() {
        val b = book(3 to listOf(2 to 1, 2 to 2, 2 to 3)) { _, _ -> 100 }
        val leaf = b.leaves[b.firstLeafOf(3)]
        assertEquals(3, leaf.page)
        assertEquals(1, leaf.parts)
        assertEquals(listOf(2 to 1, 2 to 2, 2 to 3), leaf.verses)
    }

    @Test
    fun `a page that will not fit takes two leaves, evenly`() {
        // Four verses of 500 = 2,000 characters against a 1,650 capacity: two
        // leaves of two verses, not one full leaf and a stub.
        val b = book(3 to listOf(2 to 1, 2 to 2, 2 to 3, 2 to 4)) { _, _ -> 500 }
        val first = b.firstLeafOf(3)
        assertEquals(2, b.leaves[first].parts)
        assertEquals(listOf(2 to 1, 2 to 2), b.leaves[first].verses)
        assertEquals(listOf(2 to 3, 2 to 4), b.leaves[first + 1].verses)
        assertEquals(1, b.leaves[first].part)
        assertEquals(2, b.leaves[first + 1].part)
    }

    @Test
    fun `both leaves of a page still name that page`() {
        val b = book(3 to listOf(2 to 1, 2 to 2, 2 to 3, 2 to 4)) { _, _ -> 500 }
        val first = b.firstLeafOf(3)
        assertEquals(3, b.leaves[first].page)
        assertEquals(3, b.leaves[first + 1].page)
    }

    @Test
    fun `a verse is found on the leaf that carries it, not the page's first`() {
        val b = book(3 to listOf(2 to 1, 2 to 2, 2 to 3, 2 to 4)) { _, _ -> 500 }
        val first = b.firstLeafOf(3)
        assertEquals(first, b.leafOfVerse(2, 1, page = 3))
        assertEquals(first + 1, b.leafOfVerse(2, 4, page = 3))
    }

    @Test
    fun `a verse the book does not carry falls back to its page`() {
        val b = book(3 to listOf(2 to 1)) { _, _ -> 100 }
        assertEquals(b.firstLeafOf(3), b.leafOfVerse(2, 99, page = 3))
    }

    @Test
    fun `a verse is never split across leaves, so a leaf may run over the average`() {
        // 1,600 then 200: one leaf cannot hold both, and the first cannot be
        // trimmed, so the split is where the verses allow.
        val b = book(3 to listOf(2 to 1, 2 to 2)) { _, a -> if (a == 1) 1600 else 200 }
        val first = b.firstLeafOf(3)
        assertEquals(2, b.leaves[first].parts)
        assertEquals(listOf(2 to 1), b.leaves[first].verses)
        assertEquals(listOf(2 to 2), b.leaves[first + 1].verses)
    }

    @Test
    fun `every page has a leaf, and the leaves run in page order`() {
        val b = book(
            1 to listOf(1 to 1),
            2 to listOf(2 to 1),
        ) { _, _ -> 100 }
        assertEquals(MushafCatalog.MUSHAF_PAGE_COUNT, b.leaves.count { it.parts == 1 })
        assertEquals(MushafCatalog.MUSHAF_PAGE_COUNT, b.leafCount)
        assertTrue(b.leaves.zipWithNext().all { (a, z) -> z.page >= a.page })
        assertEquals(0, b.firstLeafOf(1))
        assertEquals(1, b.firstLeafOf(2))
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
