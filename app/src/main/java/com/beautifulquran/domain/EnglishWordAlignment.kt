package com.beautifulquran.domain

import com.beautifulquran.data.model.SurahContent
import java.util.concurrent.ConcurrentHashMap

/**
 * Where each Arabic word of a verse lands inside its English translation.
 *
 * The English leaf sets the verse translation as prose, and the reciter's
 * timings name Arabic words. For a long time the leaf refused to bridge that
 * and washed the sentence by proportion — word three of seven meant three
 * sevenths of the characters — because there is no alignment in the data. That
 * is honest but it is not what a reader hears: the voice says "ٱلْكِتَٰبُ" and
 * the ink is somewhere in the middle of "about which".
 *
 * There *is* enough in the data to do better. Every Arabic word carries its own
 * gloss (`Word.translation`, the interlinear crib the scrolling reader
 * lyricizes), and the translation is a translation of the same sentence — so
 * the two share most of their content words. Aligning the gloss stream to the
 * translation, monotonically, anchors each Arabic word to the English words it
 * is actually about: over all 6,236 verses, 84 % of Arabic words land on a
 * lexical anchor, and the rest are interpolated between their neighbours.
 *
 * **Monotone on purpose.** Arabic is not English word order — لَا رَيْبَ فِيهِ is
 * "no doubt in it" and Sahih International sets "about which there is no
 * doubt" — so a faithful alignment would sometimes run backwards. The wash
 * cannot: ink already laid never lifts (docs/INK_ENGINE.md). So the alignment
 * is constrained to advance, and a reordered clause is absorbed by sliding a
 * word or two rather than by jumping the wash back. The result is never worse
 * than the proportion it replaces — with no anchors at all it *is* that
 * proportion — and where the anchors are dense it is exact.
 *
 * Pure Kotlin over immutable data, unit-tested on the JVM, computed once per
 * verse and remembered. The largest verse in the book (2:282) is a 258 × 255
 * alignment table, which is nothing.
 */
object EnglishWordAlignment {

    /**
     * The share of [translation] each of [glosses] ends at, 0..1 and
     * non-decreasing, with the last always 1. Null when there is nothing to
     * align, and the caller should fall back to plain proportion.
     *
     * [glosses] must be the verse's Arabic words in recitation order — the
     * same order and count as the timing segments the ink is driven from.
     */
    fun wordEnds(translation: String, glosses: List<String>): FloatArray? {
        if (glosses.isEmpty() || translation.isEmpty()) return null
        val prose = words(translation)
        if (prose.isEmpty()) return null
        val gloss = ArrayList<String>(glosses.size * 3)
        val owner = ArrayList<Int>(glosses.size * 3)
        glosses.forEachIndexed { index, text ->
            words(text).forEach { word ->
                gloss += word.text
                owner += index
            }
        }
        if (gloss.isEmpty()) return null

        val anchor = anchors(gloss, owner, prose, glosses.size)
        val ends = spanEnds(anchor, prose, glosses.size, translation.length)
        snapToWords(ends, prose, translation.length)
        return FloatArray(ends.size) { (ends[it].toFloat() / translation.length).coerceIn(0f, 1f) }
    }

    /** One word of a sentence: its text, lowercased, and where it ends. */
    private class ProseWord(val text: String, val end: Int)

    /**
     * The sentence's words. Letters and the apostrophe only: the translation is
     * full of brackets, quotes and hyphens the gloss does not use, and a token
     * that carries them matches nothing.
     */
    private fun words(text: String): List<ProseWord> {
        val out = ArrayList<ProseWord>(text.length / 5 + 1)
        var i = 0
        while (i < text.length) {
            if (!text[i].isLetter()) {
                i++
                continue
            }
            val start = i
            while (i < text.length && (text[i].isLetter() || text[i] == '\'')) i++
            out += ProseWord(text.substring(start, i).lowercase(), i)
        }
        return out
    }

    /**
     * For each Arabic word, the index of the last sentence word its gloss is
     * matched to, or −1. A longest-common-subsequence table with a lexical
     * score: the matches it keeps are the ones that agree with each other, so
     * a stray "the" cannot pull a word to the far end of a long verse.
     */
    private fun anchors(
        gloss: List<String>,
        owner: List<Int>,
        prose: List<ProseWord>,
        wordCount: Int,
    ): IntArray {
        val n = gloss.size
        val m = prose.size
        val stride = m + 1
        val back = ByteArray(stride * (n + 1))
        var previous = FloatArray(stride)
        var current = FloatArray(stride)
        for (i in 1..n) {
            val g = gloss[i - 1]
            current[0] = 0f
            for (j in 1..m) {
                var best = previous[j]
                var step = SKIP_GLOSS
                if (current[j - 1] > best) {
                    best = current[j - 1]
                    step = SKIP_PROSE
                }
                val match = similarity(g, prose[j - 1].text)
                if (match > 0f && previous[j - 1] + match > best) {
                    best = previous[j - 1] + match
                    step = MATCH
                }
                current[j] = best
                back[i * stride + j] = step
            }
            val swap = previous
            previous = current
            current = swap
        }

        val anchor = IntArray(wordCount) { -1 }
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            when (back[i * stride + j]) {
                MATCH -> {
                    val word = owner[i - 1]
                    // Walking back, so the first hit is this word's furthest.
                    if (anchor[word] < j - 1) anchor[word] = j - 1
                    i--
                    j--
                }
                SKIP_PROSE -> j--
                else -> i--
            }
        }
        return anchor
    }

    /**
     * Character offsets, one per Arabic word, from the anchored ones: an
     * unanchored run is spread evenly between the anchors around it, which is
     * the old proportion applied locally. The last word closes the sentence
     * whatever it matched, because the wash must finish when the verse does.
     */
    private fun spanEnds(
        anchor: IntArray,
        prose: List<ProseWord>,
        wordCount: Int,
        length: Int,
    ): IntArray {
        val ends = IntArray(wordCount) { -1 }
        var run = 0
        for (word in 0 until wordCount) {
            val at = anchor[word]
            if (at < 0) continue
            val end = maxOf(prose[at].end, run)
            ends[word] = end
            run = end
        }
        ends[wordCount - 1] = maxOf(length, run)

        var previousWord = -1
        var previousEnd = 0
        var word = 0
        while (word < wordCount) {
            if (ends[word] >= 0) {
                previousWord = word
                previousEnd = ends[word]
                word++
                continue
            }
            var next = word
            while (ends[next] < 0) next++
            val span = ends[next] - previousEnd
            val steps = next - previousWord
            for (gap in word until next) {
                ends[gap] = previousEnd + span * (gap - previousWord) / steps
            }
            word = next
        }
        return ends
    }

    /**
     * Moves every boundary onto a word end of the sentence, so the wash never
     * stops halfway through an English word — an interpolated boundary lands
     * wherever the arithmetic put it, and "slumbe|r" is not a place ink rests.
     * Boundaries that collapse onto each other are correct: the English simply
     * has no separate words for that Arabic one.
     */
    private fun snapToWords(ends: IntArray, prose: List<ProseWord>, length: Int) {
        var run = 0
        for (i in ends.indices) {
            var best = 0
            var bestGap = Int.MAX_VALUE
            for (word in prose) {
                val gap = kotlin.math.abs(word.end - ends[i])
                if (gap < bestGap) {
                    bestGap = gap
                    best = word.end
                }
            }
            if (kotlin.math.abs(length - ends[i]) < bestGap) best = length
            ends[i] = maxOf(best, run)
            run = ends[i]
        }
        ends[ends.size - 1] = maxOf(length, run)
    }

    /**
     * How much two words agree. A content word carries the alignment; a
     * grammatical one is worth a third of that, because "the" and "of" appear
     * everywhere and would otherwise anchor a verse to its own noise. A shared
     * four-letter opening covers the inflections the two texts differ by
     * (`revealed` / `reveals`, `heaven` / `heavens`).
     */
    private fun similarity(gloss: String, prose: String): Float {
        if (gloss == prose) return if (gloss in GRAMMAR) GRAMMAR_MATCH else EXACT_MATCH
        if (gloss.length >= STEM && prose.length >= STEM &&
            gloss.regionMatches(0, prose, 0, STEM)
        ) {
            return STEM_MATCH
        }
        return 0f
    }

    private const val EXACT_MATCH = 3f
    private const val STEM_MATCH = 2f
    private const val GRAMMAR_MATCH = 1f
    private const val STEM = 4

    private const val SKIP_GLOSS: Byte = 0
    private const val SKIP_PROSE: Byte = 1
    private const val MATCH: Byte = 2

    /** Words too common to anchor on their own. */
    private val GRAMMAR = setOf(
        "a", "all", "an", "and", "are", "as", "at", "be", "been", "but", "by",
        "did", "do", "does", "for", "from", "he", "her", "his", "i", "if", "in",
        "is", "it", "its", "no", "nor", "not", "of", "on", "or", "she", "so",
        "that", "the", "their", "them", "these", "they", "this", "those", "to",
        "was", "we", "were", "with", "you",
    )
}

/**
 * The loaded chapter's alignments, solved the first time a verse is asked for
 * and kept after.
 *
 * Eagerly solving a chapter is not free — Al-Baqarah's 286 verses are a few
 * milliseconds on a desktop JVM and several times that on a phone, and the
 * reader asks for this while composing a leaf. Lazily it is one verse's work
 * at a time, which is under a tenth of a millisecond for an ordinary verse and
 * 1.4 ms for the longest in the book (2:282), paid once.
 *
 * Read from composition, from `derivedStateOf`, and from the follow
 * collector — hence the concurrent map. An absent alignment is stored as
 * [NONE] rather than not stored, so an unalignable verse is not re-solved on
 * every frame that asks.
 */
class EnglishVerseAlignments(private val content: SurahContent) {

    private val solved = ConcurrentHashMap<Int, FloatArray>()

    /** Where each Arabic word of [ayah] ends in its English, or null. */
    fun of(ayah: Int?): FloatArray? {
        if (ayah == null) return null
        val ends = solved.computeIfAbsent(ayah) { number ->
            content.ayahs
                .firstOrNull { it.number == number }
                ?.let { verse ->
                    EnglishWordAlignment.wordEnds(
                        verse.translation,
                        verse.words.map { it.translation },
                    )
                }
                ?: NONE
        }
        return ends.takeIf { it !== NONE }
    }

    private companion object {
        /** Solved, and there is no alignment — distinct from "not solved". */
        val NONE = FloatArray(0)
    }
}
