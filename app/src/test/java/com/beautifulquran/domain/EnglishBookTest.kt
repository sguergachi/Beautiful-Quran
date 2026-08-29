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

    /**
     * Masses are written against the capacity rather than as figures, so the
     * tests go on saying what they mean when the capacity is retuned.
     */
    private val cap = ENGLISH_LEAF_CAPACITY_CHARS

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
        val b = book(3 to listOf(2 to 1, 2 to 2, 2 to 3)) { _, _ -> cap / 6 }
        val leaf = b.leaves[b.firstLeafOf(3)]
        assertEquals(3, leaf.page)
        assertEquals(1, leaf.parts)
        assertEquals(listOf(2 to 1, 2 to 2, 2 to 3), leaf.verses)
    }

    @Test
    fun `a page that will not fit takes two leaves`() {
        // Four verses of just under half a leaf: each leaf fills with two of
        // them, and the third and fourth start the second leaf.
        val b = book(3 to listOf(2 to 1, 2 to 2, 2 to 3, 2 to 4)) { _, _ -> cap / 2 - 10 }
        val first = b.firstLeafOf(3)
        assertEquals(2, b.leaves[first].parts)
        assertEquals(listOf(2 to 1, 2 to 2), b.leaves[first].verses)
        assertEquals(listOf(2 to 3, 2 to 4), b.leaves[first + 1].verses)
        assertEquals(1, b.leaves[first].part)
        assertEquals(2, b.leaves[first + 1].part)
    }

    @Test
    fun `both leaves of a page still name that page`() {
        val b = book(3 to listOf(2 to 1, 2 to 2, 2 to 3, 2 to 4)) { _, _ -> cap / 2 - 10 }
        val first = b.firstLeafOf(3)
        assertEquals(3, b.leaves[first].page)
        assertEquals(3, b.leaves[first + 1].page)
    }

    @Test
    fun `a verse is found on the leaf that carries it, not the page's first`() {
        val b = book(3 to listOf(2 to 1, 2 to 2, 2 to 3, 2 to 4)) { _, _ -> cap / 2 - 10 }
        val first = b.firstLeafOf(3)
        assertEquals(first, b.leafOfVerse(2, 1, page = 3))
        assertEquals(first + 1, b.leafOfVerse(2, 4, page = 3))
    }

    @Test
    fun `a verse the book does not carry falls back to its page`() {
        val b = book(3 to listOf(2 to 1)) { _, _ -> cap / 6 }
        assertEquals(b.firstLeafOf(3), b.leafOfVerse(2, 99, page = 3))
    }

    @Test
    fun `a verse longer than a leaf is set whole, and set alone`() {
        // Twice a leaf, then a short one. No number of leaves splits a
        // sentence, so the long verse takes its own leaf and runs over it —
        // 2:282 is the one place in the Qur'an this happens.
        val b = book(3 to listOf(2 to 1, 2 to 2)) { _, a -> if (a == 1) cap * 2 else cap / 4 }
        val first = b.firstLeafOf(3)
        assertEquals(2, b.leaves[first].parts)
        assertEquals(listOf(2 to 1), b.leaves[first].verses)
        assertEquals(listOf(2 to 2), b.leaves[first + 1].verses)
    }

    @Test
    fun `a verse that will not go on the leaf starts the next one`() {
        // Three verses of two-thirds a leaf: no two of them go together, so
        // each takes a leaf and no leaf is over its well.
        val b = book(3 to listOf(2 to 1, 2 to 2, 2 to 3)) { _, _ -> cap * 2 / 3 }
        val first = b.firstLeafOf(3)
        assertEquals(3, b.leaves[first].parts)
        (0..2).forEach { assertEquals(1, b.leaves[first + it].verses.size) }
    }

    @Test
    fun `a stub at the end of a page has a verse carried back into it`() {
        // Filled, this page is a full leaf and a leaf a ninth full. The last
        // verse of the full leaf comes back, and both leaves are half pages.
        val masses = listOf(cap * 5 / 9, cap * 4 / 9, cap / 9)
        val b = book(3 to masses.indices.map { 2 to it + 1 }) { _, a -> masses[a - 1] }
        val first = b.firstLeafOf(3)
        assertEquals(2, b.leaves[first].parts)
        assertEquals(listOf(2 to 1), b.leaves[first].verses)
        assertEquals(listOf(2 to 2, 2 to 3), b.leaves[first + 1].verses)
    }

    @Test
    fun `a last leaf that is not a stub is left where it fell`() {
        // Nothing is carried back for its own sake: the last leaf here is
        // two-thirds full, which is a page that ends, not a mistake.
        val masses = listOf(cap / 2, cap / 2, cap * 2 / 3)
        val b = book(3 to masses.indices.map { 2 to it + 1 }) { _, a -> masses[a - 1] }
        val first = b.firstLeafOf(3)
        assertEquals(2, b.leaves[first].parts)
        assertEquals(listOf(2 to 1, 2 to 2), b.leaves[first].verses)
        assertEquals(listOf(2 to 3), b.leaves[first + 1].verses)
    }

    @Test
    fun `no leaf of the book is set past its capacity, but the one that cannot fit`() {
        // The guarantee, on an awkward page: every leaf inside its well, and
        // every verse of the page set exactly once.
        val masses = listOf(700, 500, 300, 800, 200, 640)
        val b = book(3 to masses.indices.map { 2 to it + 1 }) { _, a -> masses[a - 1] }
        val first = b.firstLeafOf(3)
        val leaves = (0 until b.leaves[first].parts).map { b.leaves[first + it] }
        assertEquals(masses.sum(), leaves.sumOf { l -> l.verses.sumOf { masses[it.second - 1] } })
        leaves.forEach { leaf ->
            assertTrue(leaf.verses.sumOf { masses[it.second - 1] } <= cap)
        }
    }

    @Test
    fun `every page has a leaf, and the leaves run in page order`() {
        val b = book(
            1 to listOf(1 to 1),
            2 to listOf(2 to 1),
        ) { _, _ -> cap / 6 }
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
