package com.beautifulquran.domain

/*
 * The English leaf: the same Madinah page, set as a page of a book.
 *
 * **The page.** The English translation has no pagination of its own — no
 * printing of it breaks where every other printing breaks, the way the Madinah
 * mushaf does. So it borrows one: an English leaf carries the verses that
 * *begin* on the Arabic leaf of the same number. Page 255 in English opens
 * where page 255 opens.
 *
 * "Begin", not "appear", because a verse is a sentence and a sentence cannot be
 * cut at the word the calligrapher happened to reach at the foot of a page. A
 * verse that runs over a page break is therefore set whole on the leaf it
 * starts on — which is how a parallel-text Qur'an is printed, and it means the
 * English runs continuously with nothing repeated and nothing dropped. Measured
 * over the book, every one of the 604 leaves has at least one verse beginning
 * on it, so no leaf comes out empty.
 *
 * That borrowed boundary is what makes the two layouts one book: the folio, the
 * juz', the running head, the page dial, the chapter openings and the reciter's
 * own place on the paper all mean the same thing in either script, and a reader
 * who changes language does not lose their page.
 *
 * **The text.** The verse translation, set as running prose — not the
 * word-by-word gloss the scrolling reader lyricizes. The gloss is an
 * interlinear aid and reads as one ("Indeed this (is) your religion religion
 * one"); a page of it is a crib, not a book. The leaf is a book, so it is set
 * from the translation the book is translated into.
 *
 * **The ink.** The cost of that choice is that the reciter's word timings name
 * Arabic words, and this page has none. So the leaf does not claim a word-level
 * alignment it does not have. It washes the verse being recited across its own
 * sentence, at the fraction of that verse the reciter has actually reached
 * (`englishVerseReadProgress`) — a true statement about where the voice is —
 * with the same recess and the same retained ink the Arabic leaf uses. See
 * `ui/reader/MushafEnglishSheet.kt`.
 */

/** One verse of the leaf: its sentence, and the verse the ink belongs to. */
data class EnglishLeafVerse(
    val surahId: Int,
    val ayah: Int,
    val text: String,
)

/** A block of the leaf, in printing order. */
sealed class EnglishLeafBlock {
    /** A chapter opens here: its panel, and the basmalah where it takes one. */
    data class ChapterOpening(
        val surahId: Int,
        val basmalah: Boolean,
    ) : EnglishLeafBlock()

    /** Continuous prose — one paragraph, however many verses run through it. */
    data class Prose(val verses: List<EnglishLeafVerse>) : EnglishLeafBlock()
}

data class EnglishLeaf(
    val page: Int,
    val blocks: List<EnglishLeafBlock>,
) {
    /** Every verse the leaf carries, in reading order. */
    val verses: List<EnglishLeafVerse>
        get() = blocks.filterIsInstance<EnglishLeafBlock.Prose>().flatMap { it.verses }

    /**
     * Characters of set prose on the leaf — the mass its hand is fitted to.
     * Counts what the page actually lays out: each verse, its mark, and the
     * spaces that join them. See [ENGLISH_LEAF_REFERENCE_PROSE].
     */
    val prose: Int
        get() = verses.sumOf { it.text.length + ENGLISH_LEAF_MARK_CHARS }
}

/**
 * What a verse mark and its two spaces cost the measure, in characters.
 * Small, fixed, and counted so the fit is not systematically optimistic on a
 * leaf carrying forty short verses — juz' 30 has several.
 */
const val ENGLISH_LEAF_MARK_CHARS = 6

/**
 * Which verses begin on a Madinah page — the leaf's contents, in order.
 *
 * A verse begins here when the page carries its first word. Every other verse
 * on the page continues one that began on an earlier leaf and was set whole
 * there.
 */
fun englishLeafVerseKeys(page: MushafPage): List<Pair<Int, Int>> =
    page.lines
        .flatMap { it.tokens }
        .filter { it.word.position == 1 }
        .map { it.surahId to it.ayah }
        .distinct()

/**
 * Sets one Madinah page in English.
 *
 * Chapter openings stay hard boundaries, so a chapter's panel never falls
 * inside the paragraph above it. [translation] answers for one verse; a verse
 * with no text is dropped rather than set as a hole.
 */
fun englishLeaf(
    page: MushafPage,
    hideParentheticals: Boolean = false,
    translation: (surahId: Int, ayah: Int) -> String,
): EnglishLeaf {
    val openings = page.surahStarts.associateBy { it.beforeLineIndex }
    val boundaries = (listOf(0) + openings.keys + page.lines.size).distinct().sorted()
    val blocks = ArrayList<EnglishLeafBlock>(page.surahStarts.size * 2 + boundaries.size)
    boundaries.zipWithNext().forEach { (start, end) ->
        openings[start]?.let { opening ->
            blocks += EnglishLeafBlock.ChapterOpening(
                surahId = opening.surahId,
                basmalah = surahOpensWithBasmalahPreface(opening.surahId),
            )
        }
        if (start >= end) return@forEach
        val verses = page.lines.subList(start, end)
            .flatMap { it.tokens }
            .filter { it.word.position == 1 }
            .map { it.surahId to it.ayah }
            .distinct()
            .mapNotNull { (surahId, ayah) ->
                val text = englishVerseProse(translation(surahId, ayah), hideParentheticals)
                if (text.isEmpty()) {
                    null
                } else {
                    EnglishLeafVerse(surahId = surahId, ayah = ayah, text = text)
                }
            }
        if (verses.isNotEmpty()) blocks += EnglishLeafBlock.Prose(verses)
    }
    return EnglishLeaf(page = page.page, blocks = blocks)
}

/**
 * A verse as the leaf sets it: one run of prose, its bracketed asides removed
 * where the reader has asked for that, and its internal line breaks closed up
 * — the source keeps a few, and a book page does not break a sentence in the
 * middle for them.
 */
private fun englishVerseProse(text: String, hideParentheticals: Boolean): String {
    val shown = if (hideParentheticals) {
        hideParentheticalText(listOf(text)).single()
    } else {
        text
    }
    return shown.replace(WHITESPACE_RUN, " ").trim()
}

private val WHITESPACE_RUN = Regex("\\s+")
