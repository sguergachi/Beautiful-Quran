package com.beautifulquran.ui.rootviewer

/**
 * Lane writes English prose with Arabic set inline ("inf. n. كِتَابٌ and
 * كِتَابَةٌ"), so an entry has to be drawn in two scripts at once: the Latin
 * runs in the reading face, the Arabic runs in the mushaf face at its own
 * size. Splitting is pure and lives here so it can be tested without Compose.
 */
internal data class LexiconRun(val text: String, val isArabic: Boolean)

/** Characters of Lane shown before the reader asks for the whole article. */
internal const val LEXICON_PREVIEW_CHARS = 1_400

/**
 * The opening of an article, cut at one of Lane's own divisions.
 *
 * Articles run from a paragraph to ~99,000 characters, so the section shows
 * its head and lets the reader unfold the rest. The cut prefers the last
 * sense break inside the budget, then a sentence end, so the preview never
 * stops mid-clause; a short article is returned whole.
 */
internal fun lexiconPreview(text: String, budget: Int = LEXICON_PREVIEW_CHARS): String {
    if (text.length <= budget) return text
    val window = text.take(budget)
    val cut = listOf(
        window.lastIndexOf("\n•"),
        window.lastIndexOf("\n\n"),
        window.lastIndexOf(". "),
    ).firstOrNull { it > budget / 3 } ?: budget
    return window.take(cut).trimEnd().trimEnd(',', ';', '—', '(') + " …"
}

/** Arabic block, Arabic Supplement/Extended-A, and the presentation forms. */
private fun Char.isArabicScript(): Boolean = when (this) {
    in '؀'..'ۿ', in 'ݐ'..'ݿ',
    in 'ࢠ'..'ࣿ', in 'ﭐ'..'﷿',
    in 'ﹰ'..'﻿' -> true
    else -> false
}

/**
 * Splits [text] into alternating Latin and Arabic runs.
 *
 * Neutral characters — spaces, the commas and parentheses Lane sets around a
 * word, his ↓ reference arrow — carry no script of their own, so they stay in
 * the run they follow rather than starting a one-character run of their own.
 * Which font draws a comma is invisible; bidi ordering is resolved over the
 * whole paragraph, not per run, so the split only ever chooses a typeface.
 */
internal fun lexiconRuns(text: String): List<LexiconRun> {
    if (text.isEmpty()) return emptyList()
    val runs = mutableListOf<LexiconRun>()
    val current = StringBuilder()
    var arabic: Boolean? = null

    for (char in text) {
        val script = when {
            char.isArabicScript() -> true
            char.isLetterOrDigit() -> false
            else -> null // neutral: stay in the run we are already in
        }
        if (script != null && arabic != null && script != arabic) {
            runs += LexiconRun(current.toString(), arabic)
            current.clear()
        }
        if (script != null) arabic = script
        current.append(char)
    }
    if (current.isNotEmpty()) runs += LexiconRun(current.toString(), arabic == true)
    return runs
}
