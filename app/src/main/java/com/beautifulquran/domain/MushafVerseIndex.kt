package com.beautifulquran.domain

/**
 * The book as one run of verses, in the order the mushaf sets them.
 *
 * The scrub dial under a leaf used to count leaves, because a leaf is what the
 * pager turns. But a leaf is too coarse a thing to pick with: the dial's own
 * label has always named a chapter and a verse, and no amount of slowing the
 * hand down could get below a whole page. So the dial counts verses now — a
 * little over six thousand of them — and only converts to a leaf at the last
 * moment, when the finger lifts and the pager is told where to go.
 *
 * Ordinals are 1-based and dense: verse 1 is al-Fātiḥa 1, verse [count] is the
 * last verse of an-Nās. A verse that spills across a page break is counted once,
 * on the leaf where it starts.
 */
class MushafVerseIndex internal constructor(
    private val surahOf: IntArray,
    private val ayahOf: IntArray,
    private val pageOf: IntArray,
    private val firstVerseOfPage: IntArray,
    /** The ordinals that open a chapter — the dial's landmarks. */
    val chapterStarts: Set<Int>,
) {
    /** How many verses the mushaf sets. */
    val count: Int = surahOf.size - 1

    /** The chapter and ayah at [verse], or null when [verse] is off the book. */
    fun keyOf(verse: Int): Pair<Int, Int>? {
        if (verse !in 1..count) return null
        return surahOf[verse] to ayahOf[verse]
    }

    /** The leaf [verse] is set on. */
    fun pageOf(verse: Int): Int = pageOf[verse.coerceIn(1, count)]

    /**
     * The first verse the reader meets on [page].
     *
     * This is the first verse to *start* on the leaf, not the one the top line
     * happens to be finishing — the thumb should sit where the reading begins.
     * A leaf entirely inside one long verse has none of its own, so it borrows
     * the verse it is continuing.
     */
    fun firstVerseOfPage(page: Int): Int =
        firstVerseOfPage[page.coerceIn(1, firstVerseOfPage.size - 1)]
}

/** Walks the whole catalog once and numbers every verse in mushaf order. */
fun buildMushafVerseIndex(catalog: MushafCatalog): MushafVerseIndex {
    val surahOf = ArrayList<Int>(6300).apply { add(0) }
    val ayahOf = ArrayList<Int>(6300).apply { add(0) }
    val pageOf = ArrayList<Int>(6300).apply { add(0) }
    val firstVerseOfPage = IntArray(catalog.pageCount + 1)
    val chapterStarts = LinkedHashSet<Int>()

    var lastSurah = 0
    var lastAyah = 0
    for (page in 1..catalog.pageCount) {
        val leaf = catalog.page(page) ?: continue
        // A leaf with no verse of its own — the middle of a long verse — keeps
        // the running one, so the dial never points at nothing.
        firstVerseOfPage[page] = surahOf.size - 1
        var opened = false
        for (line in leaf.lines) {
            for (token in line.tokens) {
                if (token.surahId == lastSurah && token.ayah == lastAyah) continue
                lastSurah = token.surahId
                lastAyah = token.ayah
                surahOf.add(token.surahId)
                ayahOf.add(token.ayah)
                pageOf.add(page)
                val ordinal = surahOf.size - 1
                if (token.ayah == 1) chapterStarts.add(ordinal)
                if (!opened) {
                    firstVerseOfPage[page] = ordinal
                    opened = true
                }
            }
        }
    }
    if (firstVerseOfPage.size > 1 && firstVerseOfPage[1] == 0) firstVerseOfPage[1] = 1
    for (page in 2 until firstVerseOfPage.size) {
        if (firstVerseOfPage[page] == 0) firstVerseOfPage[page] = firstVerseOfPage[page - 1]
    }

    return MushafVerseIndex(
        surahOf = surahOf.toIntArray(),
        ayahOf = ayahOf.toIntArray(),
        pageOf = pageOf.toIntArray(),
        firstVerseOfPage = firstVerseOfPage,
        chapterStarts = chapterStarts,
    )
}
