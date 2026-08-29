package com.beautifulquran.domain

/*
 * The English book's leaves.
 *
 * Until now a leaf *was* a Madinah page: whatever fell on page 255 had to fit
 * one screen, and since the heaviest page in the book carries 1,997 characters
 * that page is what set the type for all 604 of them. Every leaf was legible
 * because the worst one had to be, which is another way of saying none of them
 * was as legible as it could have been.
 *
 * So the leaf stops being the page, in two steps, and the second one is the
 * one that matters.
 *
 * **A page may take more than one leaf.** A leaf holds
 * [ENGLISH_LEAF_CAPACITY_CHARS] of prose and no more, and a Madinah page takes
 * as many leaves as that needs. That alone buys the type half again its size.
 *
 * **A leaf may take more than one page.** This is the one that fixes the
 * whitespace. Keeping the page as a *boundary* means every page ends on a
 * remainder — its last leaf carries whatever is left over, and since a page now
 * makes two or three leaves, roughly half of every leaf in the book was a
 * remainder. Measured: 365 of 1,254 leaves came out under 70% full, and a
 * reader turning pages met a third of a blank one every other turn. Paginate
 * the translation continuously instead — verse after verse, straight through
 * the page breaks — and the same type gives 1,118 leaves at 91% full, with 66
 * short ones instead of 365. The short ones left are honest: a verse too long
 * to join the leaf it met.
 *
 * What that gives up is the borrowed boundary — page 255 in English no longer
 * opens where page 255 opens — and by the time it went it was buying nothing.
 * The folio counts leaves, the dial counts leaves, and a reader in the English
 * book navigates it by its own pages. What every leaf still knows is which
 * Madinah page its first verse falls on, and that is what the running head, the
 * juzʾ and the reciter's own place on the paper are drawn from, so a reader who
 * changes language still lands on the words they were on. That is
 * [EnglishBook.leafOfVerse], and it is exact for all 6,236 verses.
 *
 * Al-Fatihah is the one break the packing keeps: it opens the book on a leaf of
 * its own, as it stands on a page of its own in every mushaf.
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
 * It is not free, and the cost is leaves: about 1,120 of them, against 675 at
 * 1,650 and 604 when a leaf was a page. That is the trade, taken deliberately.
 * What it is *not* paying for any more is whitespace — the leaves are packed
 * continuously, so the median one reaches 91% of its well whatever the capacity
 * is, and the capacity buys only type.
 *
 * Below about 850 the line is shorter than the hand wants and above about 1,000
 * it is longer. `tools/measure_english_leaves.py` prints the sweep.
 */
const val ENGLISH_LEAF_CAPACITY_CHARS = 900

/**
 * What a chapter's opening costs the leaf, in the characters the capacity
 * counts.
 *
 * The capacity is a mass of *prose*, and a chapter opening sets no prose at
 * all — it sets a panel, and the air on either side of it, and it ends the
 * paragraph above it half a line early. Paper, not words. Left uncounted it is
 * paper the pagination believes is free, and the last leaf of the Qur'an,
 * which opens four chapters, came out with fifteen of its twenty-two lines
 * already spent before a word of translation was set on it. The leading closed
 * to pay for it and the lines ran into one another.
 *
 * So an opening is charged what it takes. About one line for the panel, a
 * third of one for the air on each side, and half a line for the ragged end of
 * the paragraph above: call it two lines, and a line of this book is about 46
 * characters. The basmalah preface is charged its own line and its air.
 */
const val ENGLISH_LEAF_OPENING_CHARS = 92
const val ENGLISH_LEAF_BASMALAH_CHARS = 78

/** What a verse costs the leaf: its prose, and the opening it may bring. */
fun englishLeafVerseMass(surahId: Int, ayah: Int, prose: Int): Int = when {
    ayah != 1 -> prose
    surahOpensWithBasmalahPreface(surahId) ->
        prose + ENGLISH_LEAF_OPENING_CHARS + ENGLISH_LEAF_BASMALAH_CHARS
    else -> prose + ENGLISH_LEAF_OPENING_CHARS
}

/** One leaf of the English book: a run of the translation, set as a page. */
data class EnglishBookLeaf(
    /**
     * The Madinah page this leaf *opens* on — where its first verse begins.
     * Not a boundary any more, but still where the leaf is in the mushaf, and
     * so what the running head and the juzʾ are read from.
     */
    val page: Int,
    /**
     * Every Madinah page the leaf draws a verse from. Two or three, because a
     * leaf is smaller than a page — the pager needs it to find the Arabic word
     * a tap on an English sentence should play from.
     */
    val pages: IntRange,
    /** The verses set on this leaf, in the book's own order. */
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
 * Paginates the whole book once, continuously.
 *
 * [prose] answers how much paper one verse takes, in the same characters
 * [ENGLISH_LEAF_CAPACITY_CHARS] counts. It is a *character* estimate rather
 * than a layout, deliberately: it is device-independent, so the book breaks in
 * the same places on every screen the way a printed book does, and it costs a
 * few hundred microseconds instead of a thousand text layouts. Where the
 * estimate is off, `MushafEnglishSheet` measures the leaf as it will actually
 * be drawn and closes its leading by the difference — the same guarantee that
 * has always caught it.
 *
 * The verses are walked in the mushaf's own order — page by page, and within a
 * page in the order the calligrapher set them — and a leaf is closed when the
 * next verse will not go on it. Nothing is evened out and nothing is carried
 * back, because with the page boundary gone there is no remainder to even: the
 * only leaf that ends short is one that met a verse too long to take.
 */
fun buildEnglishBook(
    catalog: MushafCatalog,
    prose: (surahId: Int, ayah: Int) -> Int,
): EnglishBook {
    val leaves = ArrayList<EnglishBookLeaf>(1_300)
    val firstLeafByPage = IntArray(MushafCatalog.MUSHAF_PAGE_COUNT + 1) { -1 }
    val leafByVerse = HashMap<Long, Int>(8_192)

    val run = ArrayList<Pair<Int, Int>>(16)
    val runPages = ArrayList<Int>(16)
    var mass = 0

    fun close() {
        if (run.isEmpty()) return
        val index = leaves.size
        run.forEach { (s, a) -> leafByVerse[quranWordKey(s, a, 1)] = index }
        // A page's leaf is the first one that carries any of its verses.
        runPages.forEach { page ->
            if (firstLeafByPage[page] < 0) firstLeafByPage[page] = index
        }
        leaves += EnglishBookLeaf(
            page = runPages.first(),
            pages = runPages.first()..runPages.last(),
            verses = ArrayList(run),
        )
        run.clear()
        runPages.clear()
        mass = 0
    }

    for (page in 1..MushafCatalog.MUSHAF_PAGE_COUNT) {
        val keys = catalog.page(page)?.let(::englishLeafVerseKeys).orEmpty()
        for (key in keys) {
            val verseMass =
                englishLeafVerseMass(key.first, key.second, prose(key.first, key.second))
            val full = mass + verseMass > ENGLISH_LEAF_CAPACITY_CHARS
            if (run.isNotEmpty() && (full || englishLeafOpensHere(key))) close()
            run += key
            runPages += page
            mass += verseMass
        }
    }
    close()

    // A page with no verse of its own reads as the leaf its neighbour opened.
    var carried = 0
    for (page in 1..MushafCatalog.MUSHAF_PAGE_COUNT) {
        if (firstLeafByPage[page] < 0) {
            firstLeafByPage[page] = carried
        } else {
            carried = firstLeafByPage[page]
        }
    }
    return EnglishBook(leaves, firstLeafByPage, leafByVerse)
}

/**
 * The one place the packing is told to break: Al-Baqarah opens a leaf, so
 * Al-Fatihah has one to itself.
 *
 * Every other chapter runs on, its panel set inside the page where it falls —
 * which is how a printed Qur'an translation sets them, and starting each of the
 * 114 on a fresh leaf would leave 39 of them under a third full. Al-Fatihah is
 * not one of the 114 in this respect: it is the opening of the book, it stands
 * on a page of its own in every mushaf, and `mushafIsOpeningLeaf` already sets
 * that page differently from all the rest.
 */
private fun englishLeafOpensHere(verse: Pair<Int, Int>): Boolean =
    verse.first == 2 && verse.second == 1
