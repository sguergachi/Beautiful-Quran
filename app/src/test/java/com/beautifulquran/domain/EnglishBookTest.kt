package com.beautifulquran.domain

import com.beautifulquran.data.model.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The English book paginates itself. A leaf is a run of the translation of
 * [ENGLISH_LEAF_CAPACITY_CHARS] or less — not a Madinah page, and not a slice
 * of one either. See `EnglishBook.kt` for why the borrowed boundary went.
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

    /** The leaves a page's verses land on, in order, with no repeats. */
    private fun leavesFrom(b: EnglishBook, page: Int): List<EnglishBookLeaf> {
        val first = b.firstLeafOf(page)
        return b.leaves.drop(first).takeWhile { page in it.pages }
    }

    @Test
    fun `a page that fits is one leaf, and that leaf is the page`() {
        val b = book(3 to listOf(2 to 1, 2 to 2, 2 to 3)) { _, _ -> cap / 6 }
        val leaf = b.leaves[b.firstLeafOf(3)]
        assertEquals(3, leaf.page)
        assertEquals(3..3, leaf.pages)
        assertEquals(listOf(2 to 1, 2 to 2, 2 to 3), leaf.verses)
    }

    @Test
    fun `a page that will not fit takes two leaves`() {
        // Four verses of just under half a leaf, none of them a chapter's
        // first: two go on, the third will not, and it opens the next leaf.
        val b = book(3 to listOf(2 to 2, 2 to 3, 2 to 4, 2 to 5)) { _, _ -> cap / 2 - 10 }
        val leaves = leavesFrom(b, 3)
        assertEquals(2, leaves.size)
        assertEquals(listOf(2 to 2, 2 to 3), leaves[0].verses)
        assertEquals(listOf(2 to 4, 2 to 5), leaves[1].verses)
    }

    @Test
    fun `a leaf runs straight through the page break`() {
        // The change this file exists for. Two pages of a third of a leaf each
        // used to be two leaves a third full; now they are one leaf.
        val b = book(
            3 to listOf(2 to 1, 2 to 2),
            4 to listOf(2 to 3, 2 to 4),
        ) { _, _ -> cap / 5 }
        val leaf = b.leaves[b.firstLeafOf(3)]
        assertEquals(listOf(2 to 1, 2 to 2, 2 to 3, 2 to 4), leaf.verses)
        // It opens on 3 and draws from both, and either page finds it.
        assertEquals(3, leaf.page)
        assertEquals(3..4, leaf.pages)
        assertEquals(b.firstLeafOf(3), b.firstLeafOf(4))
    }

    @Test
    fun `a verse is found on the leaf that carries it, not the page's first`() {
        val b = book(3 to listOf(2 to 2, 2 to 3, 2 to 4, 2 to 5)) { _, _ -> cap / 2 - 10 }
        val first = b.firstLeafOf(3)
        assertEquals(first, b.leafOfVerse(2, 2, page = 3))
        assertEquals(first + 1, b.leafOfVerse(2, 5, page = 3))
    }

    @Test
    fun `a verse the book does not carry falls back to its page`() {
        val b = book(3 to listOf(2 to 1)) { _, _ -> cap / 6 }
        assertEquals(b.firstLeafOf(3), b.leafOfVerse(2, 99, page = 3))
    }

    @Test
    fun `a verse longer than a leaf is set whole, and set alone`() {
        // Twice a leaf, then a short one. No pagination splits a sentence, so
        // the long verse takes its own leaf and runs over it — 2:282 is the one
        // place in the Qur'an this happens.
        val b = book(3 to listOf(2 to 1, 2 to 2)) { _, a -> if (a == 1) cap * 2 else cap / 4 }
        val leaves = leavesFrom(b, 3)
        assertEquals(2, leaves.size)
        assertEquals(listOf(2 to 1), leaves[0].verses)
        assertEquals(listOf(2 to 2), leaves[1].verses)
    }

    @Test
    fun `no leaf is set past its capacity, but the one that cannot fit`() {
        val masses = listOf(700, 500, 300, 800, 200, 640)
        val b = book(3 to masses.indices.map { 2 to it + 1 }) { _, a -> masses[a - 1] }
        val leaves = leavesFrom(b, 3)
        // Every verse of the page set exactly once, in order.
        assertEquals(
            masses.indices.map { 2 to it + 1 },
            leaves.flatMap { it.verses },
        )
        leaves.forEach { leaf ->
            assertTrue(leaf.verses.sumOf { masses[it.second - 1] } <= cap)
        }
    }

    @Test
    fun `Al-Fatihah opens the book on a leaf of its own`() {
        // The one break the packing keeps. Al-Fatihah is short enough that
        // Al-Baqarah would otherwise run on behind it.
        val b = book(
            1 to listOf(1 to 1, 1 to 2),
            2 to listOf(2 to 1, 2 to 2),
        ) { _, _ -> cap / 8 }
        assertEquals(listOf(1 to 1, 1 to 2), b.leaves[0].verses)
        assertEquals(listOf(2 to 1, 2 to 2), b.leaves[1].verses)
        assertEquals(1, b.firstLeafOf(2))
    }

    @Test
    fun `every chapter after the second runs on where it falls`() {
        // A book of translation does not start each of its 114 on a fresh page;
        // the panel is set inside the leaf, which is what keeps the leaves full.
        val b = book(3 to listOf(2 to 1, 3 to 1, 4 to 1)) { _, _ -> cap / 8 }
        assertEquals(1, leavesFrom(b, 3).size)
        assertEquals(listOf(2 to 1, 3 to 1, 4 to 1), b.leaves[b.firstLeafOf(3)].verses)
    }

    @Test
    fun `a chapter opening is charged the paper it takes`() {
        // The panel and its basmalah set no prose and take two lines of it.
        // Uncounted, the last leaf of the Qur'an — four chapters open on it —
        // spent two thirds of its well before a word was set.
        assertEquals(
            300 + ENGLISH_LEAF_OPENING_CHARS + ENGLISH_LEAF_BASMALAH_CHARS,
            englishLeafVerseMass(surahId = 2, ayah = 1, prose = 300),
        )
        // Al-Fatihah and At-Tawbah take no basmalah, so they are not charged one.
        assertEquals(
            300 + ENGLISH_LEAF_OPENING_CHARS,
            englishLeafVerseMass(surahId = 9, ayah = 1, prose = 300),
        )
        // Every other verse is its prose and nothing else.
        assertEquals(300, englishLeafVerseMass(surahId = 2, ayah = 2, prose = 300))
    }

    @Test
    fun `every page names a leaf, and the leaves run in page order`() {
        val b = book(
            1 to listOf(1 to 1),
            2 to listOf(2 to 1),
        ) { _, _ -> cap / 6 }
        assertTrue(b.leafCount >= 2)
        assertTrue(b.leaves.zipWithNext().all { (a, z) -> z.page >= a.page })
        assertEquals(0, b.firstLeafOf(1))
        assertEquals(1, b.firstLeafOf(2))
        // A page with no verse of its own reads as the leaf its neighbour opened.
        (1..MushafCatalog.MUSHAF_PAGE_COUNT).forEach {
            assertTrue(b.firstLeafOf(it) in 0 until b.leafCount)
        }
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
