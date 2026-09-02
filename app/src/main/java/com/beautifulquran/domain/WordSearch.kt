package com.beautifulquran.domain

import com.beautifulquran.data.model.SurahWordSearchSection
import com.beautifulquran.data.model.WordSearchHit

/** Soft cap so a very common English gloss cannot flood the cover sheet. */
const val WORD_SEARCH_MAX_HITS = 400

/** Minimum trimmed query length before Quran-wide word search runs. */
const val WORD_SEARCH_MIN_QUERY_LENGTH = 2

/** How many ayah hits to show per surah before the expand line. */
const val WORD_SEARCH_PREVIEW_LIMIT = 3

/** Keeps visible concept evidence ahead of any bounded corroboration bonus. */
private const val VISIBLE_CONCEPT_EVIDENCE_BONUS = 300

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
    val root: String = "",
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
    return parseSearchQuery(query).text.length >= WORD_SEARCH_MIN_QUERY_LENGTH
}

/**
 * Ranks Arabic, English and transliteration matches, related QAC-root words,
 * and QSAC [concepts]. Enclosing the whole query in double quotes keeps only
 * literal whole-word/phrase matches.
 *
 * When the SI ayah translation cannot show the matched English, the hit's
 * [WordSearchHit.ayahTranslation] is the same-ayah word-gloss line instead,
 * so the cover snippet always has the matched term in context.
 */
fun matchWordSearch(
    index: List<WordSearchIndexEntry>,
    query: String,
    maxHits: Int = WORD_SEARCH_MAX_HITS,
    concepts: List<SearchConcept> = emptyList(),
    thesaurus: Map<String, List<RelatedSearchTerm>> = emptyMap(),
    checkCancelled: () -> Unit = {},
): List<WordSearchHit> {
    val parsed = parseSearchQuery(query)
    if (parsed.text.length < WORD_SEARCH_MIN_QUERY_LENGTH || maxHits <= 0) return emptyList()
    data class RankedHit(
        val key: Int,
        val indexAt: Int,
        val position: Int,
        val score: Int,
        val matchLabel: String? = null,
        val matchTerms: List<String> = emptyList(),
        val matchReason: String = "Text match",
    )

    val ranked = HashMap<Int, RankedHit>(512)
    val firstIndex = HashMap<Int, Int>(7_000)
    val matchedRoots = HashSet<String>()
    fun add(
        indexAt: Int,
        position: Int,
        score: Int,
        label: String? = null,
        terms: List<String> = emptyList(),
        reason: String = "Text match",
    ) {
        if (score <= 0) return
        val entry = index[indexAt]
        val key = entry.surahId * 1_000 + entry.ayahNumber
        val current = ranked[key]
        if (current == null || score > current.score ||
            (score == current.score && position > 0 && current.position == 0)
        ) {
            ranked[key] = RankedHit(key, indexAt, position, score, label, terms, reason)
        }
    }

    fun scanOriginal(allowFuzzy: Boolean) {
        val arabic = normalizeArabicForSearch(parsed.text)
        val latin = if (arabic.isEmpty()) parsed else parsed.copy(text = "")
        for (i in index.indices) {
            if ((i and 0xfff) == 0) checkCancelled()
            val entry = index[i]
            val score = maxOf(
                if (arabic.isEmpty()) 0 else searchTextRelevance(
                    entry.arabicNorm,
                    parsed.copy(text = arabic),
                    allowFuzzy,
                ),
                searchTextRelevance(entry.translationLower, latin, allowFuzzy),
                searchTextRelevance(entry.transliterationLower, latin, allowFuzzy),
            )
            add(
                i,
                entry.position,
                score,
                terms = if (allowFuzzy && score > 0) {
                    listOfNotNull(
                        if (arabic.isNotEmpty()) {
                            fuzzyWordMatch(entry.arabicNorm, arabic)
                        } else {
                            fuzzyWordMatch(entry.translationLower, latin.text)
                                ?: fuzzyWordMatch(entry.transliterationLower, latin.text)
                        },
                    )
                } else {
                    emptyList()
                },
                reason = if (allowFuzzy) "Spelling match" else "Text match",
            )
            if (!parsed.exactOnly && score > 0 && entry.root.isNotEmpty()) matchedRoots += entry.root
            firstIndex.putIfAbsent(entry.surahId * 1_000 + entry.ayahNumber, i)
        }
        var at = 0
        while (at < index.size) {
            checkCancelled()
            val anchor = index[at]
            var end = at + 1
            while (end < index.size && index[end].surahId == anchor.surahId &&
                index[end].ayahNumber == anchor.ayahNumber
            ) {
                end++
            }
            val score = maxOf(
                if (arabic.isEmpty()) 0 else searchTextRelevance(
                    normalizeArabicForSearch(anchor.ayahText),
                    parsed.copy(text = arabic),
                    allowFuzzy,
                ),
                searchTextRelevance(anchor.ayahTranslation, latin, allowFuzzy),
                if (parsed.text.any(Char::isWhitespace)) {
                    searchTextRelevance(
                        (at until end).joinToString(" ") { index[it].translation },
                        latin,
                        allowFuzzy,
                    )
                } else {
                    0
                },
            )
            add(
                at,
                position = 0,
                score = score,
                terms = if (allowFuzzy && score > 0) {
                    listOfNotNull(
                        if (arabic.isNotEmpty()) {
                            fuzzyWordMatch(normalizeArabicForSearch(anchor.ayahText), arabic)
                        } else {
                            fuzzyWordMatch(anchor.ayahTranslation.lowercase(), latin.text)
                        },
                    )
                } else {
                    emptyList()
                },
                reason = if (allowFuzzy) "Spelling match" else "Text match",
            )
            at = end
        }
    }

    fun scanConcepts(allowFuzzy: Boolean) {
        if (parsed.exactOnly || concepts.isEmpty()) return
        data class SemanticRank(
            val best: Int,
            val bonus: Int,
            val label: String,
            val correction: String?,
        ) {
            val total: Int get() = best + bonus
        }
        val semantic = HashMap<Int, SemanticRank>()
        for (concept in concepts) {
            checkCancelled()
            val score = conceptRelevance(concept, parsed, allowFuzzy)
            if (score <= 0) continue
            val correction = if (allowFuzzy) {
                sequenceOf(concept.name)
                    .plus(concept.primaryTerms)
                    .plus(concept.secondaryTerms)
                    .mapNotNull { fuzzyWordMatch(it.lowercase(), parsed.text.lowercase()) }
                    .firstOrNull()
            } else {
                null
            }
            val evidenceQuery = ParsedSearchQuery(correction ?: parsed.text, exactOnly = false)
            for (key in concept.ayahKeys) {
                val hasVisibleEvidence = firstIndex[key]?.let { at ->
                    maxOf(
                        searchTextRelevance(index[at].ayahTranslation, evidenceQuery, allowFuzzy = false),
                        searchTextRelevance(sameAyahGlossLine(index, at), evidenceQuery, allowFuzzy = false),
                    ) > 0
                } == true
                val groundedScore = score + if (hasVisibleEvidence) {
                    VISIBLE_CONCEPT_EVIDENCE_BONUS
                } else {
                    0
                }
                val current = semantic[key]
                semantic[key] = if (current == null) {
                    SemanticRank(groundedScore, bonus = 0, concept.name, correction)
                } else {
                    SemanticRank(
                        best = maxOf(current.best, groundedScore),
                        bonus = (current.bonus + minOf(current.best, groundedScore) / 5)
                            .coerceAtMost(250),
                        label = if (groundedScore > current.best) concept.name else current.label,
                        correction = if (groundedScore > current.best) correction else current.correction,
                    )
                }
            }
        }
        for ((key, match) in semantic) {
            firstIndex[key]?.let {
                add(
                    it,
                    position = 0,
                    score = match.total,
                    label = match.label,
                    terms = listOfNotNull(match.correction),
                    reason = "Concept · ${match.label}",
                )
            }
        }
    }

    data class RelatedMatch(val score: Int, val terms: List<String>)

    fun bestRelated(text: String, related: List<RelatedSearchTerm>): RelatedMatch? {
        var bestScore = 0
        var bestTerm: String? = null
        val terms = ArrayList<String>(related.size)
        for (candidate in related) {
            val score = searchTextRelevance(
                text,
                ParsedSearchQuery(candidate.text, exactOnly = false),
                allowFuzzy = false,
            ) - (1_100 + candidate.distance * 150)
            if (score > bestScore) {
                bestScore = score
                bestTerm = candidate.text
            }
            if (score > 0) terms += candidate.text
        }
        return bestTerm?.let { term ->
            RelatedMatch(bestScore, listOf(term) + terms.filterNot { it == term })
        }
    }

    fun scanRelated(related: List<RelatedSearchTerm>) {
        if (related.isEmpty()) return
        for (i in index.indices) {
            if ((i and 0xfff) == 0) checkCancelled()
            val entry = index[i]
            val match = bestRelated(entry.translationLower, related) ?: continue
            add(
                i,
                entry.position,
                match.score,
                terms = match.terms,
                reason = "Related · ${match.terms.first()}",
            )
        }
        var at = 0
        while (at < index.size) {
            checkCancelled()
            val anchor = index[at]
            var end = at + 1
            while (end < index.size && index[end].surahId == anchor.surahId &&
                index[end].ayahNumber == anchor.ayahNumber
            ) {
                end++
            }
            val translation = bestRelated(anchor.ayahTranslation, related)
            val gloss = bestRelated(
                (at until end).joinToString(" ") { index[it].translation },
                related,
            )
            val match = listOfNotNull(translation, gloss).maxByOrNull { it.score }
            if (match != null) {
                add(
                    at,
                    position = 0,
                    score = match.score,
                    terms = match.terms,
                    reason = "Related · ${match.terms.first()}",
                )
            }
            at = end
        }
    }

    scanOriginal(allowFuzzy = false)
    scanConcepts(allowFuzzy = false)
    if (ranked.size < 3) {
        thesaurusLookupKey(parsed)?.let { key ->
            scanRelated(thesaurus[key].orEmpty())
        }
    }
    if (ranked.isEmpty()) {
        scanOriginal(allowFuzzy = true)
        scanConcepts(allowFuzzy = true)
    }
    if (!parsed.exactOnly && matchedRoots.isNotEmpty()) {
        for (i in index.indices) {
            if ((i and 0xfff) == 0) checkCancelled()
            if (index[i].root in matchedRoots) {
                add(
                    i,
                    index[i].position,
                    score = 1_450,
                    terms = listOf(index[i].translation),
                    reason = "Same Arabic root",
                )
            }
        }
    }

    return ranked.values
        .sortedWith(
            compareByDescending<RankedHit> { it.score }
                .thenBy { it.key }
                .thenBy { it.position },
        )
        .take(maxHits)
        .map { match ->
            val entry = index[match.indexAt]
            val base = entry.toHit().copy(
                matchLabel = match.matchLabel,
                matchTerms = match.matchTerms,
                matchReason = match.matchReason,
            )
            if (match.position > 0) {
                val display = snippetDisplayText(
                    entry,
                    index,
                    match.indexAt,
                    parsed.text,
                    match.matchTerms,
                )
                if (display == entry.ayahTranslation) base else base.copy(ayahTranslation = display)
            } else {
                base.copy(position = 0, arabic = "", translation = "", transliteration = "")
            }
        }
}

/** The first whole word in [text] at most one edit from [query]. */
fun fuzzyWordMatch(text: String, query: String): String? {
    if (query.length < 4 || query.any { !it.isLetterOrDigit() }) return null
    var start = -1
    for (i in 0..text.length) {
        if (i < text.length && text[i].isLetterOrDigit()) {
            if (start < 0) start = i
        } else if (start >= 0) {
            if (isWithinOneEdit(text, start, i, query)) return text.substring(start, i)
            start = -1
        }
    }
    return null
}

/** True when one whole word in [text] is at most one edit from [query]. */
fun fuzzyWordContains(text: String, query: String): Boolean = fuzzyWordMatch(text, query) != null

/** Corrected vocabulary term shown only when spelling fallback won. */
fun spellingCorrection(hits: Iterable<WordSearchHit>): String? = hits.firstNotNullOfOrNull { hit ->
    hit.matchTerms.firstOrNull().takeIf {
        hit.matchReason == "Spelling match" || hit.matchReason.startsWith("Concept ·")
    }
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
    semanticTerms: List<String> = emptyList(),
): String {
    if (
        highlightNeedles(
            entry.ayahTranslation,
            query,
            entry.translation,
            semanticTerms = semanticTerms,
        ).isNotEmpty()
    ) {
        return entry.ayahTranslation
    }
    val glossLine = sameAyahGlossLine(index, at)
    if (
        highlightNeedles(
            glossLine,
            query,
            entry.translation,
            semanticTerms = semanticTerms,
        ).isNotEmpty()
    ) {
        return glossLine
    }
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
    semanticLabel: String = "",
    semanticTerms: List<String> = emptyList(),
): List<AyahTextSpan> {
    if (ayahTranslation.isEmpty()) return emptyList()
    val needles = highlightNeedles(
        ayahTranslation,
        query.trim(),
        wordGloss.trim(),
        semanticLabel.trim(),
        semanticTerms,
    )
    val snippet = windowAroundMatch(
        text = ayahTranslation,
        needle = needles.firstOrNull(),
        wordsBefore = SNIPPET_WORDS_BEFORE,
        wordsAfter = SNIPPET_WORDS_AFTER,
    )
    return highlightAllOccurrences(snippet, needles)
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
 * Finds every visible term that earned a result: the query, related Quran
 * vocabulary, query-related gloss words, and non-filler concept-label words.
 * Unrelated fallback words stay ink.
 */
internal fun highlightNeedles(
    haystack: String,
    query: String,
    wordGloss: String,
    semanticLabel: String = "",
    semanticTerms: List<String> = emptyList(),
): List<String> {
    val needles = linkedMapOf<String, String>()
    fun add(text: String) {
        val term = text.trim()
        if (term.isNotEmpty() && haystack.contains(term, ignoreCase = true)) {
            needles.putIfAbsent(term.lowercase(), term)
        }
    }
    if (query.isNotEmpty() && haystack.contains(query, ignoreCase = true)) {
        add(query)
    }
    val parsed = parseSearchQuery(query)
    val arabicQuery = normalizeArabicForSearch(query).isNotEmpty()
    fun presentTokens(text: String) = text
        .split(Regex("[\\s,;:]+"))
        .map { it.trim().trim('(', ')', '[', ']', '"', '\'') }
        .filter { it.length >= 3 }
        .filter { haystack.contains(it, ignoreCase = true) }

    val glossTokens = presentTokens(wordGloss).filterNot { it.lowercase() in highlightFillers }
    if (arabicQuery) {
        glossTokens.forEach(::add)
    } else {
        glossTokens
            .filter { searchTextRelevance(it, parsed) > 0 }
            .forEach(::add)
    }
    semanticTerms.forEach(::add)
    presentTokens(semanticLabel)
        .filterNot { it.lowercase() in highlightFillers }
        .forEach(::add)
    return needles.values.toList()
}

private val highlightFillers = setOf(
    "and", "are", "for", "from", "has", "have", "into", "that", "the", "their", "then",
    "they", "this", "those", "was", "were", "will", "with", "you", "your",
)

private fun highlightAllOccurrences(text: String, needles: List<String>): List<AyahTextSpan> {
    if (needles.isEmpty()) return listOf(AyahTextSpan(text, highlighted = false))
    val ranges = needles.flatMap { needle ->
        buildList {
            var at = text.indexOf(needle, ignoreCase = true)
            while (at >= 0) {
                add(at until at + needle.length)
                at = text.indexOf(needle, at + needle.length, ignoreCase = true)
            }
        }
    }.sortedWith(compareBy<IntRange> { it.first }.thenByDescending { it.last })
    val visible = ArrayList<IntRange>(ranges.size)
    for (range in ranges) {
        if (visible.lastOrNull()?.last?.let { range.first <= it } != true) visible += range
    }
    if (visible.isEmpty()) return listOf(AyahTextSpan(text, highlighted = false))
    val spans = ArrayList<AyahTextSpan>()
    var start = 0
    for (range in visible) {
        if (range.first > start) {
            spans.add(AyahTextSpan(text.substring(start, range.first), highlighted = false))
        }
        val end = range.last + 1
        spans.add(AyahTextSpan(text.substring(range.first, end), highlighted = true))
        start = end
    }
    if (start < text.length) {
        spans.add(AyahTextSpan(text.substring(start), highlighted = false))
    }
    return spans
}

data class AyahTextSpan(val text: String, val highlighted: Boolean)
