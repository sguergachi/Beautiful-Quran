package com.beautifulquran.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

/**
 * How much of the text's own ink the translator's brackets keep.
 *
 * Sahih International marks every word it supplies — [All] praise is [due] —
 * and nearly half the verses carry them. Timeless draws its brackets tall and
 * dark, so at full ink they punctuated the page more than the words did. The
 * marks recede; the supplied words keep their voice, because they carry the
 * meaning.
 */
const val SUPPLIED_BRACKET_INK = 0.4f

/** Styles the `[` and `]` of [text], appended at [start], at [SUPPLIED_BRACKET_INK] of [ink]. */
fun AnnotatedString.Builder.quietSuppliedBrackets(text: CharSequence, start: Int, ink: Color) {
    if (ink == Color.Unspecified) return
    val quiet = SpanStyle(color = ink.copy(alpha = ink.alpha * SUPPLIED_BRACKET_INK))
    text.forEachIndexed { i, c ->
        if (c == '[' || c == ']') addStyle(quiet, start + i, start + i + 1)
    }
}

/** [text] with its translator's brackets quieted against [ink]. */
fun suppliedWordsText(text: String, ink: Color): AnnotatedString =
    AnnotatedString.Builder(text).apply { quietSuppliedBrackets(text, 0, ink) }.toAnnotatedString()
