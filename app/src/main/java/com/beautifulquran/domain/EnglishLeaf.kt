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
 * A verse is never cut at the *Arabic* page break — a sentence cannot end at
 * whatever word the calligrapher reached at the foot of his page — so verses
 * are set whole and in the mushaf's own order, and the English runs
 * continuously with nothing repeated and nothing dropped.
 *
 * At the English book's own page break it is different, because there the break
 * is the book's to place. A verse too long to go on the leaf it met is carried
 * over rather than left to open the next one, exactly as a printed book carries
 * a paragraph: the sentence continues at the head of the following leaf and is
 * numbered where it finishes. The cut is always the end of a sentence, and a
 * leaf fills, and the break falls where the line falls; see `buildEnglishBook`.
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
 * Arabic words, and this page prints none of them. The leaf recovers the link
 * rather than doing without it: each Arabic word carries its own gloss, and
 * `EnglishWordAlignment` aligns that gloss stream to the translation, so the
 * wash crosses the English the reciter is actually saying — say ٱلْكِتَٰبُ and
 * "the Book" inks. 84 % of Arabic words land on a lexical anchor; the rest are
 * spread between their neighbours, which is the plain proportion the leaf used
 * before and is what an unalignable verse still gets. The recess and the
 * retained ink are the Arabic leaf's. See `ui/reader/MushafEnglishSheet.kt`.
 */

/**
 * One verse of the leaf, or the part of one: its sentence, and the verse the
 * ink belongs to.
 *
 * A long verse may be carried across a leaf break (`EnglishBook.kt`), so what
 * is set here can be a fragment. [from] and [to] say which part of the verse it
 * is, in characters of [EnglishLeafVerse.text]'s source, and the ink uses them
 * to place the reciter inside the fragment rather than inside the verse.
 */
data class EnglishLeafVerse(
    val surahId: Int,
    val ayah: Int,
    val text: String,
    /** Where this fragment sits in the verse, and how long the verse is. */
    val from: Int = 0,
    val to: Int = text.length,
    val verseLength: Int = text.length,
) {
    /** The mark closes the verse, so only the fragment that ends it carries one. */
    val endsVerse: Boolean get() = to >= verseLength

    /**
     * The share of the *verse* that a point [at] characters into this fragment
     * stands at — the inverse of [fragmentProgress], and what a tap uses to
     * ask the reciter to start somewhere other than the verse's first word.
     */
    fun verseFractionAt(at: Int, length: Int): Float {
        if (verseLength <= 0) return 0f
        val within = if (length <= 0) 0f else (at.toFloat() / length).coerceIn(0f, 1f)
        return ((from + within * (to - from)) / verseLength).coerceIn(0f, 1f)
    }

    /**
     * Where the reciter is within *this* fragment, given where they are within
     * the verse. A whole verse maps straight through.
     */
    fun fragmentProgress(verseProgress: Float): Float {
        val span = (to - from).toFloat()
        if (span <= 0f) return verseProgress
        return ((verseProgress * verseLength - from) / span).coerceIn(0f, 1f)
    }
}

/**
 * Where a leaf really breaks a verse, given the offset the pagination guessed.
 *
 * The pagination counts characters and knows nothing about words, so it hands
 * out an offset that will usually fall inside one. This moves it to the end of
 * that word. Two rules, and both matter:
 *
 * - It only ever moves *back*, and it is a pure function of the text and the
 *   offset. So the leaf that ends at an offset and the leaf that begins there
 *   land on the same character without either knowing about the other.
 *
 *   Back, not forward, and that direction is the whole of a long argument. The
 *   leaf is measured before it is set ([EnglishLeafRuler]) and the offset that
 *   comes back is the end of a line the well has room for. A snap that moves
 *   *forward* hands the leaf words the ruler never measured — they wrap to a
 *   line the leaf does not have, and every remedy for that costs a whole line:
 *   the page then ends a line early with a stub on it, or an empty line under
 *   it. A snap that moves back can only ever hand the leaf less than it was
 *   measured for, so the line count it was given is the line count it keeps,
 *   and there is nothing to remedy.
 * - It never stops inside brackets. The reader may have asked for the
 *   translator's asides to come off, and those are stripped per fragment; a
 *   break inside `[O Muhammad]` would leave half a bracket on each leaf and
 *   strip neither.
 */

private const val SENTENCE_TERMINATORS = ".!?"

/** Quotes and brackets a terminator may hide behind before the space. */
private const val SENTENCE_CLOSERS = "\"')]\u2019\u201d"

fun englishLeafBreak(text: String, at: Int): Int {
    if (at <= 0) return 0
    if (at >= text.length) return text.length
    // How deep in brackets the offset itself stands.
    var depth = 0
    for (i in 0 until at) {
        when (text[i]) {
            '[', '(' -> depth++
            ']', ')' -> if (depth > 0) depth--
        }
    }
    // Walk back to the last space outside them. [depth] is kept as the depth
    // *before* the character being looked at, so stepping left past a bracket
    // undoes it.
    var i = at
    while (i > 0) {
        if (text[i] == ' ' && depth == 0) return i
        when (text[i - 1]) {
            '[', '(' -> if (depth > 0) depth--
            ']', ')' -> depth++
        }
        i--
    }
    return 0
}

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
            val mark = if (verse.endsVerse) ENGLISH_LEAF_MARK_CHARS else 0
            val opening = if (verse.ayah == 1 && verse.from == 0) {
                ENGLISH_LEAF_OPENING_CHARS +
                    if (surahOpensWithBasmalahPreface(verse.surahId)) {
                        ENGLISH_LEAF_BASMALAH_CHARS
                    } else {
                        0
                    }
            } else {
                0
            }
            verse.text.length + mark + opening
        }
}

/**
 * What a verse mark and its two spaces cost the measure, in characters.
 *
 * Counted so the fit is not systematically optimistic on a leaf carrying forty
 * short verses — juz' 30 has several, and As-Saffat's leaves carry nineteen.
 *
 * It is *measured*, off a photograph of a leaf. The mark's gold is the only
 * gold on the page, so it can be isolated by hue: it inks 76 px on every one of
 * the nineteen marks of that leaf, and with a space either side it takes 96 px.
 * The same photograph gives the advance — total ink across the twenty-four
 * lines, less the marks, over the 883 characters of translation on it — at
 * 20.84 px. So the mark and its spaces cost **4.61 characters**, and this is 5.
 *
 * It was 6, then 2.8, then 3, and 3 is what a leaf could not survive. The
 * capacity is a mass of specimen prose, which carries no marks at all, and the
 * hand is cut so that [ENGLISH_LEAF_CAPACITY_CHARS] × [ENGLISH_LEAF_REFERENCE_MARGIN]
 * of it fills the well. A leaf of the same charged mass fits only if what its
 * marks really take is covered by that margin:
 *
 * ```
 *     marks × (4.61 − charge)  ≤  capacity × (margin − 1)  =  9.4
 *     charge 3:  marks × 1.61  ≤  9.4   →  six marks, and no more
 * ```
 *
 * Six. Most leaves in the book carry more, and every one of them overflowed the
 * well and closed its leading to squeeze a further line in — a line holding one
 * word, on a leaf set tighter than the leaf before it, which is the one thing
 * the book's single leading exists to prevent. At 5 the term goes negative and
 * the question stops being asked.
 *
 * Re-measure from a device photograph after changing the mark or the face.
 */
const val ENGLISH_LEAF_MARK_CHARS = 5

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
    runs: List<EnglishVerseRun>,
    hideParentheticals: Boolean = false,
    translation: (surahId: Int, ayah: Int) -> String,
): EnglishLeaf {
    val blocks = ArrayList<EnglishLeafBlock>(4)
    var prose = ArrayList<EnglishLeafVerse>(runs.size)
    fun closeProse() {
        if (prose.isNotEmpty()) {
            blocks += EnglishLeafBlock.Prose(prose)
            prose = ArrayList(runs.size)
        }
    }
    runs.forEach { verseRun ->
        val whole = translation(verseRun.surahId, verseRun.ayah)
        // The chapter's panel belongs to the leaf that opens the chapter, which
        // is the leaf carrying the *start* of its first verse.
        if (verseRun.ayah == 1 && verseRun.from == 0) {
            closeProse()
            blocks += EnglishLeafBlock.ChapterOpening(
                surahId = verseRun.surahId,
                basmalah = surahOpensWithBasmalahPreface(verseRun.surahId),
            )
        }
        val from = englishLeafBreak(whole, verseRun.from)
        val to = englishLeafBreak(whole, verseRun.to)
        if (to <= from && whole.isNotEmpty()) return@forEach
        val text = englishVerseProse(whole.substring(from, to), hideParentheticals)
        if (text.isNotEmpty()) {
            prose += EnglishLeafVerse(
                surahId = verseRun.surahId,
                ayah = verseRun.ayah,
                text = text,
                from = from,
                to = to,
                verseLength = whole.length,
            )
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

/**
 * The word of a verse to start reciting from, given how far into the verse's
 * *English* a reader has tapped.
 *
 * The tap is the wash read backwards, so it answers with the same map. With
 * [wordEnds] — the share of the sentence each Arabic word ends at
 * (`EnglishWordAlignment`) — the tap lands on the word whose English the reader
 * actually pointed at: tap "the Book" and the reciter says ٱلْكِتَٰبُ. Without
 * one it falls back to plain proportion, which is near but not exact, because
 * the Arabic order is not the English order.
 *
 * What both replace is worse than approximate: every tap restarted the verse,
 * so a reader who wanted the last clause of a thirty-second verse heard the
 * whole of it again.
 */
fun englishSeekWordPosition(through: Float, words: Int, wordEnds: FloatArray? = null): Int {
    if (words <= 0) return 1
    val at = through.coerceIn(0f, 1f)
    val ends = wordEnds?.takeIf { it.size == words }
        ?: return ((at * words).toInt() + 1).coerceIn(1, words)
    // The first word whose share of the sentence reaches the tap. Words the
    // English gives no room to are skipped, which is right: there is nothing
    // there to have tapped.
    val index = ends.indexOfFirst { at < it }
    return (if (index < 0) words else index + 1).coerceIn(1, words)
}
