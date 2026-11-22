package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Ayah
import com.beautifulquran.data.model.Word
import com.beautifulquran.domain.EnglishTypography
import com.beautifulquran.domain.EnglishWordAlignment
import com.beautifulquran.domain.normalizeArabicForSearch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
 *
 * An English query found only in the verse's translation is still marked on
 * the word that says it: the translation's words are aligned back to the
 * Arabic ([EnglishWordAlignment.ownerAt]), so "satan" in "Satan caused them to
 * slip" marks ٱلشَّيْطَٰنُ, glossed "the Shaitaan". The translation is often not on
 * the page at all (Both without it, Arabic, English glosses), and a match the
 * reader cannot see was a match the counter jumped to with nothing marked.
 */
internal object InSurahSearch {

    /**
     * Every ayah [query] finds in [ayahs], with the words it marks in each.
     * Each keystroke supersedes the last search, so a cancelled one stops at
     * the next verse instead of aligning the rest of the chapter for nothing.
     */
    suspend fun find(ayahs: List<Ayah>, query: String): InSurahSearchResult {
        val marks = HashMap<Int, Set<Int>>()
        val matches = ArrayList<Int>()
        for (ayah in ayahs) {
            currentCoroutineContext().ensureActive()
            val marked = markedWords(ayah, query)
            if (marked.isNotEmpty()) marks[ayah.number] = marked
            if (matches(ayah, query, marked)) matches += ayah.number
        }
        return InSurahSearchResult(query, marks, matches)
    }

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
            } + translationOwners(ayah, query)
        }
    }

    /** Positions of the words whose meaning the translation's matches speak. */
    private fun translationOwners(ayah: Ayah, query: String): Set<Int> {
        // Folded character for character, so its offsets are the translation's.
        val text = EnglishTypography.fold(ayah.translation)
        var at = text.indexOf(query, 0, ignoreCase = true)
        if (at < 0) return emptySet()
        val glosses = ayah.words.map { it.translation }
        val lemmas = ayah.words.map { it.lemma }
        val arabic = ayah.words.map { it.arabic }
        val owners = linkedSetOf<Int>()
        while (at >= 0) {
            // Every printed word the match touches, from the one it starts in.
            for (i in at until at + query.length) {
                if (i != at && (!text[i].isLetterOrDigit() || text[i - 1].isLetterOrDigit())) continue
                if (!text[i].isLetterOrDigit()) continue
                EnglishWordAlignment.ownerAt(ayah.translation, glosses, i, lemmas, arabic)
                    ?.let { ayah.words.getOrNull(it) }
                    ?.let { owners += it.position }
            }
            at = text.indexOf(query, at + 1, ignoreCase = true)
        }
        return owners
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

/** What one query found: marked word positions per ayah, and the ayahs it matches. */
internal data class InSurahSearchResult(
    val query: String?,
    val marks: Map<Int, Set<Int>>,
    val matches: List<Int>,
) {
    companion object {
        val None = InSurahSearchResult(null, emptyMap(), emptyList())
    }
}
