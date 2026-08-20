package com.beautifulquran.domain

/**
 * QCF V2 page text for one Madinah line.
 *
 * Each token is one Uthman Taha word glyph. The page face has no U+0020;
 * a regular space falls back to another font and blows the line apart.
 * The renderer inserts a 3 dp Hafs hair-gap between tokens. This string
 * is glyphs only, for width measurement. The DB separates a token's glyph
 * runs with a space — see [qcfMarkStart] for what those spaces mean.
 * Never invent ﴿N﴾.
 */
data class MushafQcfLine(
    val text: String,
    val wordRanges: List<IntRange>,
)

/**
 * Where the circled verse mark starts inside a token's glyph string, or -1.
 *
 * A space in `qcf_v2` is only a break between glyph runs, and three different
 * things put one there. A verse-final word carries its circled number after
 * the space — `wordGlyph mark`. A word opening a rubʿ carries the ۞ *before*
 * it — `۞ wordGlyph`, 195 of them, and the ۞ is part of the word in the
 * Uthmani text too. And a handful of words are simply set in more than one
 * run (`إِلۡ` on 37:130 takes three).
 *
 * Splitting on the *first* space and calling everything after it a mark
 * therefore drew the ۞ as the word and the word itself in verse-mark gold —
 * one gold word at the head of every rubʿ, standing before it was ever
 * recited. Only [endsAyah] can tell a mark from a word, so only [endsAyah]
 * may produce one, and it is always the final run.
 */
private fun qcfMarkStart(qcfV2: String, endsAyah: Boolean): Int =
    if (endsAyah) qcfV2.lastIndexOf(' ') else -1

/** The token's own glyphs — every run but the verse mark, spaces removed. */
fun qcfWordGlyphs(qcfV2: String, endsAyah: Boolean): String {
    val mark = qcfMarkStart(qcfV2, endsAyah)
    val body = if (mark < 0) qcfV2 else qcfV2.substring(0, mark)
    return if (body.indexOf(' ') < 0) body else body.filter { it != ' ' }
}

/** The circled verse number this token closes its verse with, if it does. */
fun qcfTrailingMark(qcfV2: String, endsAyah: Boolean): String {
    val mark = qcfMarkStart(qcfV2, endsAyah)
    return if (mark < 0) "" else qcfV2.substring(mark + 1)
}

fun buildMushafQcfLine(tokens: List<MushafToken>): MushafQcfLine {
    val ranges = ArrayList<IntRange>(tokens.size)
    val text = buildString {
        tokens.forEach { token ->
            val qcf = token.word.qcfV2
            val glyph = if (qcf.isNotEmpty()) {
                qcfWordGlyphs(qcf, token.endsAyah)
            } else {
                token.word.arabic
            }
            val mark = if (qcf.isNotEmpty()) qcfTrailingMark(qcf, token.endsAyah) else ""
            val start = length
            append(glyph)
            ranges += start until length
            if (mark.isNotEmpty()) append(mark)
        }
    }
    return MushafQcfLine(text, ranges)
}
