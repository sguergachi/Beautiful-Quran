package com.beautifulquran.domain

import kotlin.math.abs

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
 * many leaves as that needs, which for all but a handful of the 604 is two or
 * three, and the book runs to about 1,250 leaves. The type is then cut for the
 * leaf rather than for the worst page — half again as large, at about 46
 * characters to the line, which is a book measure.
 *
 * What is *not* given up is the page. Every leaf still knows which Madinah page
 * it belongs to, so the juzʾ, the running head and the reciter’s own place
 * on the paper all go on meaning exactly what they meant; page 255 is simply
 * two leaves long. What the leaf does take over is the *count*: the folio and
 * the page dial number leaves, because those are what a reader turns and lands
 * on, and a folio that repeated itself twice a page would be a lie about where
 * they are. See `mushafLeafNumber`.
 */

/**
 * How much prose a leaf holds, in characters — the verse text plus its mark.
 *
 * This is the one number the English book is set from. It is the capacity a
 * leaf is *paginated* to and the mass the hand is *cut* for, and it has to be
 * both or the two disagree and pages come out over- or under-full.
 *
 * 900 is chosen for the *line*, not for the page. A leaf of this mass sets at
 * about 22 sp on a phone and 46 characters to the line, which is where a serif
 * of EB Garamond's small x-height reads easily in the hand; the scrolling
 * reader has always set its English at 22 sp, and the leaf had drifted to 16.
 *
 * It is not free, and the cost is leaves. Fill is quantised — a page takes
 * whole verses, so a page of 1,400 characters becomes a full leaf and a half
 * one — and the smaller the capacity the more often that happens: the median
 * leaf reaches 81% of its well here and the book runs to about 1,250 leaves,
 * against 675 at 1,650 and 604 when a leaf was a page. That is the
 * trade, taken deliberately: white at the foot of a page costs the reader
 * nothing, and type they have to squint at costs them the page.
 *
 * Below about 850 it stops paying — the leaf count climbs by a hundred for
 * each percent of type — and above about 1,000 the line is longer than the
 * hand wants. `tools/measure_english_leaves.py` prints the sweep.
 */
const val ENGLISH_LEAF_CAPACITY_CHARS = 900

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
 * Splits one page's verses into leaves the way a compositor sets a book: fill
 * the leaf, and when the next verse will not go, start the next leaf.
 *
 * A verse is never split, because a verse is a sentence (`EnglishLeaf.kt`), so
 * the break lands before the verse that would overrun — which is also the
 * guarantee that no leaf is handed out over its capacity. Exactly one leaf in
 * the Qur'an still is: 2:282, a single sentence of 1,333 characters, half as
 * long again as a leaf holds and unsplittable by any rule. That leaf, alone, is
 * set tight by the fitted leading.
 *
 * This used to even the page out instead — a page of 1,700 became two leaves of
 * 850 rather than one full one and a stub. That was right when a page made at
 * most two leaves. It is not right now that a page makes two or three: evening
 * *every* leaf of the page means every leaf of the page is short, and measured
 * over the book it cost 137 extra leaves, ten points of median fill, and — the
 * thing it was there to prevent — nearly twice as many leaves under a third
 * full. Filling gives the fuller page; the stub is handled where stubs
 * actually happen, at the end.
 */
private fun englishPageParts(masses: List<Int>): List<IntRange> {
    val runs = ArrayList<IntRange>()
    var start = 0
    var run = 0
    masses.forEachIndexed { index, mass ->
        if (index > start && run + mass > ENGLISH_LEAF_CAPACITY_CHARS) {
            runs += start..index - 1
            start = index
            run = 0
        }
        run += mass
    }
    runs += start..masses.indices.last
    return englishCarriedBack(runs, masses)
}

/**
 * How empty the last leaf of a page may be before a verse is carried back into
 * it from the leaf above.
 *
 * Filling leaves the remainder at the end, and a remainder can be one short
 * verse — a leaf 5% full, which reads as a mistake rather than as an ending.
 * Half a leaf is where a short page still reads as a page.
 */
private const val ENGLISH_LEAF_STUB_FRACTION = 0.55f

/**
 * Evens out the last two leaves of a page when the last came out a stub.
 *
 * The compositor's move, and only at the end, where the remainder falls: take
 * the last verse off the fuller leaf and set it on the emptier one, while that
 * brings the two closer together. Over the book it turns 39 leaves under a
 * third full into 3, and costs three points of median fill.
 */
private fun englishCarriedBack(
    runs: List<IntRange>,
    masses: List<Int>,
): List<IntRange> {
    if (runs.size < 2) return runs
    val out = ArrayList(runs)
    val stub = ENGLISH_LEAF_STUB_FRACTION * ENGLISH_LEAF_CAPACITY_CHARS
    while (out.size >= 2) {
        val above = out[out.size - 2]
        val last = out[out.size - 1]
        val massAbove = (above.first..above.last).sumOf { masses[it] }
        val massLast = (last.first..last.last).sumOf { masses[it] }
        // Nothing to carry, or nothing to carry it from.
        if (massLast >= stub || above.last <= above.first) break
        val carried = masses[above.last]
        // Only while it evens them: a verse that would leave the upper leaf
        // emptier than the lower has not been carried back, it has been moved.
        val evened = abs((massAbove - carried) - (massLast + carried))
        if (evened >= abs(massAbove - massLast)) break
        out[out.size - 2] = above.first..above.last - 1
        out[out.size - 1] = above.last..last.last
    }
    return out
}
