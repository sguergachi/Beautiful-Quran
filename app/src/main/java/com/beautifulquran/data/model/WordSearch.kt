package com.beautifulquran.data.model

enum class WordSearchDisplaySource { ARABIC, WORD_GLOSS, TRANSLITERATION, VERSE_TRANSLATION }

/** One word-level hit from a Quran-wide home search. */
data class WordSearchHit(
    val surahId: Int,
    val ayahNumber: Int,
    val position: Int,
    val arabic: String,
    val translation: String,
    val transliteration: String,
    val ayahText: String,
    val ayahTranslation: String,
    val surahNameTransliteration: String,
    val surahNameArabic: String,
    /** Ontology concept that surfaced an ayah-level semantic result. */
    val matchLabel: String? = null,
    /** Every visible Quran-vocabulary term that helped this result rank. */
    val matchTerms: List<String> = emptyList(),
    /** Non-null only when last-resort spelling correction actually ran. */
    val correctedQuery: String? = null,
    /** Quiet, user-facing explanation of why this result is relevant. */
    val matchReason: String = "Text match",
    /** Snippet copied from the reader-visible source selected for this search. */
    val displayText: String = ayahTranslation,
    /** Which reader-visible text surface [displayText] contains. */
    val displaySource: WordSearchDisplaySource = WordSearchDisplaySource.VERSE_TRANSLATION,
)

/**
 * Hits for one surah, with [totalCount] reflecting every match in that
 * chapter (even when [hits] is a truncated preview).
 */
data class SurahWordSearchSection(
    val surahId: Int,
    val surahNameTransliteration: String,
    val surahNameArabic: String,
    val hits: List<WordSearchHit>,
    val totalCount: Int,
    val expanded: Boolean,
) {
    val hiddenCount: Int get() = (totalCount - hits.size).coerceAtLeast(0)
}
