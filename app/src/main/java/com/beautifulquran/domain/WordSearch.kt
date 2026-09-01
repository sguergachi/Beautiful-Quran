package com.beautifulquran.domain

import com.beautifulquran.data.model.SurahWordSearchSection
import com.beautifulquran.data.model.WordSearchHit

/** Soft cap so a very common English gloss cannot flood the cover sheet. */
const val WORD_SEARCH_MAX_HITS = 400

/** Minimum trimmed query length before Quran-wide word search runs. */
const val WORD_SEARCH_MIN_QUERY_LENGTH = 2

/** How many ayah hits to show per surah before the expand line. */
const val WORD_SEARCH_PREVIEW_LIMIT = 3

/**
 * The ayah- and surah-level text every word of one ayah shares.
 *
 * Held **once per ayah** and referenced by each word's [WordSearchIndexEntry].
 * The index covers 77,429 word rows across only 6,236 ayahs, so storing these
 * four strings per *word* — which is what `Cursor.getString()` hands back, a
 * fresh instance per row — duplicated ~31 M characters and retained tens of
 * megabytes for the life of the process. One instance per ayah cuts that to
 * ~2.3 M characters.
 */
data class WordSearchAyahContext(
    val ayahText: String,
    val ayahTranslation: String,
    val surahNameTransliteration: String,
    val surahNameArabic: String,
)

/**
 * Lightweight word row used to build (and unit-test) Quran-wide search
 * without needing the Android SQLite wrapper.
 *
 * Per-word strings are stored inline; everything ayah-wide lives behind
 * [context] so it is not copied 12× per verse.
 */
data class WordSearchIndexEntry(
    val surahId: Int,
    val ayahNumber: Int,
    val position: Int,
    val arabic: String,
    val arabicNorm: String,
    val translation: String,
    val translationLower: String,
    val transliteration: String,
    val transliterationLower: String,
    /** Shared with every other word of this ayah — see [WordSearchAyahContext]. */
    val context: WordSearchAyahContext,
) {
    val ayahText: String get() = context.ayahText
    val ayahTranslation: String get() = context.ayahTranslation
    val surahNameTransliteration: String get() = context.surahNameTransliteration
    val surahNameArabic: String get() = context.surahNameArabic
}

fun WordSearchIndexEntry.toHit(): WordSearchHit =
    WordSearchHit(
        surahId = surahId,
        ayahNumber = ayahNumber,
        position = position,
        arabic = arabic,
        translation = translation,
        transliteration = transliteration,
        ayahText = ayahText,
        ayahTranslation = ayahTranslation,
        surahNameTransliteration = surahNameTransliteration,
        surahNameArabic = surahNameArabic,
    )

/**
 * Returns true when [query] is long enough to run word search. Callers that
 * also treat `surah:ayah` as a jump reference should skip word search for
 * those queries themselves.
 */
fun isWordSearchQuery(query: String): Boolean {
    val trimmed = query.trim()
    return trimmed.length >= WORD_SEARCH_MIN_QUERY_LENGTH
}

/**
 * Scans [index] for Arabic (diacritic-insensitive), English gloss, or
 * transliteration substring matches. When none exist, a one-edit fuzzy pass
 * catches a misspelling without diluting exact results. Results stay in
 * Quranic order.
 *
 * When the SI ayah translation cannot show the matched English, the hit's
 * [WordSearchHit.ayahTranslation] is the same-ayah word-gloss line instead,
 * so the cover snippet always has the matched term in context.
 */
fun matchWordSearch(
    index: List<WordSearchIndexEntry>,
    query: String,
    maxHits: Int = WORD_SEARCH_MAX_HITS,
): List<WordSearchHit> {
    val trimmed = query.trim()
    if (!isWordSearchQuery(trimmed)) return emptyList()
    val arabicNorm = normalizeArabicForSearch(trimmed)
    val lower = trimmed.lowercase()
    fun scan(fuzzy: Boolean): List<WordSearchHit> {
        val out = ArrayList<WordSearchHit>(minOf(64, maxHits))
        for (i in index.indices) {
            val entry = index[i]
            val hit = if (fuzzy) {
                fuzzyWordContains(entry.arabicNorm, arabicNorm) ||
                    fuzzyWordContains(entry.translationLower, lower) ||
                    fuzzyWordContains(entry.transliterationLower, lower)
            } else when {
            arabicNorm.length >= WORD_SEARCH_MIN_QUERY_LENGTH &&
                entry.arabicNorm.contains(arabicNorm) -> true
            entry.translationLower.contains(lower) -> true
            entry.transliterationLower.contains(lower) -> true
            else -> false
            }
            if (!hit) continue
            val base = entry.toHit()
            // Word glosses and SI ayah lines often disagree ("a resting place" vs
            // "a bed"). Prefer the gloss line only when it can host the highlight.
            val display = snippetDisplayText(entry, index, i, trimmed)
            out.add(if (display == entry.ayahTranslation) base else base.copy(ayahTranslation = display))
            if (out.size >= maxHits) break
        }
        return out
    }
    return scan(fuzzy = false).ifEmpty { scan(fuzzy = true) }
}

/** True when one whole word in [text] is at most one edit from [query]. */
fun fuzzyWordContains(text: String, query: String): Boolean {
    if (query.length < 4 || query.any { !it.isLetterOrDigit() }) return false
    var start = -1
    for (i in 0..text.length) {
        if (i < text.length && text[i].isLetterOrDigit()) {
            if (start < 0) start = i
        } else if (start >= 0) {
            if (isWithinOneEdit(text, start, i, query)) return true
            start = -1
        }
    }
    return false
}

private fun isWithinOneEdit(text: String, start: Int, end: Int, query: String): Boolean {
    val wordLength = end - start
    if (kotlin.math.abs(wordLength - query.length) > 1) return false
    var wordAt = start
    var queryAt = 0
    var edits = 0
    while (wordAt < end && queryAt < query.length) {
        if (text[wordAt] == query[queryAt]) {
            wordAt++
            queryAt++
        } else {
            edits++
            if (edits > 1) break
            when {
                wordLength > query.length -> wordAt++
                wordLength < query.length -> queryAt++
                else -> {
                    wordAt++
                    queryAt++
                }
            }
        }
    }
    edits += end - wordAt + query.length - queryAt
    if (edits <= 1) return true

    if (wordLength != query.length) return false
    val first = (0 until wordLength).firstOrNull { text[start + it] != query[it] } ?: return true
    if (first + 1 >= wordLength ||
        text[start + first] != query[first + 1] ||
        text[start + first + 1] != query[first]
    ) {
        return false
    }
    return (first + 2 until wordLength).all { text[start + it] == query[it] }
}

/**
 * SI ayah text when it can show the match; otherwise the same-ayah word-gloss
 * line when that can. Falls back to SI when neither hosts a highlight.
 */
internal fun snippetDisplayText(
    entry: WordSearchIndexEntry,
    index: List<WordSearchIndexEntry>,
    at: Int,
    query: String,
): String {
    if (highlightNeedle(entry.ayahTranslation, query, entry.translation) != null) {
        return entry.ayahTranslation
    }
    val glossLine = sameAyahGlossLine(index, at)
    if (highlightNeedle(glossLine, query, entry.translation) != null) return glossLine
    return entry.ayahTranslation
}

/** Space-joined English glosses for every word of the same ayah as [at]. */
internal fun sameAyahGlossLine(index: List<WordSearchIndexEntry>, at: Int): String {
    if (at !in index.indices) return ""
    val anchor = index[at]
    var lo = at
    while (
        lo > 0 &&
        index[lo - 1].surahId == anchor.surahId &&
        index[lo - 1].ayahNumber == anchor.ayahNumber
    ) {
        lo--
    }
    var hi = at
    while (
        hi + 1 < index.size &&
        index[hi + 1].surahId == anchor.surahId &&
        index[hi + 1].ayahNumber == anchor.ayahNumber
    ) {
        hi++
    }
    return (lo..hi).joinToString(" ") { index[it].translation }
}

/**
 * Groups flat hits into surah sections. Collapsed sections keep the first
 * [previewLimit] hits; expanded sections (ids in [expandedSurahIds]) keep all.
 */
fun sectionWordSearchHits(
    hits: List<WordSearchHit>,
    expandedSurahIds: Set<Int>,
    previewLimit: Int = WORD_SEARCH_PREVIEW_LIMIT,
): List<SurahWordSearchSection> {
    if (hits.isEmpty()) return emptyList()
    val grouped = linkedMapOf<Int, MutableList<WordSearchHit>>()
    for (hit in hits) {
        grouped.getOrPut(hit.surahId) { mutableListOf() }.add(hit)
    }
    return grouped.map { (surahId, surahHits) ->
        val expanded = surahId in expandedSurahIds
        val visible = if (expanded || surahHits.size <= previewLimit) {
            surahHits
        } else {
            surahHits.take(previewLimit)
        }
        val first = surahHits.first()
        SurahWordSearchSection(
            surahId = surahId,
            surahNameTransliteration = first.surahNameTransliteration,
            surahNameArabic = first.surahNameArabic,
            hits = visible,
            totalCount = surahHits.size,
            expanded = expanded,
        )
    }
}

/**
 * Builds spans for an ayah, highlighting the whitespace-token at 1-based
 * [position]. Falls back to highlighting every exact surface-form match of
 * [fallbackWord] when the token split does not line up.
 */
fun ayahHighlightSpans(
    ayahText: String,
    position: Int,
    fallbackWord: String,
): List<AyahTextSpan> {
    if (ayahText.isEmpty()) return emptyList()
    val tokens = ayahText.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (position in 1..tokens.size) {
        val spans = ArrayList<AyahTextSpan>(tokens.size * 2)
        tokens.forEachIndexed { index, token ->
            if (index > 0) spans.add(AyahTextSpan(" ", highlighted = false))
            spans.add(AyahTextSpan(token, highlighted = index + 1 == position))
        }
        return spans
    }
    if (fallbackWord.isEmpty()) {
        return listOf(AyahTextSpan(ayahText, highlighted = false))
    }
    val spans = ArrayList<AyahTextSpan>()
    var start = 0
    var i = ayahText.indexOf(fallbackWord)
    if (i < 0) return listOf(AyahTextSpan(ayahText, highlighted = false))
    while (i >= 0) {
        if (i > start) spans.add(AyahTextSpan(ayahText.substring(start, i), highlighted = false))
        spans.add(AyahTextSpan(fallbackWord, highlighted = true))
        start = i + fallbackWord.length
        i = ayahText.indexOf(fallbackWord, start)
    }
    if (start < ayahText.length) {
        spans.add(AyahTextSpan(ayahText.substring(start), highlighted = false))
    }
    return spans
}

/** How many whole words of context to keep on each side of a search hit. */
const val SNIPPET_WORDS_BEFORE = 8
const val SNIPPET_WORDS_AFTER = 14

/**
 * Builds spans for an English search snippet: a short window of text centered
 * on the match (query or word gloss), with the match highlighted. Used by the
 * cover-sheet word results so the gold term is always on-screen.
 */
fun englishTranslationHighlightSpans(
    ayahTranslation: String,
    query: String,
    wordGloss: String,
): List<AyahTextSpan> {
    if (ayahTranslation.isEmpty()) return emptyList()
    val needle = highlightNeedle(ayahTranslation, query.trim(), wordGloss.trim())
    val snippet = windowAroundMatch(
        text = ayahTranslation,
        needle = needle,
        wordsBefore = SNIPPET_WORDS_BEFORE,
        wordsAfter = SNIPPET_WORDS_AFTER,
    )
    if (needle == null) return listOf(AyahTextSpan(snippet, highlighted = false))
    return highlightAllOccurrences(snippet, needle)
}

/**
 * Trims [text] to roughly [wordsBefore]…[wordsAfter] words around the first
 * occurrence of [needle], adding an ellipsis when the ends were cut.
 * When [needle] is null or missing, returns [text] unchanged.
 */
internal fun windowAroundMatch(
    text: String,
    needle: String?,
    wordsBefore: Int = SNIPPET_WORDS_BEFORE,
    wordsAfter: Int = SNIPPET_WORDS_AFTER,
): String {
    if (needle.isNullOrEmpty() || text.isEmpty()) return text
    val matchStart = text.indexOf(needle, ignoreCase = true)
    if (matchStart < 0) return text
    val matchEnd = matchStart + needle.length
    val words = Regex("\\S+").findAll(text).toList()
    if (words.isEmpty()) return text
    val matchWord = words.indexOfFirst { mr ->
        matchStart < mr.range.last + 1 && matchEnd > mr.range.first
    }.let { if (it < 0) 0 else it }
    val from = (matchWord - wordsBefore).coerceAtLeast(0)
    val to = (matchWord + wordsAfter).coerceAtMost(words.lastIndex)
    val startChar = words[from].range.first
    val endChar = words[to].range.last + 1
    val core = text.substring(startChar, endChar).trim()
    val prefix = if (from > 0) "…" else ""
    val suffix = if (to < words.lastIndex) "…" else ""
    return prefix + core + suffix
}

/**
 * Prefers the typed query when it appears in the translation; otherwise the
 * word gloss. Returns null when neither can be found (case-insensitive).
 */
internal fun highlightNeedle(
    haystack: String,
    query: String,
    wordGloss: String,
): String? {
    if (query.isNotEmpty() && haystack.contains(query, ignoreCase = true)) {
        return query
    }
    if (wordGloss.isNotEmpty() && haystack.contains(wordGloss, ignoreCase = true)) {
        return wordGloss
    }
    // Gloss rows are often "(is) the Merciful" while the ayah says "the Merciful" —
    // try the longest whitespace token from the gloss that still appears.
    val tokens = wordGloss
        .split(Regex("[\\s,;:]+"))
        .map { it.trim().trim('(', ')', '[', ']', '"', '\'') }
        .filter { it.length >= 3 }
        .sortedByDescending { it.length }
    for (token in tokens) {
        if (haystack.contains(token, ignoreCase = true)) return token
    }
    return null
}

private fun highlightAllOccurrences(text: String, needle: String): List<AyahTextSpan> {
    val spans = ArrayList<AyahTextSpan>()
    var start = 0
    var i = text.indexOf(needle, ignoreCase = true)
    if (i < 0) return listOf(AyahTextSpan(text, highlighted = false))
    while (i >= 0) {
        if (i > start) spans.add(AyahTextSpan(text.substring(start, i), highlighted = false))
        val end = i + needle.length
        spans.add(AyahTextSpan(text.substring(i, end), highlighted = true))
        start = end
        i = text.indexOf(needle, start, ignoreCase = true)
    }
    if (start < text.length) {
        spans.add(AyahTextSpan(text.substring(start), highlighted = false))
    }
    return spans
}

data class AyahTextSpan(val text: String, val highlighted: Boolean)
