package com.beautifulquran.data.model

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
    /** Visible Quran-vocabulary term reached through the thesaurus. */
    val matchTerm: String? = null,
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
