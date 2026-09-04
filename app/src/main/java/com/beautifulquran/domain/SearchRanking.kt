package com.beautifulquran.domain

import kotlin.math.sqrt

/** One QSAC concept and the ayahs assigned to it in the offline search asset. */
data class SearchConcept(
    val name: String,
    val primaryTerms: List<String>,
    val secondaryTerms: List<String>,
    val category: String,
    val domain: String,
    val ayahKeys: IntArray,
)

/** A grounded Quran-vocabulary term reached through the offline thesaurus. */
data class RelatedSearchTerm(val text: String, val distance: Int)

/** A user query after recognizing an enclosing pair of exact-search quotes. */
data class ParsedSearchQuery(val text: String, val exactOnly: Boolean)

/** Double quotes around the whole query disable spelling and semantic expansion. */
fun parseSearchQuery(query: String): ParsedSearchQuery {
    val trimmed = query.trim()
    val quoted = trimmed.length >= 2 && (
        trimmed.first() == '"' && trimmed.last() == '"' ||
            trimmed.first() == '“' && trimmed.last() == '”'
        )
    return ParsedSearchQuery(
        text = (if (quoted) trimmed.substring(1, trimmed.lastIndex) else trimmed).trim(),
        exactOnly = quoted,
    )
}

private val searchSeparator = Regex("[^\\p{L}\\p{N}]+")
private val queryFillers = setOf(
    "a", "an", "about", "and", "find", "for", "from", "in", "me", "of", "on",
    "quran", "regarding", "related", "show", "the", "to", "verse", "verses", "with",
)

private fun canonicalWords(text: String): List<String> = text
    .lowercase()
    .split(searchSeparator)
    .filter(String::isNotEmpty)

private fun stem(word: String): String = when {
    word.length > 6 && word.endsWith("ness") -> word.dropLast(4)
    word.length > 5 && word.endsWith("ies") -> word.dropLast(3) + "y"
    word.length > 4 && word.endsWith("s") -> word.dropLast(1)
    else -> word
}

/**
 * Relevance of [text] to [query]. Exact phrases lead, reordered content words
 * follow, then substrings and a one-edit spelling match.
 */
fun searchTextRelevance(
    text: String,
    query: ParsedSearchQuery,
    allowFuzzy: Boolean = true,
): Int = searchLowerTextRelevance(
    text.lowercase(),
    query.text.lowercase(),
    query.exactOnly,
    allowFuzzy,
)

/** Avoids repeated case-folding for index fields and parsed queries already in lowercase. */
internal fun searchLowerTextRelevance(
    target: String,
    needle: String,
    exactOnly: Boolean,
    allowFuzzy: Boolean,
): Int {
    if (target.isEmpty() || needle.isEmpty()) return 0
    if (target == needle) return 3_200
    if (containsBounded(target, needle)) return 3_000
    if (exactOnly) {
        val phrase = canonicalWords(target).joinToString(" ")
        val canonicalNeedle = canonicalWords(needle).joinToString(" ")
        return if (canonicalNeedle.isNotEmpty() && containsBounded(phrase, canonicalNeedle)) 3_000 else 0
    }
    if (target.contains(needle)) return 2_200
    val singleWord = needle.all(Char::isLetterOrDigit)
    if (allowFuzzy && singleWord) {
        return if (fuzzyWordContains(target, needle)) 1_600 else 0
    }
    if (singleWord && needle !in queryFillers) return 0

    val queryWords = canonicalWords(needle)
    val content = queryWords.filterNot(queryFillers::contains).ifEmpty { queryWords }
    if (content.size > 1 || content.size < queryWords.size) {
        val words = canonicalWords(target)
        val stems = words.mapTo(HashSet(words.size), ::stem)
        if (content.all { stem(it) in stems }) return 2_600
    }
    return 0
}

private fun containsBounded(text: String, needle: String): Boolean {
    var at = text.indexOf(needle)
    while (at >= 0) {
        val end = at + needle.length
        if ((at == 0 || !text[at - 1].isLetterOrDigit()) &&
            (end == text.length || !text[end].isLetterOrDigit())
        ) {
            return true
        }
        at = text.indexOf(needle, at + 1)
    }
    return false
}

/** Score a concept name/vocabulary match below literal text but above broad hierarchy matches. */
fun conceptRelevance(
    concept: SearchConcept,
    query: ParsedSearchQuery,
    allowFuzzy: Boolean = true,
): Int {
    if (query.exactOnly || normalizeArabicForSearch(query.text).isNotEmpty()) return 0
    fun score(text: String) = searchTextRelevance(text, query, allowFuzzy)
    fun best(terms: Iterable<String>) = terms.maxOfOrNull(::score) ?: 0
    val relevance = maxOf(
        score(concept.name) - 1_400,
        best(concept.primaryTerms) - 1_400,
        best(concept.secondaryTerms) - 1_500,
        score(concept.category) - 1_900,
        score(concept.domain) - 2_100,
    ).coerceAtLeast(0)
    if (relevance == 0) return 0
    return relevance + if (concept.ayahKeys.isEmpty()) {
        0
    } else {
        (800 / sqrt(concept.ayahKeys.size.toDouble())).toInt().coerceAtMost(150)
    }
}

/** The one meaningful English word eligible for thesaurus expansion. */
fun thesaurusLookupKey(query: ParsedSearchQuery): String? {
    if (query.exactOnly || normalizeArabicForSearch(query.text).isNotEmpty()) return null
    val words = canonicalWords(query.text)
    return words.filterNot(queryFillers::contains).ifEmpty { words }.singleOrNull()
}
