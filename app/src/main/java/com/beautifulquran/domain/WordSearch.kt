package com.beautifulquran.domain

import com.beautifulquran.data.model.SurahWordSearchSection
import com.beautifulquran.data.model.WordSearchDisplaySource
import com.beautifulquran.data.model.WordSearchHit

/** Soft cap so a very common English gloss cannot flood the cover sheet. */
const val WORD_SEARCH_MAX_HITS = 400

/** Minimum trimmed query length before Quran-wide word search runs. */
const val WORD_SEARCH_MIN_QUERY_LENGTH = 2

/** How many ayah hits to show per surah before the expand line. */
const val WORD_SEARCH_PREVIEW_LIMIT = 3

/** Keeps visible concept evidence ahead of any bounded corroboration bonus. */
private const val VISIBLE_CONCEPT_EVIDENCE_BONUS = 300

/** Text surfaces currently visible in the reader. Hidden translations must not affect search. */
data class WordSearchSources(
    val arabic: Boolean = true,
    val wordGloss: Boolean = true,
    val transliteration: Boolean = true,
    val verseTranslation: Boolean = true,
)

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
 * Ranks the reader-visible [sources], related QAC-root words, and QSAC
 * [concepts]. Enclosing the whole query in double quotes keeps only literal
 * whole-word/phrase matches from those visible sources.
 */
fun matchWordSearch(
    index: List<WordSearchIndexEntry>,
    query: String,
    maxHits: Int = WORD_SEARCH_MAX_HITS,
    concepts: List<SearchConcept> = emptyList(),
    thesaurus: Map<String, List<RelatedSearchTerm>> = emptyMap(),
    checkCancelled: () -> Unit = {},
    sources: WordSearchSources = WordSearchSources(),
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
        val correctedQuery: String? = null,
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
        correction: String? = null,
        reason: String = "Text match",
    ) {
        if (score <= 0) return
        val entry = index[indexAt]
        val key = entry.surahId * 1_000 + entry.ayahNumber
        val current = ranked[key]
        if (current == null || score > current.score ||
            (score == current.score && position > 0 && current.position == 0)
        ) {
            ranked[key] = RankedHit(
                key,
                indexAt,
                position,
                score,
                label,
                terms,
                correction,
                reason,
            )
        }
    }

    fun scanOriginal(allowFuzzy: Boolean) {
        val arabic = normalizeArabicForSearch(parsed.text)
        val latin = if (arabic.isEmpty()) parsed else parsed.copy(text = "")
        for (i in index.indices) {
            if ((i and 0xfff) == 0) checkCancelled()
            val entry = index[i]
            val score = maxOf(
                if (!sources.arabic || arabic.isEmpty()) 0 else searchTextRelevance(
                    entry.arabicNorm,
                    parsed.copy(text = arabic),
                    allowFuzzy,
                ),
                if (sources.wordGloss) {
                    searchTextRelevance(entry.translationLower, latin, allowFuzzy)
                } else 0,
                if (sources.transliteration) {
                    searchTextRelevance(entry.transliterationLower, latin, allowFuzzy)
                } else 0,
            )
            val correction = if (allowFuzzy && score > 0) {
                if (sources.arabic && arabic.isNotEmpty()) {
                    fuzzyWordMatch(entry.arabicNorm, arabic)
                } else {
                    (if (sources.wordGloss) {
                        fuzzyWordMatch(entry.translationLower, latin.text)
                    } else null) ?: if (sources.transliteration) {
                        fuzzyWordMatch(entry.transliterationLower, latin.text)
                    } else null
                }
            } else null
            add(
                i,
                entry.position,
                score,
                terms = listOfNotNull(correction),
                correction = correction,
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
                if (!sources.arabic || arabic.isEmpty()) 0 else searchTextRelevance(
                    normalizeArabicForSearch(anchor.ayahText),
                    parsed.copy(text = arabic),
                    allowFuzzy,
                ),
                if (sources.verseTranslation) {
                    searchTextRelevance(anchor.ayahTranslation, latin, allowFuzzy)
                } else 0,
                if (sources.wordGloss && parsed.text.any(Char::isWhitespace)) {
                    searchTextRelevance(sameAyahGlossLine(index, at), latin, allowFuzzy)
                } else {
                    0
                },
                if (sources.transliteration && parsed.text.any(Char::isWhitespace)) {
                    searchTextRelevance(sameAyahTransliterationLine(index, at), latin, allowFuzzy)
                } else 0,
            )
            val correction = if (allowFuzzy && score > 0) {
                if (sources.arabic && arabic.isNotEmpty()) {
                    fuzzyWordMatch(normalizeArabicForSearch(anchor.ayahText), arabic)
                } else {
                    (if (sources.verseTranslation) {
                        fuzzyWordMatch(anchor.ayahTranslation.lowercase(), latin.text)
                    } else null) ?: (if (sources.wordGloss) {
                        fuzzyWordMatch(sameAyahGlossLine(index, at).lowercase(), latin.text)
                    } else null) ?: if (sources.transliteration) {
                        fuzzyWordMatch(sameAyahTransliterationLine(index, at).lowercase(), latin.text)
                    } else null
                }
            } else null
            add(
                at,
                position = 0,
                score = score,
                terms = listOfNotNull(correction),
                correction = correction,
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
            val terms: List<String>,
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
            val terms = conceptHighlightTerms(concept)
            val evidenceQuery = ParsedSearchQuery(correction ?: parsed.text, exactOnly = false)
            for (key in concept.ayahKeys) {
                val hasVisibleEvidence = firstIndex[key]?.let { at ->
                    maxOf(
                        if (sources.verseTranslation) {
                            searchTextRelevance(index[at].ayahTranslation, evidenceQuery, false)
                        } else 0,
                        if (sources.wordGloss) {
                            searchTextRelevance(sameAyahGlossLine(index, at), evidenceQuery, false)
                        } else 0,
                        if (sources.transliteration) {
                            searchTextRelevance(sameAyahTransliterationLine(index, at), evidenceQuery, false)
                        } else 0,
                    ) > 0
                } == true
                val groundedScore = score + if (hasVisibleEvidence) {
                    VISIBLE_CONCEPT_EVIDENCE_BONUS
                } else {
                    0
                }
                val current = semantic[key]
                semantic[key] = if (current == null) {
                    SemanticRank(groundedScore, bonus = 0, concept.name, correction, terms)
                } else {
                    SemanticRank(
                        best = maxOf(current.best, groundedScore),
                        bonus = (current.bonus + minOf(current.best, groundedScore) / 5)
                            .coerceAtMost(250),
                        label = if (groundedScore > current.best) concept.name else current.label,
                        correction = if (groundedScore > current.best) correction else current.correction,
                        terms = (current.terms + terms).distinctBy(String::lowercase),
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
                    terms = listOfNotNull(match.correction) + match.terms,
                    correction = match.correction,
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
        if (related.isEmpty() || (!sources.wordGloss && !sources.verseTranslation)) return
        if (sources.wordGloss) {
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
            val match = listOfNotNull(
                if (sources.verseTranslation) bestRelated(anchor.ayahTranslation, related) else null,
                if (sources.wordGloss) bestRelated(sameAyahGlossLine(index, at), related) else null,
            ).maxByOrNull { it.score }
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
                    terms = listOf(if (sources.wordGloss) index[i].translation else index[i].arabic),
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
            val anchor = index[match.indexAt]
            val display = snippetDisplayText(
                anchor,
                index,
                match.indexAt,
                parsed.text,
                match.matchLabel.orEmpty(),
                match.matchTerms,
                sources,
            )
            val targetAt = if (match.position > 0) {
                match.indexAt
            } else {
                visibleSearchTargetIndex(
                    index,
                    match.indexAt,
                    display.text,
                    parsed.text,
                    match.matchLabel.orEmpty(),
                    match.matchTerms,
                )
            }
            val target = targetAt?.let(index::get)
            val base = (target ?: anchor).toHit().copy(
                matchLabel = match.matchLabel,
                matchTerms = match.matchTerms,
                correctedQuery = match.correctedQuery,
                matchReason = match.matchReason,
                displayText = display.text,
                displaySource = display.source,
            )
            if (target == null) {
                base.copy(position = 0, arabic = "", translation = "", transliteration = "")
            } else {
                base
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
fun spellingCorrection(hits: Iterable<WordSearchHit>): String? =
    hits.firstNotNullOfOrNull(WordSearchHit::correctedQuery)

/** Searchable concept vocabulary carried to the renderer as grounded highlight candidates. */
private fun conceptHighlightTerms(concept: SearchConcept): List<String> =
    sequenceOf(concept.name)
        .plus(concept.primaryTerms)
        .plus(concept.secondaryTerms)
        .flatMap { alignmentWordPattern.findAll(it).map(MatchResult::value) }
        .filter { it.length >= 3 && it.lowercase() !in highlightFillers }
        .distinctBy(String::lowercase)
        .toList()

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

internal data class WordSearchSnippet(
    val text: String,
    val source: WordSearchDisplaySource,
)

/** Chooses only among text surfaces visible under the active reader settings. */
internal fun snippetDisplayText(
    entry: WordSearchIndexEntry,
    index: List<WordSearchIndexEntry>,
    at: Int,
    query: String,
    semanticLabel: String = "",
    semanticTerms: List<String> = emptyList(),
    sources: WordSearchSources = WordSearchSources(),
): WordSearchSnippet {
    val candidates = buildList {
        if (sources.verseTranslation) {
            add(WordSearchSnippet(entry.ayahTranslation, WordSearchDisplaySource.VERSE_TRANSLATION))
        }
        if (sources.wordGloss) {
            add(WordSearchSnippet(sameAyahGlossLine(index, at), WordSearchDisplaySource.WORD_GLOSS))
        }
        if (sources.transliteration) {
            add(
                WordSearchSnippet(
                    sameAyahTransliterationLine(index, at),
                    WordSearchDisplaySource.TRANSLITERATION,
                ),
            )
        }
        if (sources.arabic) {
            add(WordSearchSnippet(entry.ayahText, WordSearchDisplaySource.ARABIC))
        }
    }
    return candidates.firstOrNull { candidate ->
        highlightNeedles(
            candidate.text,
            query,
            if (candidate.source == WordSearchDisplaySource.WORD_GLOSS) entry.translation else "",
            semanticLabel,
            semanticTerms = semanticTerms,
        ).isNotEmpty()
    } ?: candidates.firstOrNull() ?: WordSearchSnippet("", WordSearchDisplaySource.WORD_GLOSS)
}

/** Resolves an ayah-level result to the word gloss behind its visible gold term. */
internal fun visibleSearchTargetIndex(
    index: List<WordSearchIndexEntry>,
    at: Int,
    displayText: String,
    query: String,
    semanticLabel: String = "",
    semanticTerms: List<String> = emptyList(),
): Int? {
    if (at !in index.indices) return null
    val anchor = index[at]
    var lo = at
    while (
        lo > 0 && index[lo - 1].surahId == anchor.surahId &&
        index[lo - 1].ayahNumber == anchor.ayahNumber
    ) lo--
    var hi = at
    while (
        hi + 1 < index.size && index[hi + 1].surahId == anchor.surahId &&
        index[hi + 1].ayahNumber == anchor.ayahNumber
    ) hi++

    val arabicTerms = query.split(Regex("[^\\p{L}\\p{N}]+"))
        .map(::normalizeArabicForSearch)
        .filter(String::isNotEmpty)
    if (arabicTerms.isNotEmpty()) {
        return (lo..hi).firstOrNull { i ->
            arabicTerms.any { term -> index[i].arabicNorm.contains(term) }
        }
    }

    val needles = highlightNeedles(displayText, query, "", semanticLabel, semanticTerms)
    val terms = needles
        .flatMap { needle ->
            listOf(needle) + Regex("[\\p{L}\\p{N}]+").findAll(needle).map { it.value }
        }
        .filter { it.length >= 3 && it.lowercase() !in highlightFillers }
        .distinctBy(String::lowercase)
    var bestAt: Int? = null
    var bestScore = 0
    for (i in lo..hi) {
        val score = terms.maxOfOrNull { term ->
            glossAlignmentRelevance(index[i].translationLower, term)
        } ?: 0
        if (score > bestScore) {
            bestAt = i
            bestScore = score
        }
    }
    if (bestAt != null) return bestAt

    val auxiliaryOnly = needles
        .flatMap { Regex("[\\p{L}\\p{N}]+").findAll(it).map(MatchResult::value) }
        .all { it.lowercase() in translationOnlyAuxiliaries }
    if (!auxiliaryOnly) return null

    // Saheeh International sometimes adds an English auxiliary that has no
    // one-to-one Quran word gloss ("could see", "could have taken"). Walk
    // outward from that visible match and pulse its nearest grounded verb.
    for (term in neighboringVisibleTerms(displayText, needles)) {
        val target = (lo..hi).maxByOrNull { i ->
            glossAlignmentRelevance(index[i].translationLower, term)
        } ?: continue
        if (glossAlignmentRelevance(index[target].translationLower, term) > 0) return target
    }
    return null
}

private val alignmentWordPattern = Regex("[\\p{L}\\p{N}]+")

private fun alignmentForm(word: String): String = when {
    word.length > 4 && word.endsWith("ing") -> word.dropLast(3)
    word.length > 6 && word.endsWith("ness") -> word.dropLast(4)
    word.length > 5 && word.endsWith("ies") -> word.dropLast(3) + "y"
    word.length > 4 && word.endsWith("s") -> word.dropLast(1)
    else -> word
}

/** Whole-token/stem score for mapping visible translation evidence to a Quran gloss. */
internal fun glossAlignmentRelevance(gloss: String, visibleTerm: String): Int {
    val glossWords = alignmentWordPattern.findAll(gloss.lowercase()).map(MatchResult::value).toList()
    val termWords = alignmentWordPattern.findAll(visibleTerm.lowercase()).map(MatchResult::value).toList()
    if (glossWords.isEmpty() || termWords.isEmpty()) return 0
    if (glossWords.windowed(termWords.size).any { it == termWords }) return 2
    val glossForms = glossWords.mapTo(HashSet(glossWords.size), ::alignmentForm)
    return if (termWords.all { alignmentForm(it) in glossForms }) 1 else 0
}

/** Nearby content words that can ground a translation-only auxiliary match. */
private fun neighboringVisibleTerms(text: String, needles: List<String>): List<String> {
    val words = Regex("[\\p{L}\\p{N}]+").findAll(text).toList()
    val range = needles.firstNotNullOfOrNull { needle ->
        text.indexOf(needle, ignoreCase = true).takeIf { it >= 0 }?.let { it until it + needle.length }
    } ?: return emptyList()
    val first = words.indexOfFirst { it.range.last >= range.first }
    val last = words.indexOfLast { it.range.first <= range.last }
    if (first < 0 || last < first) return emptyList()
    return buildList {
        for (distance in 1..minOf(4, maxOf(words.size - last, first + 1))) {
            listOf(last + distance, first - distance).forEach { at ->
                val term = words.getOrNull(at)?.value ?: return@forEach
                if (term.length >= 2 && term.lowercase() !in targetContextFillers) add(term)
            }
        }
    }.distinctBy(String::lowercase)
}

/** Space-joined English glosses, coalescing adjacent shared-phrase copies. */
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
    val parts = ArrayList<String>(hi - lo + 1)
    for (i in lo..hi) {
        val entry = index[i]
        val part = entry.translation.trim()
        val previous = index.getOrNull(i - 1)?.takeIf { i > lo }
        val sharedPhrase = previous != null &&
            part.equals(previous.translation.trim(), ignoreCase = true) &&
            normalizeArabicForSearch(entry.arabic) != normalizeArabicForSearch(previous.arabic)
        if (part.isNotEmpty() && !sharedPhrase) {
            parts += part
        }
    }
    return parts.joinToString(" ")
}

/** Space-joined transliteration shown beneath Arabic when that reader option is enabled. */
internal fun sameAyahTransliterationLine(index: List<WordSearchIndexEntry>, at: Int): String =
    sameAyahLine(index, at) { it.transliteration }

private fun sameAyahLine(
    index: List<WordSearchIndexEntry>,
    at: Int,
    text: (WordSearchIndexEntry) -> String,
): String {
    if (at !in index.indices) return ""
    val anchor = index[at]
    var lo = at
    while (
        lo > 0 && index[lo - 1].surahId == anchor.surahId &&
        index[lo - 1].ayahNumber == anchor.ayahNumber
    ) lo--
    var hi = at
    while (
        hi + 1 < index.size && index[hi + 1].surahId == anchor.surahId &&
        index[hi + 1].ayahNumber == anchor.ayahNumber
    ) hi++
    return (lo..hi).joinToString(" ") { text(index[it]).trim() }.trim()
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
    val needles = highlightNeedleSpecs(
        ayahTranslation,
        query.trim(),
        wordGloss.trim(),
        semanticLabel.trim(),
        semanticTerms,
    )
    val snippet = windowAroundMatch(
        text = ayahTranslation,
        needle = needles.firstOrNull()?.text,
        wordsBefore = SNIPPET_WORDS_BEFORE,
        wordsAfter = SNIPPET_WORDS_AFTER,
        wholeWord = needles.firstOrNull()?.wholeWord == true,
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
    wholeWord: Boolean = false,
): String {
    if (needle.isNullOrEmpty() || text.isEmpty()) return text
    val matchStart = firstOccurrence(text, needle, wholeWord)
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
): List<String> = highlightNeedleSpecs(
    haystack,
    query,
    wordGloss,
    semanticLabel,
    semanticTerms,
).map(HighlightNeedle::text)

private data class HighlightNeedle(val text: String, val wholeWord: Boolean)

private fun highlightNeedleSpecs(
    haystack: String,
    query: String,
    wordGloss: String,
    semanticLabel: String,
    semanticTerms: List<String>,
): List<HighlightNeedle> {
    val needles = linkedMapOf<String, HighlightNeedle>()
    fun add(text: String, wholeWord: Boolean = true) {
        val term = text.trim()
        if (term.isNotEmpty() && firstOccurrence(haystack, term, wholeWord) >= 0) {
            needles.putIfAbsent(term.lowercase(), HighlightNeedle(term, wholeWord))
        }
    }
    add(query, wholeWord = false)
    val parsed = parseSearchQuery(query)
    val arabicQuery = normalizeArabicForSearch(query).isNotEmpty()
    fun visibleTerm(term: String): String? {
        if (firstOccurrence(haystack, term, wholeWord = true) >= 0) return term
        val sourceWords = alignmentWordPattern.findAll(term).map(MatchResult::value).toList()
        if (sourceWords.size != 1) return null
        val form = alignmentForm(sourceWords.single().lowercase())
        return alignmentWordPattern.findAll(haystack)
            .firstOrNull { alignmentForm(it.value.lowercase()) == form }
            ?.value
    }
    fun presentTokens(text: String) = text
        .split(Regex("[\\s,;:]+"))
        .map { it.trim().trim('(', ')', '[', ']', '"', '\'') }
        .filter { it.length >= 3 }
        .mapNotNull(::visibleTerm)

    val glossTokens = presentTokens(wordGloss).filterNot { it.lowercase() in highlightFillers }
    if (arabicQuery) {
        glossTokens.forEach(::add)
    } else {
        glossTokens
            .filter { searchTextRelevance(it, parsed) > 0 }
            .forEach(::add)
    }
    semanticTerms.flatMap(::presentTokens).forEach(::add)
    presentTokens(semanticLabel)
        .filterNot { it.lowercase() in highlightFillers }
        .forEach(::add)
    return needles.values.toList()
}

private fun firstOccurrence(text: String, term: String, wholeWord: Boolean, startIndex: Int = 0): Int {
    var at = text.indexOf(term, startIndex, ignoreCase = true)
    while (at >= 0) {
        val end = at + term.length
        if (!wholeWord ||
            (at == 0 || !text[at - 1].isLetterOrDigit()) &&
            (end == text.length || !text[end].isLetterOrDigit())
        ) return at
        at = text.indexOf(term, at + 1, ignoreCase = true)
    }
    return -1
}

private val highlightFillers = setOf(
    "and", "are", "for", "from", "has", "have", "into", "that", "the", "their", "then",
    "they", "this", "those", "was", "were", "will", "with", "you", "your",
)

private val targetContextFillers = highlightFillers + setOf(
    "but", "can", "could", "had", "he", "her", "him", "his", "how", "if", "in", "is", "it",
    "its", "may", "might", "nor", "not", "or", "shall", "she", "should", "so", "than",
    "them", "there", "these", "to", "we", "what", "when", "where", "which", "who",
    "whom", "whose", "why", "would",
)

private val translationOnlyAuxiliaries = setOf(
    "can", "could", "may", "might", "shall", "should", "will", "would",
)

private fun highlightAllOccurrences(text: String, needles: List<HighlightNeedle>): List<AyahTextSpan> {
    if (needles.isEmpty()) return listOf(AyahTextSpan(text, highlighted = false))
    val ranges = needles.flatMap { needle ->
        buildList {
            var at = firstOccurrence(text, needle.text, needle.wholeWord)
            while (at >= 0) {
                add(at until at + needle.text.length)
                at = firstOccurrence(text, needle.text, needle.wholeWord, at + needle.text.length)
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
