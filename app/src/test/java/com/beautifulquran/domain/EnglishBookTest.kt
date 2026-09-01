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
        ) { s, a -> everySentence(mass(s, a)) }

    /**
     * A verse that may be cut anywhere: a sentence end at every offset. The
     * pagination then behaves exactly as it did before it took sentences into
     * account, which is what most of these cases are about — the sentence rule
     * has its own cases below.
     */
    private fun everySentence(length: Int) =
        EnglishVerseMeasure(length, IntArray(length.coerceAtLeast(0)) { it })

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
    fun `a carried verse is found on the leaf the reciter is actually reading`() {
        // The bug this guards: a leaf holding only the tail of a verse answered
        // "the leaf it began on", so it never owned the voice - it sat recessed
        // and washless for as long as the first half took to recite, and a tap
        // on it looked like it had done nothing.
        val b = book(3 to listOf(2 to 2, 2 to 3)) { _, a -> if (a == 2) cap / 2 else cap }
        val first = b.firstLeafOf(3)
        val head = b.leaves[first].runs.single { it.ayah == 3 }
        // 2:3 is cut: the head leaf carries the front of it, the next the rest.
        assertTrue(head.to < cap)
        // At the opening of the verse the answer is the leaf it opens on, which
        // is what the dial and a deep link want.
        assertEquals(first, b.leafOfVerse(2, 3, page = 3))
        assertEquals(first, b.leafOfVerse(2, 3, page = 3, through = 0f))
        // Once the reciter is past the cut, it is the leaf that picks it up.
        assertEquals(first + 1, b.leafOfVerse(2, 3, page = 3, through = 0.99f))
    }

    @Test
    fun `a verse set whole answers the same leaf however far through it is`() {
        val b = book(3 to listOf(2 to 2, 2 to 3)) { _, _ -> cap / 3 }
        val first = b.firstLeafOf(3)
        listOf(0f, 0.5f, 1f).forEach { through ->
            assertEquals(first, b.leafOfVerse(2, 3, page = 3, through = through))
        }
    }

    @Test
    fun `a verse the book does not carry falls back to its page`() {
        val b = book(3 to listOf(2 to 1)) { _, _ -> cap / 6 }
        assertEquals(b.firstLeafOf(3), b.leafOfVerse(2, 99, page = 3))
    }

    @Test
    fun `a verse longer than a leaf is carried across as many as it needs`() {
        // Twice a leaf, then a short one. No leaf can hold the first verse, so
        // it runs on — 2:282 is the one verse in the Qur'an this reaches.
        val b = book(3 to listOf(2 to 1, 2 to 2)) { _, a -> if (a == 1) cap * 2 else cap / 4 }
        val leaves = leavesFrom(b, 3)
        assertTrue(leaves.size >= 3)
        // Every leaf until the last carries part of it, in order and without
        // repeating or dropping a character.
        val runs = leaves.flatMap { it.runs }.filter { it.ayah == 1 }
        assertEquals(0, runs.first().from)
        runs.zipWithNext().forEach { (a, z) -> assertEquals(a.to, z.from) }
        // The short verse follows it, whole.
        val tail = leaves.last().runs.single { it.ayah == 2 }
        assertEquals(0, tail.from)
    }

    @Test
    fun `a verse is carried only when leaving it whole would waste a real hole`() {
        // Half a leaf, then a verse that will not fit in what is left. The hole
        // it would leave is worth cutting for, so the second verse is carried.
        val b = book(3 to listOf(2 to 2, 2 to 3)) { _, a -> if (a == 2) cap / 2 else cap }
        val leaves = leavesFrom(b, 3)
        assertEquals(2, leaves.size)
        assertEquals(2, leaves[0].runs.size)
        assertTrue(leaves[0].runs.last().to < cap)
    }

    @Test
    fun `even a small hole is filled, because the cut is a sentence`() {
        // This used to be left alone: a couple of spare lines were not worth
        // the end of a thought falling on the other side of a page turn. The
        // cut lands between sentences now, so it costs the reader nothing and
        // there is no reason to leave the foot of the leaf empty.
        val short = ENGLISH_LEAF_LINE_CHARS
        val b = book(3 to listOf(2 to 2, 2 to 3)) { _, a ->
            if (a == 2) cap - short else cap / 2
        }
        val leaves = leavesFrom(b, 3)
        assertEquals(2, leaves.size)
        assertEquals(listOf(2 to 2, 2 to 3), leaves[0].verses)
        assertEquals(listOf(2 to 3), leaves[1].verses)
        // The hole is taken by the head of verse 3, which continues overleaf.
        assertTrue(leaves[0].runs.last().let { it.from == 0 && !it.endsVerse(cap / 2) })
        assertEquals(leaves[0].runs.last().to, leaves[1].runs.first().from)
    }

    @Test
    fun `a verse with nowhere to break is still set whole on the next leaf`() {
        // No sentence end in reach, so there is nothing to cut at — the verse
        // moves down entire, the way a paragraph too big for the foot does.
        val short = ENGLISH_LEAF_LINE_CHARS
        val b = buildEnglishBook(
            buildMushafCatalog(listOf(source(2, 2, 3), source(2, 3, 3))),
        ) { _, a ->
            if (a == 2) {
                EnglishVerseMeasure(cap - short, IntArray(0))
            } else {
                EnglishVerseMeasure(cap / 2, IntArray(0))
            }
        }
        val leaves = leavesFrom(b, 3)
        assertEquals(2, leaves.size)
        assertEquals(listOf(2 to 2), leaves[0].verses)
        assertEquals(listOf(2 to 3), leaves[1].verses)
        assertTrue(leaves.all { l -> l.runs.all { it.from == 0 } })
    }

    @Test
    fun `no leaf is set past its capacity, but the one that cannot fit`() {
        val masses = listOf(700, 500, 300, 800, 200, 640)
        val b = book(3 to masses.indices.map { 2 to it + 1 }) { _, a -> masses[a - 1] }
        val leaves = leavesFrom(b, 3)
        // Every verse of the page set, in order, and none skipped. A carried
        // one appears on both the leaf it leaves and the leaf it lands on.
        assertEquals(
            masses.indices.map { 2 to it + 1 },
            leaves.flatMap { it.verses }.distinct(),
        )
        // No leaf is set past its capacity, counting only what it actually sets.
        leaves.forEach { leaf ->
            assertTrue(leaf.runs.sumOf { it.to - it.from } <= cap)
        }
        // A carried verse hands its offset straight to the next leaf.
        leaves.zipWithNext { a, b2 ->
            // The mass a verse is given includes its mark; the text it sets
            // does not.
            val tail = a.runs.last()
            if (!tail.endsVerse(masses[tail.ayah - 1] - ENGLISH_LEAF_MARK_CHARS)) {
                assertEquals(tail.key, b2.runs.first().key)
                assertEquals(tail.to, b2.runs.first().from)
            }
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
