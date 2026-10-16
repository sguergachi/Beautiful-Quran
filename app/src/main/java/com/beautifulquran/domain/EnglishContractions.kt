package com.beautifulquran.domain

/** Search-only expansion with source ranges; displayed Quran translations are never rewritten. */
internal object EnglishContractions {
    private val wordPattern = Regex(
        "(?:'(?=tis\\b|twas\\b|twere\\b))?[\\p{L}\\p{N}]+(?:'[\\p{L}\\p{N}]+)*",
        RegexOption.IGNORE_CASE,
    )
    private val hosts = setOf(
        "i", "you", "he", "she", "it", "we", "they", "that", "there", "here",
        "who", "what", "where", "when", "why", "how", "y'all",
    )
    private val negatives = setOf(
        "am", "is", "are", "was", "were", "do", "does", "did", "have", "has", "had",
        "could", "would", "should", "must", "may", "might", "need", "dare", "ought", "used",
    )
    private val questions = setOf("who", "what", "where", "when", "why", "how")
    private val special = mapOf(
        "can't" to "can not", "cannot" to "can not", "won't" to "will not",
        "shan't" to "shall not", "let's" to "let us", "y'all" to "you all",
        "ma'am" to "madam", "o'clock" to "of the clock", "'tis" to "it is",
        "'twas" to "it was", "'twere" to "it were",
    )
    private data class Token(val forms: List<String>, val range: IntRange)
    private data class Match(val range: IntRange, val complete: Boolean)

    private fun expand(word: String): List<List<String>> {
        special[word]?.let { return it.split(' ').map(::listOf) }
        if (word == "ain't") return listOf(listOf("am", "is", "are", "has", "have"), listOf("not"))
        if (word.endsWith("n't") && word.dropLast(3) in negatives) {
            return listOf(listOf(word.dropLast(3)), listOf("not"))
        }
        val stem = word.substringBeforeLast('\'', "")
        val suffix = word.substringAfterLast('\'')
        if (suffix == "ve" && stem.endsWith("'d") && stem.dropLast(2) in hosts) {
            return expand(stem.dropLast(2)) + listOf(listOf("would"), listOf("have"))
        }
        val tail = when {
            suffix == "ve" && (stem in hosts || stem in setOf("could", "would", "should", "might", "must") ||
                stem.endsWith("n't") || stem.endsWith("'ll")) -> listOf("have")
            stem !in hosts -> null
            suffix == "m" && stem == "i" -> listOf("am")
            suffix == "re" -> listOf("are")
            suffix == "ll" -> if (stem in setOf("i", "we")) listOf("will", "shall") else listOf("will")
            suffix == "d" -> listOf("had", "would") + if (stem in questions) listOf("did") else emptyList()
            suffix == "s" -> listOf("is", "has") + if (stem in questions) listOf("does") else emptyList()
            else -> null
        }
        return if (tail == null) listOf(listOf(word)) else expand(stem) + listOf(tail)
    }

    private fun tokens(text: String): List<Token> = wordPattern.findAll(EnglishTypography.fold(text))
        .flatMap { match -> expand(match.value.lowercase()).map { Token(it, match.range) } }.toList()

    /** A cheap gate keeps ordinary searches off the expansion path. */
    fun mayExpand(text: String): Boolean = '\'' in text || '’' in text || text.contains("cannot", ignoreCase = true)

    /** Candidate vocabulary for Android's cold search, including ambiguous auxiliaries. */
    fun words(text: String): List<String> = tokens(text).flatMap(Token::forms)

    /** Alternative auxiliaries remain one word slot rather than requiring both meanings. */
    fun forms(text: String): List<List<String>> = tokens(text).map(Token::forms)

    /** Complete equivalents lead the user's unfinished final word, as in literal search. */
    fun relevance(text: String, query: String): Int =
        matches(text, query).maxOfOrNull { if (it.complete) 3_000 else 2_200 } ?: 0

    /** Equivalent phrases and final-word prefixes retain the original glyphs and brackets. */
    fun ranges(text: String, query: String): List<IntRange> = matches(text, query).map(Match::range).distinct()

    private fun matches(text: String, query: String): List<Match> {
        if (!mayExpand(text) && !mayExpand(query)) return emptyList()
        val needle = tokens(query)
        val haystack = tokens(text)
        if (needle.isEmpty() || needle.size > haystack.size) return emptyList()
        // An expanded suffix is complete: "doesn't" must never prefix "does nothing".
        val prefix = needle.last().forms.singleOrNull()?.takeIf {
            it == EnglishTypography.fold(query).substring(needle.last().range).lowercase()
        }
        return (0..haystack.size - needle.size).mapNotNull { at ->
            if ((0 until needle.lastIndex).any { i -> needle[i].forms.none { it in haystack[at + i].forms } }) {
                return@mapNotNull null
            }
            val ending = haystack[at + needle.lastIndex].forms
            val complete = needle.last().forms.any { it in ending }
            if (!complete && (prefix == null || ending.none { it.startsWith(prefix) })) return@mapNotNull null
            var start = haystack[at].range.first
            var end = haystack[at + needle.lastIndex].range.last + 1
            while (start > 0 && text[start - 1] in "([") start--
            while (end < text.length && text[end] in ")]") end++
            Match(start until end, complete)
        }.distinct()
    }
}
