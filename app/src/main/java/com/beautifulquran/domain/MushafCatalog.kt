package com.beautifulquran.domain

import com.beautifulquran.data.model.Word

/** One Hafs word on a Madinah mushaf line. */
data class MushafToken(
    val surahId: Int,
    val ayah: Int,
    val word: Word,
    /** True when this token is the last word of its ayah (draw ﴿N﴾ after it). */
    val endsAyah: Boolean,
)

data class MushafLine(
    val number: Int,
    val tokens: List<MushafToken>,
)

/** A chapter opening that belongs on this page, drawn before [beforeLineIndex]. */
data class MushafSurahStart(
    val surahId: Int,
    val beforeLineIndex: Int,
)

data class MushafPage(
    val page: Int,
    val lines: List<MushafLine>,
    val surahStarts: List<MushafSurahStart>,
) {
    val primarySurahId: Int
        get() = lines.firstOrNull()?.tokens?.firstOrNull()?.surahId ?: 0

    /** Juzʾ this page opens in — the running head's second figure. */
    val juz: Int
        get() = lines.firstOrNull()?.tokens?.firstOrNull()
            ?.let { juzOf(it.surahId, it.ayah) }
            ?: 1

    val ayahKeys: Set<Pair<Int, Int>> by lazy {
        buildSet {
            lines.forEach { line ->
                line.tokens.forEach { add(it.surahId to it.ayah) }
            }
        }
    }
}

/**
 * Keeps a leaf's words and page boundary, but balances them over the larger
 * display grid. Chapter openings remain hard boundaries so their title and
 * basmalah never drift into the preceding chapter's tail.
 */
fun reflowMushafPage(
    page: MushafPage,
    tokenWeight: (MushafToken) -> Float,
): MushafPage {
    if (mushafIsOpeningLeaf(page.page) || page.lines.isEmpty()) return page
    val boundaries = (listOf(0) + page.surahStarts.map { it.beforeLineIndex } + page.lines.size)
        .distinct().sorted()
    val sections = boundaries.zipWithNext().filter { (start, end) -> start < end }
    val tokens = sections.map { (start, end) -> page.lines.subList(start, end).flatMap { it.tokens } }
    val counts = IntArray(sections.size) { sections[it].second - sections[it].first }
    val weights = tokens.map { row -> row.sumOf { tokenWeight(it).coerceAtLeast(0.001f).toDouble() } }
    val extraRows = MUSHAF_DISPLAY_LINES_PER_PAGE - MUSHAF_LINES_PER_PAGE
    repeat(extraRows) {
        val expanded = sections.indices.filter { counts[it] < tokens[it].size }
            .maxByOrNull { weights[it] / counts[it] } ?: return@repeat
        counts[expanded]++
    }
    val rows = ArrayList<MushafLine>(page.lines.size + extraRows)
    val starts = ArrayList<MushafSurahStart>(page.surahStarts.size)
    sections.forEachIndexed { index, (start, _) ->
        page.surahStarts.filter { it.beforeLineIndex == start }.forEach {
            starts += it.copy(beforeLineIndex = rows.size)
        }
        val count = counts[index].coerceAtMost(tokens[index].size)
        balancedMushafRows(tokens[index], count, tokenWeight).forEach { row ->
            rows += MushafLine(number = rows.size + 1, tokens = row)
        }
    }
    return page.copy(lines = rows, surahStarts = starts)
}

private fun balancedMushafRows(
    tokens: List<MushafToken>,
    count: Int,
    tokenWeight: (MushafToken) -> Float,
): List<List<MushafToken>> {
    val weights = tokens.map { tokenWeight(it).coerceAtLeast(0.001f) }
    val rows = ArrayList<List<MushafToken>>(count)
    var start = 0
    repeat(count) { row ->
        if (row == count - 1) {
            rows += tokens.subList(start, tokens.size)
        } else {
            val remaining = count - row
            val target = weights.subList(start, weights.size).sum() / remaining
            val maxEnd = tokens.size - remaining + 1
            var end = start + 1
            var width = weights[start]
            while (end < maxEnd) {
                val next = width + weights[end]
                if (kotlin.math.abs(target - next) >= kotlin.math.abs(target - width)) break
                width = next
                end++
            }
            rows += tokens.subList(start, end)
            start = end
        }
    }
    return rows
}

data class MushafSourceWord(
    val surahId: Int,
    val ayah: Int,
    val word: Word,
)

/**
 * Madinah 604-page index built from `words.qcf_page` / `qcf_line`.
 * Page 0 (unmatched source words) is dropped. The pager paints each
 * line's `qcf_v2` glyphs in that page's QCF V2 face.
 */
class MushafCatalog internal constructor(
    private val pagesByNumber: Map<Int, MushafPage>,
    private val firstPageBySurah: IntArray,
    private val pageByWord: Map<Long, Int>,
) {
    val pageCount: Int = MUSHAF_PAGE_COUNT

    /** True until the runtime snapshot it is built from has loaded. */
    fun isEmpty(): Boolean = pagesByNumber.isEmpty()

    fun page(number: Int): MushafPage? = pagesByNumber[number]

    fun firstPageOf(surahId: Int): Int {
        if (surahId !in 1..114) return 1
        return firstPageBySurah[surahId].takeIf { it > 0 } ?: 1
    }

    /**
     * The leaf a reader following the voice belongs on — which is not always
     * the leaf the word is printed on.
     *
     * The English leaf sets a verse whole on the page it *begins* on, because
     * a sentence cannot be cut at a page break (see `EnglishLeaf.kt`). So while
     * the voice is inside a verse that straddles a boundary, the page the
     * reader is looking at is still the verse's opening leaf, and the paper
     * must not turn out from under a sentence halfway through it. Pass
     * [wholeVerses] for a layout set that way.
     */
    fun readingPageOf(
        surahId: Int,
        ayah: Int,
        position: Int,
        wholeVerses: Boolean,
    ): Int = pageOf(surahId, ayah, if (wholeVerses) 1 else position)

    /** How many words the mushaf sets for a verse; 0 when it has none. */
    fun wordCountOf(surahId: Int, ayah: Int): Int {
        var count = 0
        while (pageByWord.containsKey(quranWordKey(surahId, ayah, count + 1))) count++
        return count
    }

    fun pageOf(surahId: Int, ayah: Int, position: Int = 1): Int {
        pageByWord[quranWordKey(surahId, ayah, position)]?.let { return it }
        pageByWord[quranWordKey(surahId, ayah, 1)]?.let { return it }
        return firstPageOf(surahId)
    }

    companion object {
        const val MUSHAF_PAGE_COUNT = 604
    }
}

fun buildMushafCatalog(words: List<MushafSourceWord>): MushafCatalog {
    val grouped = LinkedHashMap<Int, LinkedHashMap<Int, MutableList<MushafSourceWord>>>()
    val pageByWord = HashMap<Long, Int>(words.size)
    val firstPageBySurah = IntArray(115)
    words.forEach { source ->
        val page = source.word.qcfPage
        if (page !in 1..MushafCatalog.MUSHAF_PAGE_COUNT) return@forEach
        val line = source.word.qcfLine.coerceAtLeast(1)
        grouped.getOrPut(page) { LinkedHashMap() }
            .getOrPut(line) { mutableListOf() }
            .add(source)
        pageByWord[quranWordKey(source.surahId, source.ayah, source.word.position)] = page
        val first = firstPageBySurah[source.surahId]
        if (first == 0 || page < first) firstPageBySurah[source.surahId] = page
    }

    val lastPosition = HashMap<Long, Int>()
    words.forEach { source ->
        if (source.word.qcfPage !in 1..MushafCatalog.MUSHAF_PAGE_COUNT) return@forEach
        val key = ayahKey(source.surahId, source.ayah)
        val pos = source.word.position
        lastPosition[key] = maxOf(lastPosition[key] ?: 0, pos)
    }

    val pages = HashMap<Int, MushafPage>(MushafCatalog.MUSHAF_PAGE_COUNT)
    grouped.forEach { (pageNumber, linesByNumber) ->
        val lines = linesByNumber.entries
            .sortedBy { it.key }
            .map { (lineNumber, sources) ->
                MushafLine(
                    number = lineNumber,
                    tokens = sources.map { source ->
                        val last = lastPosition[ayahKey(source.surahId, source.ayah)] ?: source.word.position
                        MushafToken(
                            surahId = source.surahId,
                            ayah = source.ayah,
                            word = source.word,
                            endsAyah = source.word.position == last,
                        )
                    },
                )
            }
        val surahStarts = ArrayList<MushafSurahStart>()
        lines.forEachIndexed { index, line ->
            line.tokens.firstOrNull()
                ?.takeIf { it.ayah == 1 && it.word.position == 1 }
                ?.let { surahStarts += MushafSurahStart(it.surahId, index) }
        }
        pages[pageNumber] = MushafPage(pageNumber, lines, surahStarts)
    }

    return MushafCatalog(pages, firstPageBySurah, pageByWord)
}

private fun ayahKey(surahId: Int, ayah: Int): Long =
    (surahId.toLong() shl 32) or ayah.toLong()

/**
 * One word of the Qur'an as a single key. Used by the page index here and by
 * anything else that has to answer per word without carrying a nested map —
 * the English leaf's gloss lookup, for one.
 */
fun quranWordKey(surahId: Int, ayah: Int, position: Int): Long =
    (surahId.toLong() shl 40) or (ayah.toLong() shl 16) or position.toLong()
