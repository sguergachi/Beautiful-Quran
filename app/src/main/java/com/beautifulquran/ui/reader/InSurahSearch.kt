package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Ayah
import com.beautifulquran.data.model.Word
import com.beautifulquran.domain.EnglishTypography
import com.beautifulquran.domain.normalizeArabicForSearch

/**
 * The reader's in-surah search: which ayahs a query finds and which of their
 * words it marks, so the match can be marked on the page in every view —
 * the Arabic word itself in Arabic and Both, its gloss in Both and English.
 *
 * A query holding Arabic letters is read as Arabic and matched against the
 * words' undiacritized text (typed Arabic carries no tashkeel, the page's
 * Uthmani does); anything else is matched against the English. Both kinds
 * run across word boundaries, so "most merciful" or "الرحمن الرحيم" mark every
 * word the phrase touches, not only a word that holds the whole phrase.
 */
internal object InSurahSearch {

    /** Word positions of [ayah] that [query] marks; empty when it marks none. */
    fun markedWords(ayah: Ayah, query: String?): Set<Int> {
        if (query.isNullOrEmpty() || ayah.words.isEmpty()) return emptySet()
        val arabic = normalizeArabicForSearch(query)
        return if (arabic.isNotEmpty()) {
            // Normalised Arabic has no spaces, so neither does the joined line:
            // a phrase typed with or without them finds the same words.
            marked(ayah, arabic, separator = "", ignoreCase = false) {
                normalizeArabicForSearch(it.arabic)
            }
        } else {
            marked(ayah, query, separator = " ", ignoreCase = true) {
                EnglishTypography.fold(it.translation)
            }
        }
    }

    /** True when [ayah] is a match: a marked word, or its translation. */
    fun matches(ayah: Ayah, query: String, marked: Set<Int>): Boolean =
        marked.isNotEmpty() ||
            EnglishTypography.fold(ayah.translation).contains(query, ignoreCase = true)

    private inline fun marked(
        ayah: Ayah,
        needle: String,
        separator: String,
        ignoreCase: Boolean,
        text: (Word) -> String,
    ): Set<Int> {
        val starts = IntArray(ayah.words.size)
        val ends = IntArray(ayah.words.size)
        val line = buildString {
            ayah.words.forEachIndexed { index, word ->
                if (index > 0) append(separator)
                starts[index] = length
                append(text(word))
                ends[index] = length
            }
        }
        val marked = linkedSetOf<Int>()
        var at = line.indexOf(needle, 0, ignoreCase)
        while (at >= 0) {
            val end = at + needle.length
            // Overlap with the word's own letters: a separator alone marks nothing.
            for (index in ayah.words.indices) {
                if (starts[index] < end && at < ends[index]) marked += ayah.words[index].position
            }
            at = line.indexOf(needle, at + 1, ignoreCase)
        }
        return marked
    }
}
