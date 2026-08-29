package com.beautifulquran.domain

import kotlin.math.ceil

/*
 * The English book's leaves.
 *
 * Until now a leaf *was* a Madinah page: whatever fell on page 255 had to fit
 * one screen, and since the heaviest page in the book carries 1,997 characters
 * that page is what set the type for all 604 of them. Every leaf was legible
 * because the worst one had to be, which is another way of saying none of them
 * was as legible as it could have been.
 *
 * So the leaf stops being the page. A leaf holds
 * [ENGLISH_LEAF_CAPACITY_CHARS] of prose and no more; a Madinah page takes as
 * many leaves as that needs, and 71 of the 604 take two. The type is then cut
 * for the leaf rather than for the worst page — 14% larger, at about
 * 42 characters to the line — and because a page that overran is now split
 * into two leaves that each fill, the median leaf reaches 85% of its well
 * against 68% before. Larger type on fuller pages, from the same paper.
 *
 * What is *not* given up is the page. Every leaf still knows which Madinah page
 * it belongs to, so the folio, the juzʾ, the running head, the page dial and
 * the reciter's own place on the paper all go on meaning exactly what they
 * meant. Page 255 is simply two leaves long.
 */

/**
 * How much prose a leaf holds, in characters — the verse text plus its mark.
 *
 * This is the one number the English book is set from. It is the capacity a
 * leaf is *paginated* to and the mass the hand is *cut* for, and it has to be
 * both or the two disagree and pages come out over- or under-full.
 *
 * 1,650 is where the two costs cross. Larger, and the type shrinks toward the
 * old page-bound size; smaller, and pages start splitting into two half-empty
 * leaves faster than the type grows — at 1,500 the median leaf falls from 85%
 * full to 59% for a further 5% of type. Swept in
 * `tools/measure_english_leaves.py`.
 */
const val ENGLISH_LEAF_CAPACITY_CHARS = 1650

/** One leaf of the English book: a slice of one Madinah page. */
data class EnglishBookLeaf(
    /** The Madinah page this leaf belongs to. Its folio, its juzʾ, its dial. */
    val page: Int,
    /** 1-based, within that page. */
    val part: Int,
    /** How many leaves the page takes in all. */
    val parts: Int,
    /** The verses set on this leaf, in the page's own order. */
    val verses: List<Pair<Int, Int>>,
)

/**
 * The English book as a sequence of leaves, and the two lookups the reader
 * needs: where a page starts, and which leaf a verse is on.
 */
class EnglishBook internal constructor(
    val leaves: List<EnglishBookLeaf>,
    private val firstLeafByPage: IntArray,
    private val leafByVerse: Map<Long, Int>,
) {
    val leafCount: Int get() = leaves.size

    fun leaf(index: Int): EnglishBookLeaf? = leaves.getOrNull(index)

    /** The first leaf of a Madinah page — where the dial and a deep link land. */
    fun firstLeafOf(page: Int): Int =
        firstLeafByPage.getOrNull(page.coerceIn(1, MushafCatalog.MUSHAF_PAGE_COUNT)) ?: 0

    /**
     * The leaf a verse is set on. A verse that begins on a Madinah page is set
     * whole on one of that page's leaves (`EnglishLeaf.kt`), so this is exact
     * rather than a nearest-match; a verse the book does not carry falls back
     * to the first leaf of the page it began on.
     */
    fun leafOfVerse(surahId: Int, ayah: Int, page: Int): Int =
        leafByVerse[quranWordKey(surahId, ayah, 1)] ?: firstLeafOf(page)
}

/**
 * Paginates the whole book once.
 *
 * [prose] answers how much paper one verse takes, in the same characters
 * [ENGLISH_LEAF_CAPACITY_CHARS] counts. It is a *character* estimate rather
 * than a layout, deliberately: it is device-independent, so the book breaks in
 * the same places on every screen the way a printed book does, and it costs a
 * few hundred microseconds instead of six hundred text layouts. Where the
 * estimate is off, `MushafEnglishSheet` measures the leaf as it will actually
 * be drawn and closes its leading by the difference — the same guarantee that
 * has always caught it.
 */
fun buildEnglishBook(
    catalog: MushafCatalog,
    prose: (surahId: Int, ayah: Int) -> Int,
): EnglishBook {
    val leaves = ArrayList<EnglishBookLeaf>(MushafCatalog.MUSHAF_PAGE_COUNT)
    val firstLeafByPage = IntArray(MushafCatalog.MUSHAF_PAGE_COUNT + 1)
    val leafByVerse = HashMap<Long, Int>(6_500)
    for (page in 1..MushafCatalog.MUSHAF_PAGE_COUNT) {
        firstLeafByPage[page] = leaves.size
        val verses = catalog.page(page)?.let(::englishLeafVerseKeys).orEmpty()
        if (verses.isEmpty()) {
            leaves += EnglishBookLeaf(page = page, part = 1, parts = 1, verses = emptyList())
            continue
        }
        val parts = englishPageParts(verses.map { (s, a) -> prose(s, a) })
        parts.forEachIndexed { index, run ->
            val slice = verses.subList(run.first, run.last + 1)
            slice.forEach { (s, a) -> leafByVerse[quranWordKey(s, a, 1)] = leaves.size }
            leaves += EnglishBookLeaf(
                page = page,
                part = index + 1,
                parts = parts.size,
                verses = slice,
            )
        }
    }
    return EnglishBook(leaves, firstLeafByPage, leafByVerse)
}

/**
 * Splits one page's verses into leaves: as few as will hold them, then evened
 * out so the last one is not a stub.
 *
 * A page of 1,700 characters becomes two leaves of 850 rather than one of
 * 1,650 and one of 50 — the arithmetic that decides *how many* leaves is the
 * capacity, and the arithmetic that decides *where they break* is the average.
 * A verse is never split, because a verse is a sentence
 * (`EnglishLeaf.kt`), so a leaf may run over the average by one verse.
 */
private fun englishPageParts(masses: List<Int>): List<IntRange> {
    val total = masses.sum()
    val count = ceil(total.toDouble() / ENGLISH_LEAF_CAPACITY_CHARS).toInt().coerceAtLeast(1)
    if (count == 1) return listOf(masses.indices.first..masses.indices.last)
    val target = total.toDouble() / count
    val parts = ArrayList<IntRange>(count)
    var start = 0
    var run = 0
    masses.forEachIndexed { index, mass ->
        // Break *before* this verse when the leaf is already past its share and
        // there are leaves left to fill — never after, or the last leaf is the
        // one that overflows.
        if (run > 0 && run + mass > target && parts.size < count - 1) {
            parts += start..index - 1
            start = index
            run = 0
        }
        run += mass
    }
    parts += start..masses.indices.last
    return parts
}
