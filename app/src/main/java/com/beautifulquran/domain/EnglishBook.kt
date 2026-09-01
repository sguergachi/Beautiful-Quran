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
 * all — it sets a panel, and the air on either side of it, and a basmalah.
 * Paper, not words. Left uncounted it is paper the pagination believes is free,
 * and the last leaf of the Qur'an, which opened four chapters before chapters
 * took a leaf of their own, came out with fifteen of its lines already spent
 * before a word of translation was set on it.
 *
 * These are *measured*, off a screen capture of an opening leaf, and not
 * reasoned about. `MushafEnglishSheet` builds the panel's slot from the line's
 * ink and [EnglishLeafPanelAir] on each side and the basmalah's from its own
 * line and [EnglishLeafBasmalahAirEm] under it, and on the reference leaf those
 * come to 126 px and 113 px against a line pitch of 80 — 1.58 lines and 1.41,
 * three lines together. A line of this book is [ENGLISH_LEAF_LINE_CHARS], so
 * they are 64 characters and 58.
 *
 * They were 92 and 78 — four and a sixth lines for three — and a chapter's leaf
 * came up an eighth of its well short every time. Half of the excess was a
 * charge for "the ragged end of the paragraph above", which was real when a
 * panel could land halfway down a leaf and became a charge for nothing the day
 * chapters started opening leaves of their own ([englishLeafOpensHere]).
 *
 * Re-measure with a device capture after changing the panel, the basmalah or
 * the leading.
 */
const val ENGLISH_LEAF_OPENING_CHARS = 64
const val ENGLISH_LEAF_BASMALAH_CHARS = 58

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
 * How the leaf breaks: it doesn't, except where a book breaks.
 *
 * The rules a trade compositor works to are short, and this book now works to
 * them:
 *
 *  1. **The page fills.** The type page is a fixed rectangle and text fills it;
 *     every page carries the same number of lines. Everything else is an
 *     exception to this one.
 *  2. **A chapter opens a new page** ([englishLeafOpensHere]). A printed book
 *     will burn a whole leaf rather than start a chapter halfway down one.
 *  3. **The last page of a chapter is the only short page.** That is where the
 *     ragged foot is allowed to live, and because it always coincides with a
 *     chapter ending it reads as intended rather than as a gap.
 *  4. **Widows and orphans move the break** — in a book. Not in this one, and
 *     [ENGLISH_LEAF_MIN_FRAGMENT_CHARS] says why. So here, nothing moves it.
 *
 * What is *not* on that list is the sentence. This book used to refuse to cut a
 * verse anywhere but at a full stop, on the reasoning that a reader should not
 * carry half a thought over the fold. It is a real argument for a page that is
 * being recited aloud, but it is not what a book does: a paragraph runs
 * straight over the break, mid-clause, and the break is meant to be invisible.
 * The reader's eye simply carries on. Holding out for a full stop left the foot
 * of an ordinary leaf blank by 1.47 lines on average and 3.94 at the
 * ninety-fifth percentile, which is paper spent on a fault the reader was never
 * going to notice.
 *
 * So the break falls where the line falls. `englishLeafBreak` still moves it off
 * the middle of a word, because this book does not hyphenate — that is a word
 * break, not a sentence break, and it costs at most a word of the leaf.
 */

/**
 * The least of a carried verse worth cutting: one word.
 *
 * This was a line, standing in for the widow and orphan rule, and that rule
 * does not apply to this book. A *widow* is a paragraph's last line alone at
 * the head of a page with white beside it — and a carried verse is never
 * alone. The rest of it is followed on the same line by the next verse, and
 * the next, for twenty-three lines. There is no white beside it to look wrong,
 * so there is nothing to protect.
 *
 * It was not cheap to keep. Measured over the book it refused a good cut on
 * 239 leaves and held 5,253 characters off the paper — twenty-two leaves'
 * worth — and left an ordinary leaf 0.27 of a line short where without it the
 * figure is 0.01.
 *
 * A word is what is left, and it is arithmetic rather than typography: a cut of
 * nothing sets an empty run and never advances. `englishLeafBreak` snaps the
 * offset off the middle of a word as the leaf sets it, so a cut this small
 * lands on whichever word straddles the break.
 */
const val ENGLISH_LEAF_MIN_FRAGMENT_CHARS = 6

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
fun buildEnglishBook(
    catalog: MushafCatalog,
    /** Characters the verse takes, its mark included — see [EnglishLeaf.prose]. */
    prose: (surahId: Int, ayah: Int) -> Int,
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
                // The leaf is always fresh here — a chapter opened it — so the
                // panel never has to be tested against the room left.
                mass += englishLeafOpeningChars(surahId)
                if (runPages.isEmpty()) runPages += page
            }
            val length = (prose(surahId, ayah) - ENGLISH_LEAF_MARK_CHARS).coerceAtLeast(0)
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
                // Fill the leaf. The break falls where the line falls.
                //
                // Nothing moves it. The leaf takes every character of room it
                // has and the verse picks up where it left off, because the
                // carried half is never stranded: the rest of the verse, and
                // the next verse, follow it on the same line. Only a leaf with
                // less than a word of room does not cut at all — that verse
                // goes whole to the next leaf, the way a paragraph too big for
                // the foot of a page does.
                var cut = left
                val carry = cut >= ENGLISH_LEAF_MIN_FRAGMENT_CHARS
                if (!carry && run.isNotEmpty()) {
                    // Not a word of room left: the verse opens the next leaf.
                    close()
                    continue
                }
                // Either the cut is worth making, or there is nothing to move
                // the verse to — an empty leaf already holding a chapter's
                // panel, or a verse longer than any leaf holds, which in the
                // whole Qur'an is 2:282 alone.
                if (!carry) cut = maxOf(left, ENGLISH_LEAF_MIN_FRAGMENT_CHARS)
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

private fun englishLeafOpeningChars(surahId: Int): Int =
    if (surahOpensWithBasmalahPreface(surahId)) {
        ENGLISH_LEAF_OPENING_CHARS + ENGLISH_LEAF_BASMALAH_CHARS
    } else {
        ENGLISH_LEAF_OPENING_CHARS
    }

/**
 * Does this verse open a leaf of its own?
 *
 * Every chapter does. A printed book starts a chapter on a new page — on a
 * *recto* at that, burning the blank verso — and this is the one page break a
 * reader is meant to notice. It was Al-Baqarah alone before, so a chapter's
 * panel could land halfway down a leaf with the chapter above it still warm,
 * which is the one thing no book does.
 *
 * The cost is the short leaf at the end of every chapter, and that cost is the
 * point: it is where all the ragged paper in the book now lives, and it reads
 * as an ending rather than as a gap.
 */
private fun englishLeafOpensHere(verse: Pair<Int, Int>): Boolean = verse.second == 1
