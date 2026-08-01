package com.beautifulquran.ui.rootviewer

import com.beautifulquran.data.model.DictionaryEntry
import com.beautifulquran.data.model.DictionarySenseGroup
import java.net.URLEncoder

/** Senses shown under Lemma before the reader asks for the rest. */
internal const val DICTIONARY_PREVIEW_SENSES = 4

/**
 * Orders Wiktionary POS groups so the open word's QAC part of speech comes
 * first; everything else keeps source order after that.
 */
internal fun orderedDictionaryGroups(
    groups: List<DictionarySenseGroup>,
    qacPos: String,
): List<DictionarySenseGroup> {
    val preferred = wiktionaryPosForQac(qacPos) ?: return groups
    val (match, rest) = groups.partition { it.pos.equals(preferred, ignoreCase = true) }
    return match + rest
}

internal fun wiktionaryPosForQac(qacPos: String): String? = when (qacPos.uppercase()) {
    "V", "VERB" -> "verb"
    "N", "NOUN" -> "noun"
    "ADJ", "ADJECTIVE" -> "adj"
    "ADV", "ADVERB" -> "adv"
    "P", "PREP", "PREPOSITION" -> "prep"
    "CONJ", "CONJUNCTION" -> "conj"
    "PRON", "PRONOUN" -> "pron"
    "PART", "PARTICLE" -> "particle"
    "INL", "INTERJECTION" -> "intj"
    else -> null
}

internal fun dictionaryPosLabel(pos: String): String = when (pos.lowercase()) {
    "verb" -> "Verb"
    "noun" -> "Noun"
    "adj", "adjective" -> "Adjective"
    "adv", "adverb" -> "Adverb"
    "prep", "preposition" -> "Preposition"
    "conj", "conjunction" -> "Conjunction"
    "pron", "pronoun" -> "Pronoun"
    "particle" -> "Particle"
    "intj", "interjection" -> "Interjection"
    "name", "proper noun", "propn" -> "Proper noun"
    else -> pos.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

/** Flat gloss list in display order (preferred POS first). */
internal fun dictionaryGlosses(
    entry: DictionaryEntry,
    qacPos: String,
): List<Pair<String?, String>> {
    val out = ArrayList<Pair<String?, String>>()
    for (group in orderedDictionaryGroups(entry.groups, qacPos)) {
        val label = dictionaryPosLabel(group.pos)
        group.glosses.forEachIndexed { index, gloss ->
            out += (if (index == 0) label else null) to gloss
        }
    }
    return out
}

internal fun dictionaryNeedsExpand(glossCount: Int): Boolean =
    glossCount > DICTIONARY_PREVIEW_SENSES

/** Wiktionary page for the matched Arabic headword. */
internal fun wiktionaryArabicUrl(word: String): String {
    val encoded = URLEncoder.encode(word, Charsets.UTF_8.name()).replace("+", "_")
    return "https://en.wiktionary.org/wiki/$encoded#Arabic"
}
