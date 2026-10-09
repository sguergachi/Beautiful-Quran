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
 * the two share most of their content words. A weighted lexical alignment
 * anchors their common words; unmatched runs interpolate between neighbours.
 *
 * **Monotone on purpose.** Arabic is not English word order — لَا رَيْبَ فِيهِ is
 * "no doubt in it" and Sahih International sets "about which there is no
 * doubt" — so a faithful alignment would sometimes run backwards. The wash
 * cannot: ink already laid never lifts (docs/INK_ENGINE.md). Recognizable
 * content holds the forward wash until its Arabic owner speaks: "painful
 * punishment" waits for the adjective, even though the noun was said first.
 * Synonyms outside the small shared vocabulary, ambiguous repetitions and
 * translator additions still use an approximate share, not an exact timing.
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
        val gloss = ArrayList<ProseWord>(glosses.size * 3)
        val owner = ArrayList<Int>(glosses.size * 3)
        glosses.forEachIndexed { index, text ->
            words(text).forEach { word ->
                gloss += word
                owner += index
            }
        }
        if (gloss.isEmpty()) return null

        val claimed = IntArray(prose.size) { -1 }
        val anchor = anchors(gloss, owner, prose, glosses.size, claimed)
        val ends = spanEnds(anchor, prose, glosses.size, translation.length)
        holdUnheardWords(ends, gloss, owner, prose, claimed)
        snapToWords(ends, prose, translation.length)
        return FloatArray(ends.size) { (ends[it].toFloat() / translation.length).coerceIn(0f, 1f) }
    }

    /** One word of a sentence: its text, lowercased, and where it ends. */
    private class ProseWord(val text: String, val end: Int, val added: Boolean, val sentence: Int) {
        var key = inflection(text)
    }

    /**
     * The sentence's words. Letters and the apostrophe only: the translation is
     * full of brackets, quotes and hyphens the gloss does not use, and a token
     * that carries them matches nothing.
     */
    private fun words(text: String): List<ProseWord> {
        val out = ArrayList<ProseWord>(text.length / 5 + 1)
        var i = 0
        var added = false
        var sentence = 0
        while (i < text.length) {
            if (!text[i].isLetter()) {
                if (text[i] == '[' || text[i] == '(') added = true
                if (text[i] == ']' || text[i] == ')') added = false
                if (text[i] in ".?!;") sentence++
                i++
                continue
            }
            val start = i
            while (i < text.length && (text[i].isLetter() || text[i] == '\'' || text[i] == '’')) i++
            out += ProseWord(text.substring(start, i).lowercase(), i, added, sentence)
        }
        // "Glad" means "good" in this phrase, not wherever either adjective occurs.
        for (j in 0 until out.lastIndex) {
            if (out[j].text == "glad" && out[j + 1].text in setOf("tiding", "tidings")) out[j].key = "good"
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
        gloss: List<ProseWord>,
        owner: List<Int>,
        prose: List<ProseWord>,
        wordCount: Int,
        claimed: IntArray,
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
                val match = similarity(g, prose[j - 1])
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
                    claimed[j - 1] = word
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
     * An unambiguous content word is a barrier until its Arabic owner speaks.
     * LCS alone drops one side of an inversion ("punishment painful" becomes
     * "painful punishment"), letting an earlier anchor wash straight over it.
     * Hold the forward wash at that word instead. LCS claims resolve repeats;
     * extra matches need a recognizable gloss and the surrounding anchors'
     * sentence context. Translator additions do not establish a barrier.
     */
    private fun holdUnheardWords(
        ends: IntArray,
        gloss: List<ProseWord>,
        owner: List<Int>,
        prose: List<ProseWord>,
        claimed: IntArray,
    ) {
        val original = ends.copyOf()
        val counts = prose.filter { !it.added && it.text !in GRAMMAR }.groupingBy { it.key }.eachCount()
        val exactCounts = prose.groupingBy { it.text }.eachCount()
        val uniqueOwner = HashMap<String, Int>()
        val exactOwner = HashMap<String, Int>()
        val content = Array(ends.size) { HashSet<String>() }
        val complete = BooleanArray(ends.size) { true }
        val firstSentence = IntArray(ends.size) { -1 }
        val lastSentence = IntArray(ends.size) { -1 }
        var complementOwner = -1
        for (i in prose.indices) {
            val at = claimed[i]
            if (at < 0 || prose[i].text in GRAMMAR) continue
            if (firstSentence[at] < 0) firstSentence[at] = prose[i].sentence
            lastSentence[at] = prose[i].sentence
        }
        for (g in gloss.indices) {
            val word = gloss[g]
            if (word.text == "of") complementOwner = owner[g]
            if (word.text in GRAMMAR) continue
            content[owner[g]] += word.key
            if (!word.added && complementOwner != owner[g] && word.key !in counts && word.text !in GLOSS_SUPPLEMENTS) {
                complete[owner[g]] = false
            }
            val previous = uniqueOwner[word.key]
            uniqueOwner[word.key] = if (previous == null || previous == owner[g]) owner[g] else -1
            val exact = exactOwner[word.text]
            exactOwner[word.text] = if (exact == null || exact == owner[g]) owner[g] else -1
        }
        for ((index, word) in prose.withIndex()) {
            if (word.text in GRAMMAR) continue
            // A supplied qualifier can follow a literal head from the same
            // gloss ("[pure] wine"); an unrelated aside cannot anchor itself.
            val head = if (word.added) (index + 1 until prose.size).firstOrNull {
                !prose[it].added
            }?.takeIf { prose[it].text !in GRAMMAR }?.let { claimed[it] } ?: -1 else -1
            if (word.added && (head < 0 || word.key !in content[head])) continue
            val exact = exactOwner[word.text] ?: -1
            val at = when {
                word.added -> head
                claimed[index] >= 0 -> claimed[index]
                counts[word.key] == 1 -> uniqueOwner[word.key] ?: -1
                exact >= 0 && exactCounts[word.text] == 1 && (content[exact].size == 1 ||
                    word.sentence in firstSentence[exact]..lastSentence[exact]) -> exact
                else -> -1
            }
            if (at <= 0) continue
            val before = if (index == 0) 0 else prose[index - 1].end
            // Partial glosses can protect a direct match from interpolation
            // through grammar, but cannot pull it across other content words.
            if (!word.added && !complete[at] && (claimed[index] != at || (at > 1 && original[at - 2] > before &&
                    (0 until at).any { original[it] > before && content[it].isNotEmpty() }))) continue
            if (claimed[index] < 0) {
                if (prose.indices.any { claimed[it] == at && prose[it].key == word.key }) continue
                val left = (at - 1 downTo 0).firstOrNull { lastSentence[it] >= 0 }
                val right = (at + 1 until ends.size).firstOrNull { firstSentence[it] >= 0 }
                val start = if (firstSentence[at] >= 0) firstSentence[at] else left?.let { lastSentence[it] } ?: 0
                val end = if (lastSentence[at] >= 0) lastSentence[at] else right?.let { firstSentence[it] } ?: prose.last().sentence
                if (word.sentence !in start..end) continue
            }
            ends[at - 1] = minOf(ends[at - 1], before)
        }
        for (i in ends.lastIndex - 1 downTo 0) ends[i] = minOf(ends[i], ends[i + 1])
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
            var bestGap = ends[i]
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
     * grammatical one carries little weight, because auxiliaries and pronouns
     * otherwise outvote the content of a reordered phrase. Match inflections,
     * not arbitrary four-letter prefixes ("hearing" is not "hearts").
     */
    private fun similarity(gloss: ProseWord, prose: ProseWord): Float {
        if (prose.added) return 0f
        if (gloss.text == prose.text) return if (gloss.text in GRAMMAR) GRAMMAR_MATCH else EXACT_MATCH
        if (gloss.text in GRAMMAR || prose.text in GRAMMAR) return 0f
        if (gloss.key == prose.key) return STEM_MATCH
        return 0f
    }

    /** Conservative English inflections, normalized once rather than in each DP cell. */
    private fun inflection(text: String): String {
        var word = text.replace('’', '\'').removeSuffix("'s").replace("'", "")
        EQUIVALENT_WORDS[word]?.let { return it }
        if (word.length > 4) {
            word = when {
                word.endsWith("ies") -> word.dropLast(3) + "y"
                word.endsWith("ing") -> word.dropLast(3)
                word.endsWith("ed") -> word.dropLast(2)
                word.endsWith("es") -> word.dropLast(2)
                word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
                else -> word
            }
        }
        if (word.length > 3 && word.endsWith("e")) word = word.dropLast(1)
        if (word.length > 3 && word.last() == word[word.lastIndex - 1] && word.last() in "bdgmnprt") {
            word = word.dropLast(1)
        }
        return EQUIVALENT_WORDS[word] ?: word
    }

    private const val EXACT_MATCH = 3f
    private const val STEM_MATCH = 2f
    private const val GRAMMAR_MATCH = 0.25f

    private val EQUIVALENT_WORDS = mapOf(
        "slave" to "servant", "slaves" to "servant", "servants" to "servant",
        "poor" to "needy", "needy" to "needy",
        "forever" to "eternal", "eternally" to "eternal",
        "sorrow" to "grief",
        "single" to "one",
        "said" to "say", "says" to "say", "sent" to "send",
        "died" to "die", "dying" to "die", "die" to "die",
        "came" to "com", "come" to "com", "coming" to "com",
        "made" to "mak", "took" to "tak", "taken" to "tak",
        "gave" to "giv", "given" to "giv", "knew" to "know", "known" to "know",
        "spread" to "spread",
    )

    /** Complements/intensity the prose can leave implicit in the matched gloss head. */
    private val GLOSS_SUPPLEMENTS = setOf("one", "thing", "things", "partners", "existence", "abundantly")

    private const val SKIP_GLOSS: Byte = 0
    private const val SKIP_PROSE: Byte = 1
    private const val MATCH: Byte = 2

    /** Words too common to anchor on their own. */
    private val GRAMMAR = setOf(
        "a", "all", "an", "and", "are", "as", "at", "be", "been", "but", "by",
        "did", "do", "does", "for", "from", "he", "her", "his", "i", "if", "in",
        "is", "it", "its", "no", "nor", "not", "of", "on", "or", "she", "so",
        "that", "the", "their", "them", "these", "they", "this", "those", "to",
        "was", "we", "were", "will", "with", "you",
        "can", "could", "had", "has", "have", "may", "might", "must", "our",
        "shall", "should", "there", "then", "what", "when", "where", "which",
        "who", "whom", "whose", "would", "your", "my",
        "any", "both", "each", "every", "even", "more", "most", "oft",
        "ones", "only", "other", "others", "over", "surely", "well",
        "down", "forth", "up",
        "about", "above", "after", "against", "along", "among", "around",
        "before", "behind", "below", "beside", "besides", "between", "concerning",
        "during", "into", "off", "out", "through", "toward", "towards", "under",
        "until", "upon", "while", "within", "without",
        "him", "me", "us", "ours", "yours", "yourself", "yourselves",
        "ourselves", "himself", "herself", "itself", "themselves",
        "although", "because", "hence", "however", "otherwise", "therefore",
        "though", "used", "whatever", "whenever", "wherever", "whether", "whoever",
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
