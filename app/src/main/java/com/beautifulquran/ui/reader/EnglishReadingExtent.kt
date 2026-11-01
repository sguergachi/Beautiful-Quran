package com.beautifulquran.ui.reader

/**
 * Reading distance follows visible character slots. Crossing blank space takes
 * no audio time; it cannot paint a glyph or anticipate the following word.
 */
internal class EnglishReadingExtent(text: String) {
    private val length = text.length
    private val offsets = text.indices.filterNot { text[it].isWhitespace() }.toIntArray()
    private val prefix = IntArray(length + 1).also { counts ->
        for (at in text.indices) counts[at + 1] = counts[at] + if (text[at].isWhitespace()) 0 else 1
    }

    val hasInk: Boolean get() = offsets.isNotEmpty()

    /** Continuous ink distance to printed character coordinates, skipping only blanks. */
    fun printed(through: Float): Float {
        if (!hasInk || through <= 0f) return 0f
        if (through >= 1f) return 1f
        val at = through * offsets.size
        val index = at.toInt().coerceAtMost(offsets.lastIndex)
        return (offsets[index] + at - index) / length
    }

    /** Blank-space coordinates share one instant, so forward/inverse audio holds agree. */
    fun ink(through: Float): Float {
        if (!hasInk || through <= 0f) return 0f
        if (through >= 1f) return 1f
        val at = through * length
        val index = at.toInt().coerceAtMost(length - 1)
        val within = if (prefix[index + 1] > prefix[index]) at - index else 0f
        return (prefix[index] + within) / offsets.size
    }
}
