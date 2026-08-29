package com.beautifulquran.domain

/*
 * The English leaf: the same Madinah page, set as a page of a book.
 *
 * **The page.** The English translation has no pagination of its own — no
 * printing of it breaks where every other printing breaks, the way the Madinah
 * mushaf does. It borrowed one for a while: a leaf carried the verses that
 * *began* on the Arabic leaf of the same number. It no longer does, because the
 * borrowed boundary turned out to cost a third of a blank page every other turn
 * and to buy nothing a reader could see — `EnglishBook.kt` has the measurement.
 * The English book paginates itself, continuously.
 *
 * What the leaf still carries is the verses that *begin* somewhere, in the
 * mushaf's own order, whole. A verse is a sentence and a sentence cannot be cut
 * at the word the calligrapher happened to reach at the foot of a page, so a
 * verse that straddles an Arabic page break is set whole where it starts —
 * which is how a parallel-text Qur'an is printed, and it means the English runs
 * continuously with nothing repeated and nothing dropped.
 *
 * The two layouts are still one book, but through the *verse* rather than
 * through the page: `EnglishBook.leafOfVerse` is exact for all 6,236 of them,
 * so the running head, the juz', the page dial, the reciter's own place on the
 * paper and a reader who changes language all land on the words they were on.
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
     * The paper the leaf takes, in the characters [ENGLISH_LEAF_CAPACITY_CHARS]
     * counts: each verse, its mark, the spaces that join them, and what a
     * chapter's panel and basmalah cost even though they set no prose.
     */
    val prose: Int
        get() = verses.sumOf { verse ->
            englishLeafVerseMass(
                surahId = verse.surahId,
                ayah = verse.ayah,
                prose = verse.text.length + ENGLISH_LEAF_MARK_CHARS,
            )
        }
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
 * Sets one leaf of the English book.
 *
 * [verses] is the run the leaf carries, in the book's order — `EnglishBook` has
 * already decided where the leaf begins and ends, and a run may cross a Madinah
 * page break, so nothing here reads the Arabic page's lines. What a leaf is, at
 * this point, is a list of verses.
 *
 * A chapter opening is simply a verse numbered 1: its panel is set immediately
 * before it, which is where a printed translation sets one, and which cannot
 * put the panel inside the paragraph above it because the panel *is* a block.
 * [translation] answers for one verse; a verse with no text is dropped rather
 * than set as a hole.
 */
fun englishLeaf(
    page: Int,
    verses: List<Pair<Int, Int>>,
    hideParentheticals: Boolean = false,
    translation: (surahId: Int, ayah: Int) -> String,
): EnglishLeaf {
    val blocks = ArrayList<EnglishLeafBlock>(4)
    var prose = ArrayList<EnglishLeafVerse>(verses.size)
    fun closeProse() {
        if (prose.isNotEmpty()) {
            blocks += EnglishLeafBlock.Prose(prose)
            prose = ArrayList(verses.size)
        }
    }
    verses.forEach { (surahId, ayah) ->
        if (ayah == 1) {
            closeProse()
            blocks += EnglishLeafBlock.ChapterOpening(
                surahId = surahId,
                basmalah = surahOpensWithBasmalahPreface(surahId),
            )
        }
        val text = englishVerseProse(translation(surahId, ayah), hideParentheticals)
        if (text.isNotEmpty()) {
            prose += EnglishLeafVerse(surahId = surahId, ayah = ayah, text = text)
        }
    }
    closeProse()
    return EnglishLeaf(page = page, blocks = blocks)
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
