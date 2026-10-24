package com.beautifulquran.domain

import com.beautifulquran.data.model.SurahContent
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/** A printed English span and the 1-based Arabic word that speaks its meaning. */
data class EnglishWordSpan(val from: Float, val to: Float, val position: Int)

/** Semantic owners for taps, and a continuous forward wash derived from those owners. */
data class EnglishAlignment(val wordEnds: FloatArray, val spans: List<EnglishWordSpan>) {
    /** A later spoken meaning brings every preceding English word into the same wash. */
    val washSpans: List<EnglishWordSpan> = buildList {
        var position = Int.MAX_VALUE
        for (span in spans.asReversed()) {
            position = minOf(position, span.position)
            val previous = lastOrNull()
            if (previous?.position == position) {
                this[lastIndex] = previous.copy(from = span.from)
            } else add(span.copy(position = position))
        }
        reverse()
    }

    /** The Arabic owner of a point in the printed English, including reordered words. */
    fun positionAt(through: Float): Int =
        spans.firstOrNull { through.coerceIn(0f, 1f) < it.to }?.position ?: spans.lastOrNull()?.position ?: 1

    /** Where the forward wash begins; words already included never move it backwards. */
    fun through(position: Int): Float = washSpans.firstOrNull { it.position >= position }?.from ?: 1f
}

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
 * Arabic is not English word order. Each recognized English span keeps its
 * Arabic owner for interaction. The wash reaches that meaning when it is
 * spoken, including all intervening English words in one continuous prefix.
 * Ink already laid never lifts (docs/INK_ENGINE.md). Unmatched text uses the
 * guarded, interpolated alignment between recognized neighbours.
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
    fun wordEnds(translation: String, glosses: List<String>, lemmas: List<String> = emptyList(), arabic: List<String> = emptyList()): FloatArray? =
        align(translation, glosses, lemmas, arabic)?.wordEnds

    /** QAC [lemmas] resolve shared heads; [arabic] corroborates heads omitted from grammatical cards. */
    fun align(translation: String, glosses: List<String>, lemmas: List<String> = emptyList(), arabic: List<String> = emptyList()): EnglishAlignment? {
        if (glosses.isEmpty() || translation.isEmpty()) return null
        val prose = proseWords(translation, glosses)
        if (prose.isEmpty()) return null
        val spoken = glossWords(glosses, prose, lemmas)
        if (spoken.isEmpty()) return null
        val gloss = spoken.map { it.first }
        val owner = spoken.map { it.second }

        pairOccurrences(gloss, owner, prose, lemmas.takeIf { it.size == glosses.size }.orEmpty(), arabic)
        val claimed = IntArray(prose.size) { -1 }
        val anchor = anchors(gloss, owner, prose, glosses.size, claimed)
        val ends = spanEnds(anchor, prose, glosses.size, translation.length)
        val contextualEnds = ends.copyOf().also { snapToWords(it, prose, translation.length) }
        val sharedGroups = spoken.mapNotNull { it.first.sharedGroup }.distinct()
        val verified = holdUnheardWords(ends, gloss, owner, prose, claimed)
        snapToWords(ends, prose, translation.length)
        val shares = FloatArray(ends.size) { (ends[it].toFloat() / translation.length).coerceIn(0f, 1f) }
        val reflexives = spoken.filter { it.first.text.endsWith("self") || it.first.text.endsWith("selves") }
            .groupBy({ it.first.text }, { it.second })
        val positions = IntArray(prose.size) { index ->
            val word = prose[index]
            var fallback = ends.indexOfFirst { word.end <= it }.coerceAtLeast(0)
            val contextual = contextualEnds.indexOfFirst { word.end <= it }.coerceAtLeast(0)
            // An explicit reordered head can trim the interpolation before
            // it; that must not exile the shared phrase's unclaimed remainder.
            sharedGroups.firstOrNull {
                contextual in it || (contextual == it.last + 1 && index < anchor.getOrElse(contextual) { -1 })
            }?.let { fallback = minOf(fallback, it.last) }
            if (word.added) fallback else if (word.text in GRAMMAR) {
                // "Wronging themselves" reverses Arabic's object and verb.
                reflexives[word.text]?.distinct()?.singleOrNull()
                    ?.takeIf { prose.count { other -> other.text == word.text } == 1 }
                    ?: claimed[index].takeIf { it >= 0 } ?: fallback
            } else verified[index].takeIf { it >= 0 } ?: fallback
        }
        closeAnchoredSentences(anchor, claimed, prose, positions, glosses)
        // A possessive or auxiliary travels with its printed head when that
        // head's gloss says it too: "Our servants" cannot split across owners.
        val grammar = glosses.map { words(it).map { word -> word.text }.toSet() }
        var head = -1
        for (index in prose.indices.reversed()) {
            val word = prose[index]
            if (word.added) continue
            if (word.text !in GRAMMAR) head = index
            else if (head >= 0 && word.sentence == prose[head].sentence &&
                word.text in grammar[positions[head]]) positions[index] = positions[head]
        }
        val spans = ArrayList<EnglishWordSpan>(prose.size)
        prose.forEachIndexed { index, word ->
            val span = EnglishWordSpan(
                from = if (index == 0) 0f else prose[index - 1].end.toFloat() / translation.length,
                to = if (index == prose.lastIndex) 1f else word.end.toFloat() / translation.length,
                position = positions[index] + 1,
            )
            if (spans.lastOrNull()?.position == span.position) {
                spans[spans.lastIndex] = spans.last().copy(to = span.to)
            } else spans += span
        }
        return EnglishAlignment(shares, spans)
    }

    /**
     * The Arabic word (0-based) whose gloss names the English word at [offset]
     * of [translation], in any order. A hold means the word under the finger,
     * but [wordEnds] is monotone and absorbs reordering, so a hold read off it
     * lands on a neighbour: "painful punishment" said "punishment painful".
     * Null when neither the glosses nor corroborated Arabic evidence identifies
     * the word (grammar, additions, or ambiguous repeats); the caller falls back.
     */
    fun ownerAt(translation: String, glosses: List<String>, offset: Int, lemmas: List<String> = emptyList(), arabic: List<String> = emptyList()): Int? {
        val prose = proseWords(translation, glosses)
        val held = prose.indexOfFirst { offset <= it.end }
        val word = prose.getOrNull(held) ?: return null
        if (offset < word.end - word.text.length || word.added || word.text in GRAMMAR) return null
        val spoken = glossWords(glosses, prose, lemmas)
        pairOccurrences(spoken.map { it.first }, spoken.map { it.second }, prose,
            lemmas.takeIf { it.size == glosses.size }.orEmpty(), arabic)
        if (word.arabicOwner >= 0) return word.arabicOwner
        val owners = spoken.filter { (text, _) ->
            text.text !in GRAMMAR && text.key == word.key
        }.map { it.second }
        if (owners.distinct().size == 1) return owners[0]
        // Said more than once: the n-th in the sentence is the n-th in the glosses.
        val said = prose.filter { !it.added && it.key == word.key }
        if (said.size != owners.size) return null
        return owners[said.indexOf(word)]
    }

    /**
     * A multiword gloss copied onto adjacent Arabic words names one phrase,
     * not several utterances. Divide that phrase over its owners; its final
     * token belongs to the final word ("their gaze" on مِنْ أَبْصَارِهِمْ).
     * Single-token duplicates remain separate, since they can be real repeats.
     */
    private fun glossWords(glosses: List<String>, prose: List<ProseWord>, lemmas: List<String>): List<Pair<ProseWord, Int>> = buildList {
        val counts = prose.filter { !it.added }.groupingBy { it.key }.eachCount()
        var first = 0
        while (first < glosses.size) {
            val tokens = words(glosses[first])
            var next = first + 1
            var headOwners = emptyMap<String, Int>()
            var remainder = emptyList<Int>()
            if (tokens.size > 1) {
                while (next < glosses.size && glosses[next] == glosses[first]) next++
                // "Musa said" can be copied onto both Arabic words. QAC
                // identifies Musa independently; the other word speaks the verb.
                if (lemmas.size == glosses.size && next - first == 2) {
                    headOwners = tokens.mapNotNull { word ->
                        (first until next).singleOrNull { at ->
                            namesHead(word.key, lemmas.getOrElse(at) { "" })
                        }?.let { word.key to it }
                    }.toMap()
                    remainder = (first until next).filter { it !in headOwners.values }
                }
                val resolved = headOwners.isNotEmpty() && remainder.size == 1
                // Repeated verbs such as "you do good, you do good" are real
                // repeats. Only a grammatical prefix plus one printed head
                // identifies a shared phrase without Arabic syntax evidence.
                if (!resolved && (tokens.dropLast(1).any { it.text !in GRAMMAR && !it.added } ||
                    counts[tokens.last().key] != 1)) next = first + 1
            }
            tokens.forEachIndexed { index, word ->
                if (headOwners.isNotEmpty() && remainder.size == 1) {
                    word.sharedGroup = first until next
                }
                val at = if (headOwners.isNotEmpty() && remainder.size == 1) headOwners[word.key] ?: remainder.single()
                    else first + ((index + 1) * (next - first) - 1) / tokens.size
                add(word to at)
            }
            first = next
        }
    }.sortedBy { it.second }.also { spoken ->
        // Sahih calls Rahman "Most Merciful" outside the opening chapter.
        // Keep Rahman and Rahim distinct when both occur in this verse.
        if (spoken.any { it.first.key == inflection("gracious") } &&
            spoken.none { it.first.key == inflection("merciful") }) {
            prose.filter { it.text == "most merciful" }.forEach { it.key = inflection("gracious") }
        }
    }

    /** One word of a sentence: its text, lowercased, and where it ends. */
    private class ProseWord(val text: String, val end: Int, val added: Boolean, val sentence: Int) {
        var key = inflection(text)
        var arabicOwner = -1
        var sharedGroup: IntRange? = null
    }

    /**
     * The sentence's words. Letters and the apostrophe only: the translation is
     * full of brackets, quotes and hyphens the gloss does not use, and a token
     * that carries them matches nothing.
     */
    private fun words(text: String, phrases: Set<List<String>>? = null): List<ProseWord> {
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
            // The translation is typeset with curled apostrophes (Qur’an); one
            // word either way, and spelled straight so it matches the glosses.
            while (i < text.length && (text[i].isLetter() || text[i] in '\u0300'..'\u036f' || text[i] == '\'' || text[i] == '’')) i++
            out += ProseWord(text.substring(start, i).replace('’', '\'').lowercase(), i, added, sentence)
        }
        // One meaning can span several printed words: "Exalted in Might"
        // and "All-Mighty", or "give life" and "life".
        var at = 0
        while (at < out.size) {
            val first = out[at]
            val phrase = PHRASES.firstOrNull { (parts, key) ->
                (phrases == null || parts in phrases) &&
                parts.indices.all { offset ->
                    val word = out.getOrNull(at + offset)
                    word != null && word.text == parts[offset] &&
                        word.added == first.added && word.sentence == first.sentence &&
                        (offset == 0 || text.substring(out[at + offset - 1].end, word.end - word.text.length)
                            .all { it.isWhitespace() || it == '-' })
                }
            }
            if (phrase != null) {
                val (parts, key) = phrase
                val last = out[at + parts.lastIndex]
                out[at] = ProseWord(
                    text.substring(first.end - first.text.length, last.end).lowercase(),
                    last.end, first.added, first.sentence,
                ).apply { this.key = key }
                repeat(parts.size - 1) { out.removeAt(at + 1) }
            }
            at++
        }
        // "Glad" means "good" in this phrase, not wherever either adjective occurs.
        for (j in 0 until out.lastIndex) {
            if (out[j].text == "glad" && out[j + 1].text in setOf("tiding", "tidings")) out[j].key = "good"
        }
        // Drawing a headcover over the chest is translated as wrapping it;
        // "draw" and "wrap" are not interchangeable outside that phrase.
        if (out.any { it.key == "headcover" }) {
            out.filter { it.text == "wrap" }.forEach { it.key = "draw" }
        }
        return out
    }

    /** Join a printed phrase only when the glosses name that single meaning. */
    private fun proseWords(text: String, glosses: List<String>): List<ProseWord> {
        val spoken = glosses.flatMap { words(it) }
        val keys = spoken.map { it.key }.toSet()
        val (compound, single) = spoken.partition { word -> word.text.any { it.isWhitespace() || it == '-' } }
        val compounds = compound.map { it.key }.toSet()
        val singles = single.map { it.key }.toSet()
        val phrases = PHRASES.filter { (parts, key) ->
            (key in keys || (parts == listOf("most", "merciful") &&
                inflection("gracious") in keys && inflection("merciful") !in keys)) &&
                parts.filter { it !in GRAMMAR }.map(::inflection).let { heads ->
                heads.size < 2 || heads.any { it !in keys } || (key in compounds && key !in singles)
            }
        }.map { it.first }.toSet()
        return words(text, phrases).also { prose ->
            // A gendered offender can be printed as a woman/man followed by
            // an explanation. Literal gender or offender heads keep priority.
            for ((gender, names) in listOf("woman" to listOf("fornicatress", "adulteress"), "man" to listOf("fornicator", "adulterer"))) {
                val head = names.map(::inflection).filter { it in keys }.singleOrNull() ?: continue
                if (gender !in keys && prose.none { it.key == head }) {
                    prose.filter { it.text == gender }.forEach { it.key = head }
                }
                if (gender == "woman") prose.zipWithNext().filter { (before, word) ->
                    before.text == "female" && !word.added &&
                        word.key in setOf(inflection("fornicator"), inflection("adulterer"))
                }.forEach { (_, word) -> word.key = head }
            }
            // The divine title is distinct from a nearby verb "knows".
            if ("omniscient" in keys && spoken.none { it.text in setOf("knowing", "knower") }) {
                prose.filter { it.text in setOf("knowing", "knower") }.forEach { it.key = "omniscient" }
            }
            // Iqamah can be printed "performance of prayer". Keep a literal
            // performance when supplied, and leave prayer with its own head.
            val literalPerformance = glosses.any { gloss ->
                words(gloss).filter { !it.added && it.text !in GRAMMAR }
                    .singleOrNull()?.key == inflection("performance")
            }
            if ("establish" in keys && !literalPerformance) {
                for (j in 0 until prose.lastIndex) {
                    if (prose[j].text == "performance" && prose[j + 1].text == "of" &&
                        prose.getOrNull(j + 2)?.key == "prayer") prose[j].key = "establish"
                }
            }
            // The conditional "you come" can be printed "you brought".
            // A literal bring gloss must keep its own meaning and context.
            if (inflection("come") in keys && inflection("bring") !in keys) {
                prose.filter { it.text == "brought" }.forEach { it.key = inflection("come") }
            }
        }
    }

    /**
     * Equal repetition counts give each occurrence its own anchor. Otherwise
     * LCS can spend a later "sons" on an earlier possessive and drag the
     * intervening "brothers" along with it. Unequal or shared gloss counts
     * remain ambiguous and keep the ordinary contextual alignment.
     */
    private fun pairOccurrences(gloss: List<ProseWord>, owner: List<Int>, prose: List<ProseWord>, lemmas: List<String>, arabic: List<String>) {
        // An explicit Arabic name outranks a gloss mentioning that name as
        // a complement ("fear Allah"). Require both sources to name the head.
        for ((key, positions) in lemmas.indices.filter { lemmas[it] in ARABIC_HEADS }
            .groupBy { ARABIC_HEADS[lemmas[it]] }) {
            val matches = prose.filter { !it.added && it.key == key }
            if (matches.size == positions.size && positions.all { at ->
                sourceNamesHead(at, gloss, owner, lemmas, arabic)
            }) matches.zip(positions).forEach { (word, at) -> word.arabicOwner = at }
        }
        for (byMeaning in listOf(false, true)) {
            fun form(word: ProseWord) = if (byMeaning) word.key else literal(word.text)
            val spoken = gloss.indices.filter { !gloss[it].added && gloss[it].text !in GRAMMAR }
                .groupBy { form(gloss[it]) }
            val printed = prose.filter { !it.added && it.text !in GRAMMAR }.groupBy(::form)
            for ((key, indices) in spoken) {
                val matches = printed[key] ?: continue
                if (indices.size < 2 || indices.size != matches.size ||
                    indices.map { owner[it] }.distinct().size != indices.size ||
                    (matches.map { it.sentence }.distinct().size != 1 &&
                        gloss[indices.first()].key !in NAMED_MEANINGS &&
                        indices.map { lemmas.getOrElse(owner[it]) { "" } }.let { heads ->
                            heads.any { it.isEmpty() } || heads.distinct().size != 1
                        })) continue
                // Exact distinct forms already distinguish an inversion:
                // "approached the Approaching Day" must keep those forms.
                if (byMeaning && indices.groupingBy { literal(gloss[it].text) }.eachCount() ==
                    matches.groupingBy { literal(it.text) }.eachCount()) continue
                indices.forEachIndexed { occurrence, index ->
                    val paired = "${gloss[index].key}#$key#$occurrence"
                    gloss[index].key = paired
                    matches[occurrence].key = paired
                }
            }
        }
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

    /** Unspoken grammar tails stay in their sentence between adjacent Arabic anchors. */
    private fun closeAnchoredSentences(anchor: IntArray, claimed: IntArray, prose: List<ProseWord>, positions: IntArray, glosses: List<String>) {
        val grammarGlosses = glosses.map { words(it).map { word -> word.text } }
        for (position in 0 until anchor.lastIndex) {
            val at = anchor[position]
            val next = anchor[position + 1]
            if (at < 0 || next <= at || prose[at].sentence == prose[next].sentence) continue
            val tail = (at + 1 until next).takeWhile { prose[it].sentence == prose[at].sentence }
            if (tail.any { claimed[it] >= 0 || positions[it] <= position || prose[it].added || prose[it].text !in GRAMMAR }) continue
            val literal = grammarGlosses.indices.filter { grammarGlosses[it] == tail.map { index -> prose[index].text } }
            if (literal.isNotEmpty()) {
                literal.singleOrNull()?.let { owner -> tail.forEach { positions[it] = owner } }
                continue
            }
            tail.forEach { positions[it] = position }
        }
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
    ): IntArray {
        val verified = IntArray(prose.size) { -1 }
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
        fun sentenceRange(at: Int): IntRange {
            val left = (at - 1 downTo 0).firstOrNull { lastSentence[it] >= 0 }
            val right = (at + 1 until ends.size).firstOrNull { firstSentence[it] >= 0 }
            val start = if (firstSentence[at] >= 0) firstSentence[at] else left?.let { lastSentence[it] } ?: 0
            val end = if (lastSentence[at] >= 0) lastSentence[at] else right?.let { firstSentence[it] } ?: prose.last().sentence
            return start..end
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
            val unique = uniqueOwner[word.key] ?: -1
            val at = when {
                word.arabicOwner >= 0 -> word.arabicOwner
                word.added -> head
                claimed[index] >= 0 -> claimed[index]
                unique >= 0 && (counts[word.key] == 1 || prose.count {
                    !it.added && it.key == word.key && it.sentence in sentenceRange(unique)
                } == 1) -> unique
                exact >= 0 && exactCounts[word.text] == 1 && (content[exact].size == 1 ||
                    word.sentence in firstSentence[exact]..lastSentence[exact]) -> exact
                else -> -1
            }
            if (at < 0) continue
            val before = if (index == 0) 0 else prose[index - 1].end
            // Partial glosses can protect a direct match from interpolation
            // through grammar, but cannot pull it across other content words.
            if (word.arabicOwner < 0 && !word.added && !complete[at] && (claimed[index] != at || (at > 1 && original[at - 2] > before &&
                    (0 until at).any { original[it] > before && content[it].isNotEmpty() }))) continue
            if (word.arabicOwner < 0 && claimed[index] < 0) {
                if (prose.indices.any { claimed[it] == at && prose[it].key == word.key }) continue
                if (word.sentence !in sentenceRange(at)) continue
            }
            verified[index] = at
            if (at > 0) ends[at - 1] = minOf(ends[at - 1], before)
        }
        // Sayyi'at is a noun glossed "the evil" but printed "evil deeds".
        // Carry its implicit noun only when no deed is named in this clause.
        for (index in 1 until prose.size) {
            val at = verified[index - 1]
            if (at >= 0 && complete[at] && !prose[index].added && !prose[index - 1].added &&
                prose[index].text == "deeds" && prose[index - 1].text == "evil" && claimed[index] < 0 &&
                gloss.indices.none { gloss[it].key == prose[index].key &&
                    prose[index].sentence in sentenceRange(owner[it]) }) verified[index] = at
        }
        for (i in ends.lastIndex - 1 downTo 0) ends[i] = minOf(ends[i], ends[i + 1])
        return verified
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
        if (prose.added || gloss.key != prose.key) return 0f
        if (gloss.text == prose.text) return if (gloss.text in GRAMMAR) GRAMMAR_MATCH else EXACT_MATCH
        if (gloss.text in GRAMMAR || prose.text in GRAMMAR) return 0f
        if (gloss.key == prose.key) return STEM_MATCH
        return 0f
    }

    /** Conservative English inflections, normalized once rather than in each DP cell. */
    private fun inflection(text: String): String {
        val latin = if (text.any { it.code > 127 }) Normalizer.normalize(text, Normalizer.Form.NFD)
            .filterNot { it in '\u0300'..'\u036f' || it == '\u02be' || it == '\u02bf' } else text
        var word = latin.replace('’', '\'').removeSuffix("'s").replace("'", "")
        word = EQUIVALENT_WORDS[word] ?: word
        if (word.length > 4) {
            word = when {
                word.endsWith("ies") -> word.dropLast(3) + "y"
                word.endsWith("ings") -> word.dropLast(4)
                word.endsWith("ees") -> word.dropLast(1)
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

    private fun literal(text: String) = text.removeSuffix("'s").removeSuffix("’s").trimEnd('\'', '’')

    private val EQUIVALENT_WORDS = mapOf(
        "slave" to "servant", "slaves" to "servant", "servants" to "servant",
        "gaze" to "vision", "gaz" to "vision",
        "sky" to "heaven", "land" to "earth", "true" to "truth", "tru" to "truth",
        "masjid" to "mosque", "masajid" to "mosque", "wives" to "wife", "wiv" to "wif",
        "path" to "way", "days" to "day", "eats" to "eat", "dies" to "die", "heard" to "hear",
        "denied" to "deny", "decreed" to "decree",
        "slay" to "kill", "slays" to "kill", "slain" to "kill", "thankful" to "grateful",
        "flog" to "lash",
        "commerce" to "trade",
        "glorify" to "exalt", "glorifi" to "exalt", "glorification" to "exalt",
        "bosom" to "chest",
        "tell" to "say", "apparent" to "appear",
        "poor" to "needy", "needy" to "needy",
        "forever" to "eternal", "eternally" to "eternal",
        "sorrow" to "grief",
        "single" to "one",
        "said" to "say", "says" to "say", "sent" to "send",
        "died" to "die", "dying" to "die", "die" to "die",
        "came" to "com", "come" to "com", "coming" to "com",
        "made" to "mak", "took" to "tak", "taken" to "tak",
        "gave" to "giv", "given" to "giv", "knew" to "know", "known" to "know",
        "became" to "becom", "become" to "becom", "felt" to "feel",
        "threw" to "throw", "thrown" to "throw", "brought" to "bring",
        "seen" to "see", "saw" to "see", "taught" to "teach", "written" to "writ",
        "wrote" to "writ", "ate" to "eat", "eaten" to "eat", "stood" to "stand",
        "sought" to "seek", "fell" to "fall", "fallen" to "fall", "held" to "hold",
        "knower" to "know", "hearer" to "hear", "seer" to "see", "attractive" to "attract",
        "scripture" to "book", "manifest" to "evident",
        "deity" to "god", "deities" to "god", "gods" to "god",
        "work" to "deed", "forceful" to "firm", "appropriate" to "suitable",
        "establishment" to "establish",
        "scoff" to "mock", "ridicule" to "mock", "ridicul" to "mock", "purest" to "best",
        "faction" to "group", "party" to "group",
        "shaitaan" to "satan", "shaitan" to "satan", "shaytan" to "satan",
        "musa" to "moses", "nuh" to "noah", "ibrahim" to "abraham",
        "firaun" to "pharaoh", "maryam" to "mary", "isa" to "jesus", "yusuf" to "joseph",
        "lut" to "lot", "dawud" to "david", "dawood" to "david", "sulaiman" to "solomon", "yunus" to "jonah",
        "ishaq" to "isaac", "yaqub" to "jacob", "ismail" to "ishmael", "harun" to "aaron",
        "zakariya" to "zechariah", "yahya" to "john", "ilyas" to "elijah", "elias" to "elijah", "ayyub" to "job",
        "taurat" to "torah", "taurah" to "torah", "injeel" to "gospel",
        "spread" to "spread",
    )

    private val PHRASES = mapOf(
        "clear evidence" to "clear", "clear evidences" to "clear",
        "woman found guilty of sexual intercourse" to "fornicatress",
        "man found guilty of sexual intercourse" to "fornicator",
        "private parts" to "chastity", "head covers" to "headcover", "all aware" to "acquaint",
        "all knower" to "omniscient", "all knowing" to "omniscient",
        "most knowing" to "omniscient",
        "exalted in might" to "mighty", "all mighty" to "mighty",
        "entirely merciful" to "gracious", "most gracious" to "gracious",
        "especially merciful" to "merciful", "most merciful" to "merciful",
        "all powerful" to "competent", "all able" to "competent",
        "accepting of repentance" to "repentaccept", "acceptor of repentance" to "repentaccept",
        "oft returning" to "repentaccept",
        "give life" to "life", "bestows mercy" to "mercy",
        "take your soul" to "tak",
        "come to know" to "know", "going to know" to "know",
        "come true" to "fulfil", "come into effect" to "fulfil",
        "splits asunder" to "split", "split asunder" to "split",
        "splits open" to "split", "split open" to "split",
        "grow firm" to "thick", "becomes thick" to "thick", "led across" to "across",
        "sinful conduct" to "disobedienc", "fair seeming" to "attract",
        "seemed alike" to "similar", "seemed similar" to "similar",
        "seems suitable" to "please", "seek to hasten" to "impatient",
        "my way" to "mycause", "my cause" to "mycause",
    ).map { (phrase, key) -> phrase.split(' ') to inflection(key) }

    // Names retain their identity across clauses; ordinary repeated content
    // remains sentence-bounded (the two different burdens in 2:286).
    private val NAMED_MEANINGS = setOf(
        "moses", "noah", "abraham", "pharaoh", "mary", "jesus", "joseph", "lot", "david",
        "solomon", "jonah", "isaac", "jacob", "ishmael", "aaron", "zechariah", "john",
        "elijah", "job", "hud", "salih", "torah", "gospel", "satan", "god", "allah",
    ).map(::inflection).toSet()

    /** QAC identities: vocalization distinguishes a book from writing, or an angel from a king. */
    private val ARABIC_HEADS = mapOf(
        "مُوسَىٰ" to "moses", "نُوح" to "noah", "إِبْرَاهِيم" to "abraham",
        "فِرْعَوْن" to "pharaoh", "مَرْيَم" to "mary", "عِيسَى" to "jesus",
        "يُوسُف" to "joseph", "لُوط" to "lot", "دَاوُد" to "david",
        "سُلَيْمَٰن" to "solomon", "يُونُس" to "jonah", "إِسْحَاق" to "isaac",
        "يَعْقُوب" to "jacob", "إِسْمَاعِيل" to "ishmael", "هَٰرُون" to "aaron",
        "زَكَرِيَّا" to "zechariah", "يَحْيَىٰ" to "john", "إِلْيَاس" to "elijah",
        "أَيُّوب" to "job", "هُود" to "hud", "صَٰلِح" to "salih",
        "تَّوْرَىٰة" to "torah", "إِنجِيل" to "gospel",
        "شَيْطَٰن" to "satan", "كِتَٰب" to "book", "ٱللَّه" to "allah",
        "رَبّ" to "lord",
        "قَالَ" to "say", "ءَامَنَ" to "believe", "سَمِعَ" to "hear",
        "مَاتَ" to "die", "مَّاتَ" to "die", "قَتَلَ" to "kill", "خَلَقَ" to "create",
        "عَلِمَ" to "know", "أَكَلَ" to "eat", "كَتَبَ" to "write",
        "عَبَدَ" to "worship", "غَفَرَ" to "forgive", "حَقّ" to "truth",
        "أَرْض" to "earth", "سَمَآء" to "heaven", "مَسْجِد" to "mosque",
        "عَذَاب" to "punishment", "زَوْج" to "wife", "يَوْم" to "day",
        "كَفَرَ" to "disbelieve", "مُؤْمِن" to "believer", "عِلْم" to "knowledge",
        "رَحْمَة" to "mercy", "أَجْر" to "reward", "جَزَىٰ" to "reward", "دِين" to "religion",
        "سَقَىٰ" to "water", "لَيْل" to "night", "لَيْلَة" to "night", "مَال" to "wealth",
        "بَيْت" to "home", "دَار" to "home", "قَوْم" to "people", "سَبِيل" to "way", "صِرَٰط" to "way",
        "سَوَآء" to "soundness",
        "بَيِّنَة" to "clear", "مُّبِين" to "clear",
        "مُسْمَع" to "hear", "أَعْلَم" to "omniscient", "شَكَرَ" to "grateful",
    ).mapValues { (_, meaning) -> inflection(meaning) }

    /** Gloss variants are evidence for a head only when its Arabic lemma agrees. */
    private fun namesHead(key: String, lemma: String): Boolean = ARABIC_HEADS[lemma]?.let { head ->
        key == head || key in HEAD_GLOSS_VARIANTS[head].orEmpty()
    } ?: false

    /** An adjacent reversed phrase needs both lexical heads, not one stray matching word. */
    private fun sourceNamesHead(at: Int, gloss: List<ProseWord>, owner: List<Int>, lemmas: List<String>, arabic: List<String>): Boolean {
        val own = gloss.indices.filter { owner[it] == at }
        // Clamped QAC positions can put a noun's lemma on a neighbouring
        // preposition. A missing gloss head needs the actual Arabic word too.
        if (own.isNotEmpty() && own.all { gloss[it].added || gloss[it].text in GRAMMAR } &&
            arabic.size == lemmas.size && normalizeArabicForSearch(arabic[at]).contains(normalizeArabicForSearch(lemmas[at]))) return true
        return gloss.indices.any { i ->
            val other = owner[i]
            namesHead(gloss[i].key, lemmas[at]) && (other == at ||
                ((other == at - 1 || other == at + 1) && ARABIC_HEADS[lemmas[other]] != ARABIC_HEADS[lemmas[at]] &&
                    own.any { namesHead(gloss[it].key, lemmas[other]) }))
        }
    }

    private val HEAD_GLOSS_VARIANTS = mapOf(
        "say" to setOf("speak"), "day" to setOf("today", "period"),
        "disbelieve" to setOf("deny"), "believer" to setOf("believe"),
        "knowledge" to setOf("knowingly"), "religion" to setOf("law"),
        "wife" to setOf("spouse"), "wealth" to setOf("property"), "home" to setOf("house"),
        "people" to setOf("group", "community"), "hear" to setOf("listen"),
        "know" to setOf("evident"), "forgive" to setOf("forgiveness"), "die" to setOf("dead"),
        "write" to setOf("ordain", "decree"), "punishment" to setOf("punish"),
        "soundness" to setOf("right"),
        "clear" to setOf("evidence"),
    ).mapKeys { (head, _) -> inflection(head) }.mapValues { (_, variants) -> variants.map(::inflection).toSet() }

    /** Complements/intensity the prose can leave implicit in the matched gloss head. */
    private val GLOSS_SUPPLEMENTS = setOf(
        "one", "thing", "things", "partners", "existence", "abundantly", "away", "cause", "causing",
    )

    private const val SKIP_GLOSS: Byte = 0
    private const val SKIP_PROSE: Byte = 1
    private const val MATCH: Byte = 2

    /** Words too common to anchor on their own. */
    private val GRAMMAR = setOf(
        "a", "all", "an", "and", "are", "as", "at", "be", "been", "but", "by", "o",
        "did", "do", "does", "for", "from", "he", "her", "his", "i", "if", "in",
        "is", "it", "its", "no", "nor", "not", "of", "on", "or", "she", "so", "having",
        "that", "the", "their", "them", "these", "they", "this", "those", "to",
        "was", "we", "were", "will", "with", "you",
        "can", "could", "had", "has", "have", "may", "might", "must", "our",
        "shall", "should", "there", "then", "what", "when", "where", "which",
        "who", "whom", "whose", "would", "your", "my",
        "any", "both", "each", "every", "even", "more", "most", "oft",
        "ones", "only", "other", "others", "over", "surely", "well",
        "down", "forth", "up",
        "about", "above", "after", "afterwards", "against", "along", "among", "around",
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
class EnglishVerseAlignments(private val content: SurahContent, private val wordByWord: Boolean = false) {

    private val solved = ConcurrentHashMap<Int, EnglishAlignment>()

    /** Where each Arabic word of [ayah] ends in its English, or null. */
    fun of(ayah: Int?): FloatArray? = alignment(ayah)?.wordEnds

    /** The same cached ownership map read by the wash, tap and carried-leaf follow. */
    fun alignment(ayah: Int?): EnglishAlignment? {
        if (ayah == null) return null
        val ends = solved.computeIfAbsent(ayah) { number ->
            content.ayahs
                .firstOrNull { it.number == number }
                ?.let { verse ->
                    EnglishWordAlignment.align(
                        if (wordByWord) EnglishTypography.lyricize(
                            verse.words.map { it.translation }, verse.words.map { it.arabic },
                        ).filter { it.isNotBlank() }.joinToString(" ") else verse.translation,
                        verse.words.map { it.translation },
                        verse.words.map { it.lemma },
                        verse.words.map { it.arabic },
                    )
                }
                ?: NONE
        }
        return ends.takeIf { it !== NONE }
    }

    private companion object {
        /** Solved, and there is no alignment — distinct from "not solved". */
        val NONE = EnglishAlignment(FloatArray(0), emptyList())
    }
}
