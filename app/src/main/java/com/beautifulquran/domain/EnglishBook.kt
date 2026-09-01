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
const val ENGLISH_LEAF_CAPACITY_CHARS = 940

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

/**
 * About what one line of the leaf holds, in the characters the capacity counts.
 *
 * The well comes to 22 lines on a phone, and the hand is solved so that a leaf
 * exactly fills it, so a line is a twenty-second of the capacity. It is an
 * approximation on a tablet, whose well is fewer and longer lines — near enough,
 * because the two rules below only need to know a line from a page.
 */
const val ENGLISH_LEAF_LINE_CHARS = ENGLISH_LEAF_CAPACITY_CHARS / 23

/**
 * How big a hole has to be before a verse is carried over rather than moved
 * whole to the next leaf.
 *
 * A leaf ends when the next verse will not go on it, and a verse averages three
 * lines, so the foot of the page is blank by up to that much: measured over the
 * book, 2.6 lines of 22 on average and 7.3 at the ninety-fifth percentile. A
 * printed book does not do this — it carries the paragraph over — and neither
 * does a printed translation of the Qur'an, which runs its verses on and lets
 * the page break fall where it falls.
 *
 * A verse used to be cut only where leaving it whole would waste three lines or
 * more, because the cut fell wherever the character budget ran out and breaking
 * a sentence across the fold is a real cost — not one to pay for a line or two.
 *
 * The cut is a sentence end now ([englishSentenceCut]), and that changes the
 * arithmetic: the reader loses nothing at the turn, so there is no cost to
 * weigh against the paper and no reason to leave the foot of a leaf empty. A
 * leaf fills as far as a whole sentence will fill it.
 *
 * Measured over the book: 1,075 leaves become 1,055, average blank 1.86 lines
 * becomes 1.47, and 195 carried verses become 324.
 */

/**
 * The least of a carried verse that may stand alone on either leaf.
 *
 * One line. It was two, from when a fragment could be any run of words a budget
 * happened to end on — half a sentence alone at a foot is a widow, and a
 * compositor moves the break rather than set one. A fragment is a whole
 * sentence now, and a whole sentence on a line of its own is not a widow; it is
 * a short paragraph. Holding out for two lines simply refused good cuts: it
 * cost twenty leaves and a third of a blank line on every one of them.
 *
 * Below one line it would be: five characters of "Say." alone at the head of a
 * leaf is nobody's idea of a page. The break moves back up the verse until both
 * halves clear this, and if none does the verse is not split at all.
 */
const val ENGLISH_LEAF_MIN_FRAGMENT_CHARS = ENGLISH_LEAF_LINE_CHARS

/**
 * One verse, or the part of one, that a leaf sets.
 *
 * [from] and [to] are character offsets into the verse's own text, and they are
 * *estimates* — the pagination counts characters, not glyphs. The leaf snaps
 * them to a word boundary as it sets them (`englishLeafBreak`), and because
 * both the leaf that ends at an offset and the leaf that begins there snap it
 * the same way, the two agree without either knowing about the other.
 */
data class EnglishVerseRun(
    val surahId: Int,
    val ayah: Int,
    val from: Int,
    val to: Int,
) {
    /** Whether this run carries the end of the verse, and so its mark. */
    fun endsVerse(length: Int): Boolean = to >= length

    val key: Pair<Int, Int> get() = surahId to ayah
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
    /** What this leaf sets, in the book's own order. */
    val runs: List<EnglishVerseRun>,
) {
    /** The verses the leaf touches, whole or in part, without repeats. */
    val verses: List<Pair<Int, Int>> get() = runs.map { it.key }.distinct()
}

/**
 * The English book as a sequence of leaves, and the two lookups the reader
 * needs: where a page starts, and which leaf a verse is on.
 */
class EnglishBook internal constructor(
    val leaves: List<EnglishBookLeaf>,
    private val firstLeafByPage: IntArray,
    private val leafByVerse: Map<Long, Int>,
    /**
     * For a verse the book carried over, where each of its later leaves picks
     * it up, as a fraction of the verse. Verses set whole have no entry, which
     * is all but 302 of the 6,236.
     */
    private val carriedByVerse: Map<Long, FloatArray>,
) {
    val leafCount: Int get() = leaves.size

    fun leaf(index: Int): EnglishBookLeaf? = leaves.getOrNull(index)

    /** The first leaf of a Madinah page — where the dial and a deep link land. */
    fun firstLeafOf(page: Int): Int =
        firstLeafByPage.getOrNull(page.coerceIn(1, MushafCatalog.MUSHAF_PAGE_COUNT)) ?: 0

    /**
     * The leaf a verse is set on, and — where the book carried it over — which
     * of its leaves the reciter is on.
     *
     * [through] is how far into the verse the voice has come, as a fraction. At
     * 0 this is the leaf the verse opens on, which is what a deep link, the dial
     * and the chapter comb want. Past a cut it is the leaf that picks the verse
     * up, which is what the ink and the page turn want: a leaf carrying only the
     * tail of a verse is still the leaf the reciter is reading from, and
     * answering "the leaf it began on" left that leaf recessed and silent for
     * as long as the first half took to recite.
     *
     * A verse the book does not carry falls back to the first leaf of the page
     * it began on.
     */
    fun leafOfVerse(surahId: Int, ayah: Int, page: Int, through: Float = 0f): Int {
        val key = quranWordKey(surahId, ayah, 1)
        val first = leafByVerse[key] ?: return firstLeafOf(page)
        val cuts = carriedByVerse[key] ?: return first
        var leaf = first
        for (cut in cuts) {
            if (through < cut) break
            leaf++
        }
        return leaf.coerceAtMost(leaves.lastIndex)
    }
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
/** What the pagination needs to know about one verse's English. */
class EnglishVerseMeasure(
    /** Characters the verse takes, its mark included — see [EnglishLeaf.prose]. */
    val length: Int,
    /** Where its sentences end ([englishSentenceEnds]), in the same characters. */
    val sentenceEnds: IntArray,
)

fun buildEnglishBook(
    catalog: MushafCatalog,
    prose: (surahId: Int, ayah: Int) -> EnglishVerseMeasure,
): EnglishBook {
    val leaves = ArrayList<EnglishBookLeaf>(1_200)
    val firstLeafByPage = IntArray(MushafCatalog.MUSHAF_PAGE_COUNT + 1) { -1 }
    val leafByVerse = HashMap<Long, Int>(8_192)
    // Where a carried verse is picked up again, as a fraction of itself.
    val carriedCuts = HashMap<Long, MutableList<Float>>()

    val run = ArrayList<EnglishVerseRun>(16)
    val runPages = ArrayList<Int>(16)
    var mass = 0

    fun close() {
        if (run.isEmpty()) return
        val index = leaves.size
        // A verse is found on the leaf it *begins* on, so a carried one keeps
        // pointing at where the reader would start reading it.
        run.forEach { r ->
            if (r.from == 0) leafByVerse.putIfAbsent(quranWordKey(r.surahId, r.ayah, 1), index)
        }
        runPages.forEach { page ->
            if (firstLeafByPage[page] < 0) firstLeafByPage[page] = index
        }
        leaves += EnglishBookLeaf(
            page = runPages.first(),
            pages = runPages.first()..runPages.last(),
            runs = ArrayList(run),
        )
        run.clear()
        runPages.clear()
        mass = 0
    }

    for (page in 1..MushafCatalog.MUSHAF_PAGE_COUNT) {
        val keys = catalog.page(page)?.let(::englishLeafVerseKeys).orEmpty()
        for ((surahId, ayah) in keys) {
            if (run.isNotEmpty() && englishLeafOpensHere(surahId to ayah)) close()
            // The panel and its basmalah take paper before a word is set.
            if (ayah == 1) {
                val opening = englishLeafOpeningChars(surahId)
                if (run.isNotEmpty() && mass + opening > ENGLISH_LEAF_CAPACITY_CHARS) close()
                mass += opening
                if (runPages.isEmpty()) runPages += page
            }
            val measure = prose(surahId, ayah)
            val length = (measure.length - ENGLISH_LEAF_MARK_CHARS).coerceAtLeast(0)
            var from = 0
            while (true) {
                val left = ENGLISH_LEAF_CAPACITY_CHARS - mass
                val rest = length - from
                if (rest + ENGLISH_LEAF_MARK_CHARS <= left) {
                    run += EnglishVerseRun(surahId, ayah, from, length)
                    runPages += page
                    mass += rest + ENGLISH_LEAF_MARK_CHARS
                    break
                }
                // Fill the leaf, and break on a sentence rather than on a
                // verse.
                //
                // A page break inside a sentence is the one thing a printed
                // book does not do to prose it can help: the reader carries
                // half a thought over the fold and has to reassemble it on the
                // other side. A break *between* sentences costs nothing — it is
                // what every page of every book does. So there is no threshold
                // to clear here any more: if a sentence ends anywhere in the
                // room left, the leaf takes it. What used to gate this was the
                // price of cutting mid-sentence, and that price is gone.
                //
                // A verse with no sentence end in reach is still not cut: it
                // goes whole on the next leaf, the way a paragraph too big for
                // the foot of a page does.
                val sentenceCut = englishSentenceCut(
                    sentenceEnds = measure.sentenceEnds,
                    from = from,
                    length = length,
                    room = left,
                )
                val carry = sentenceCut != null
                if (!carry && run.isNotEmpty()) {
                    // Not worth cutting: the verse opens the next leaf instead.
                    close()
                    continue
                }
                // Either the cut is worth making, or there is nothing to move
                // the verse to — an empty leaf already holding a chapter's
                // panel, or a verse longer than any leaf holds, which in the
                // whole Qur'an is 2:282 alone.
                val cut = if (carry) {
                    sentenceCut!! - from
                } else {
                    minOf(rest, maxOf(left, ENGLISH_LEAF_MIN_FRAGMENT_CHARS))
                }
                val to = if (from + cut >= length) length else from + cut
                run += EnglishVerseRun(surahId, ayah, from, to)
                runPages += page
                from = to
                if (from >= length) {
                    mass = ENGLISH_LEAF_CAPACITY_CHARS
                    break
                }
                if (length > 0) {
                    carriedCuts.getOrPut(quranWordKey(surahId, ayah, 1)) { ArrayList(2) }
                        .add(from.toFloat() / length)
                }
                close()
            }
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
    return EnglishBook(
        leaves = leaves,
        firstLeafByPage = firstLeafByPage,
        leafByVerse = leafByVerse,
        carriedByVerse = carriedCuts.mapValues { (_, cuts) -> cuts.toFloatArray() },
    )
}

/** What a chapter's opening costs the leaf, panel and basmalah together. */
/**
 * The sentence end to cut a carried verse at, or null when there is none to
 * cut at.
 *
 * The last one that fits the [room] left on the leaf, so the leaf is filled as
 * far as a whole sentence will fill it, and only where what is left over is
 * worth a fragment of its own. Null is not a failure: it is the answer that
 * this verse should be set whole on the next leaf.
 */
internal fun englishSentenceCut(
    sentenceEnds: IntArray,
    from: Int,
    length: Int,
    room: Int,
): Int? {
    var best: Int? = null
    for (end in sentenceEnds) {
        if (end <= from) continue
        if (end - from < ENGLISH_LEAF_MIN_FRAGMENT_CHARS) continue
        if (end - from > room) break
        if (length - end < ENGLISH_LEAF_MIN_FRAGMENT_CHARS) continue
        best = end
    }
    return best
}

private fun englishLeafOpeningChars(surahId: Int): Int =
    if (surahOpensWithBasmalahPreface(surahId)) {
        ENGLISH_LEAF_OPENING_CHARS + ENGLISH_LEAF_BASMALAH_CHARS
    } else {
        ENGLISH_LEAF_OPENING_CHARS
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
