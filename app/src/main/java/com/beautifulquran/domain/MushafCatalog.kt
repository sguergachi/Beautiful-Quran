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

    fun page(number: Int): MushafPage? = pagesByNumber[number]

    fun firstPageOf(surahId: Int): Int {
        if (surahId !in 1..114) return 1
        return firstPageBySurah[surahId].takeIf { it > 0 } ?: 1
    }

    fun pageOf(surahId: Int, ayah: Int, position: Int = 1): Int {
        pageByWord[wordKey(surahId, ayah, position)]?.let { return it }
        pageByWord[wordKey(surahId, ayah, 1)]?.let { return it }
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
        pageByWord[wordKey(source.surahId, source.ayah, source.word.position)] = page
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

private fun wordKey(surahId: Int, ayah: Int, position: Int): Long =
    (surahId.toLong() shl 40) or (ayah.toLong() shl 16) or position.toLong()
