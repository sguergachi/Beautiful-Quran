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
 * Keeps a leaf's words and page boundary, but sets them over the larger
 * display grid. Chapter openings remain hard boundaries so their title and
 * basmalah never drift into the preceding chapter's tail.
 *
 * Each section — the run of a page's words between chapter openings — is
 * given the print's rows and the grid's two spare ones, but only as many as
 * its words fill. A full page needs both spare rows and gets them (the page
 * faces' advances carry the print's justification, so a print line holds
 * about 1.2 display rows of words). A page of short chapters does not: handed
 * the spare rows anyway, page 604's al-Falaq and an-Nās spread three rows of
 * words over four and five, and full justification could only open each row
 * into gaps an em and more wide. A row no section can fill is left off, and
 * the leaf's foot stays paper.
 *
 * A section that ends its chapter is set as the print sets one: full rows,
 * the last standing short ([mushafLineMayStandShort]). Spread evenly, every row
 * of it came out short and every one was justified loose. A section that runs
 * on to the next page has no row that may stand short, so its rows are
 * balanced; it keeps the print's count and takes a spare row only while its
 * rows stay at least [MUSHAF_ROW_MIN_FILL] full.
 *
 * [rowCapacity] is what a naturally set display row holds, in [tokenWeight]'s
 * units. [endsChapter] says whether a token closes its chapter — needed for a
 * page's last section only; every other section is followed by an opening.
 */
fun reflowMushafPage(
    page: MushafPage,
    rowCapacity: Float,
    endsChapter: (MushafToken) -> Boolean,
    tokenWeight: (MushafToken) -> Float,
): MushafPage {
    if (mushafIsOpeningLeaf(page.page) || page.lines.isEmpty() || rowCapacity <= 0f) return page
    val boundaries = (listOf(0) + page.surahStarts.map { it.beforeLineIndex } + page.lines.size)
        .distinct().sorted()
    val sections = boundaries.zipWithNext().filter { (start, end) -> start < end }
    val tokens = sections.map { (start, end) -> page.lines.subList(start, end).flatMap { it.tokens } }
    val printRows = IntArray(sections.size) { sections[it].second - sections[it].first }
    val weights = tokens.map { row -> row.sumOf { tokenWeight(it).coerceAtLeast(0.001f).toDouble() }.toFloat() }
    val ends = BooleanArray(sections.size) { i ->
        i < sections.lastIndex || tokens[i].lastOrNull()?.let(endsChapter) == true
    }
    val wanted = IntArray(sections.size) { i ->
        mushafSectionRows(weights[i] / rowCapacity, printRows[i], tokens[i].size, ends[i])
    }
    // Never more rows than the print plus the grid's spare ones; fewer when
    // the words need fewer.
    val counts = IntArray(sections.size) { minOf(printRows[it], wanted[it]) }
    val spare = printRows.sum() + (MUSHAF_DISPLAY_LINES_PER_PAGE - MUSHAF_LINES_PER_PAGE) - counts.sum()
    // A spare row goes where the rows are still fullest *with* it. Judged by
    // the rows a section has now, a chapter's two-line opening outbid its
    // neighbour's eleven, and page 520's six short verses of al-Dhāriyāt were
    // split over three rows that a full justification could only open into
    // gaps half a word wide.
    repeat(spare) {
        val expanded = sections.indices.filter { counts[it] < wanted[it] }
            .maxByOrNull { weights[it] / (counts[it] + 1) } ?: return@repeat
        counts[expanded]++
    }
    val rows = ArrayList<MushafLine>(page.lines.size + spare)
    val starts = ArrayList<MushafSurahStart>(page.surahStarts.size)
    sections.forEachIndexed { index, (start, _) ->
        page.surahStarts.filter { it.beforeLineIndex == start }.forEach {
            starts += it.copy(beforeLineIndex = rows.size)
        }
        val count = counts[index].coerceIn(1, tokens[index].size)
        balancedMushafRows(
            tokens = tokens[index],
            count = count,
            tokenWeight = tokenWeight,
            // A chapter's rows are filled; only its last may stand short.
            fullRow = if (ends[index]) rowCapacity * MUSHAF_ROW_END_FILL else 0f,
            lastRowMin = rowCapacity * MUSHAF_ROW_SPILL,
        ).forEach { row ->
            rows += MushafLine(number = rows.size + 1, tokens = row)
        }
    }
    return page.copy(lines = rows, surahStarts = starts)
}

/**
 * How many display rows a section's words want: [rows] is their width in
 * naturally set rows. A chapter's ending is filled row by row, so it takes one
 * more only when more than [MUSHAF_ROW_SPILL] of a row would otherwise be
 * squeezed into the others — less than that and the rows give it up by
 * narrowing a few percent, where a row of its own would be one stranded word.
 * A section running on to the next page is justified throughout: it keeps
 * its [printRows] and takes more only while each stays [MUSHAF_ROW_MIN_FILL]
 * full.
 */
internal fun mushafSectionRows(rows: Float, printRows: Int, tokenCount: Int, endsChapter: Boolean): Int {
    val wanted = if (endsChapter) {
        kotlin.math.ceil(rows - MUSHAF_ROW_SPILL).toInt()
    } else {
        maxOf(printRows, kotlin.math.floor(rows / MUSHAF_ROW_MIN_FILL).toInt())
    }
    return wanted.coerceIn(1, tokenCount.coerceAtLeast(1))
}

/**
 * How much of a display row a page face's advances fill when its words are set
 * at their own width: the advances carry the print's justification, which the
 * display's own fit replaces. Measured over the book, rows that the reader's
 * full pages set at their natural spacing come to about this much.
 */
const val MUSHAF_ROW_SET_FILL = 0.85f

/**
 * How full a chapter's ending sets each row before its last, in naturally set
 * rows: about as full as an ordinary page's rows come out, so its white is
 * theirs. Filled only to the natural measure, al-Kawthar's first row on page
 * 602 opened to gaps a word wide.
 */
const val MUSHAF_ROW_END_FILL = 1.05f

/** How much of a row a chapter's ending may fold into its other rows, and
 * the least it leaves its last row. */
const val MUSHAF_ROW_SPILL = 0.3f

/** How full a justified row must stay for a section to take another. */
const val MUSHAF_ROW_MIN_FILL = 0.9f

private fun balancedMushafRows(
    tokens: List<MushafToken>,
    count: Int,
    tokenWeight: (MushafToken) -> Float,
    /** At least this much per row before the last, when the words allow. */
    fullRow: Float = 0f,
    /** What filling the rows must leave the last one. */
    lastRowMin: Float = 0f,
): List<List<MushafToken>> {
    val weights = tokens.map { tokenWeight(it).coerceAtLeast(0.001f) }
    val rows = ArrayList<List<MushafToken>>(count)
    var start = 0
    repeat(count) { row ->
        if (row == count - 1) {
            rows += tokens.subList(start, tokens.size)
        } else {
            val remaining = count - row
            val rest = weights.subList(start, weights.size).sum()
            val even = rest / remaining
            val target = if (fullRow > 0f) {
                maxOf(even, minOf(fullRow, (rest - lastRowMin) / (remaining - 1)))
            } else {
                even
            }
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
    /** Each verse's last word position — its word count — by [ayahKey]. */
    private val wordsByAyah: Map<Long, Int> = emptyMap(),
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
    fun wordCountOf(surahId: Int, ayah: Int): Int = wordsByAyah[ayahKey(surahId, ayah)] ?: 0

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

    return MushafCatalog(pages, firstPageBySurah, pageByWord, lastPosition)
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
