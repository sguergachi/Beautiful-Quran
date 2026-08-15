package com.beautifulquran.domain

/**
 * QCF V2 page text for one Madinah line.
 *
 * Each token is one Uthman Taha word glyph. The page face has no U+0020;
 * a regular space falls back to another font and blows the line apart.
 * The renderer inserts a 3 dp Hafs hair-gap between tokens. This string
 * is glyphs only, for width measurement. The DB stores the circled mark
 * as `wordGlyph + space + markGlyph`. Never invent ﴿N﴾.
 */
data class MushafQcfLine(
    val text: String,
    val wordRanges: List<IntRange>,
)

fun qcfWordGlyphs(qcfV2: String): String {
    val space = qcfV2.indexOf(' ')
    return if (space < 0) qcfV2 else qcfV2.substring(0, space)
}

fun qcfTrailingMark(qcfV2: String): String {
    val space = qcfV2.indexOf(' ')
    return if (space < 0) "" else qcfV2.substring(space + 1).filter { it != ' ' }
}

fun buildMushafQcfLine(tokens: List<MushafToken>): MushafQcfLine {
    val ranges = ArrayList<IntRange>(tokens.size)
    val text = buildString {
        tokens.forEach { token ->
            val qcf = token.word.qcfV2
            val glyph = if (qcf.isNotEmpty()) qcfWordGlyphs(qcf) else token.word.arabic
            val mark = if (qcf.isNotEmpty()) qcfTrailingMark(qcf) else ""
            val start = length
            append(glyph)
            ranges += start until length
            if (mark.isNotEmpty()) append(mark)
        }
    }
    return MushafQcfLine(text, ranges)
}
