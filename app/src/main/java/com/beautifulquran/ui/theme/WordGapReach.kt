package com.beautifulquran.ui.theme

/**
 * The gap a word's tint may reach into: the whitespace after it, when a word
 * stands beyond it on the same line. Offsets of the gap, or null.
 *
 * A tinted word is clipped to its selection path where words share one layout,
 * and a selection stops at the word's advance — but a Hafs glyph inks past it:
 * the tail of a final و or ن sweeps out into the space after the word. Clipped
 * at the advance, that tail kept its plain ink and the orange ended on a
 * straight edge across the stroke.
 *
 * Only the gap *after* the word. The ink in it is this word's tail; the ink in
 * the gap before a word is the previous word's, and taking half of that tinted
 * the neighbour's tail whenever it crossed the middle. And never the space a
 * line wraps on: it is not a gap between two words on the paper, its box
 * reaches into the next line, and taking it lit the glimmer over words a line
 * below the one being said.
 *
 * [lineOf] is the layout's line for an offset.
 */
internal fun trailingWordGap(
    text: CharSequence,
    start: Int,
    endExclusive: Int,
    lineOf: (Int) -> Int,
): IntRange? {
    if (start < 0 || endExclusive <= start || endExclusive >= text.length) return null
    var after = endExclusive
    while (after < text.length && text[after].isWhitespace()) after++
    if (after == endExclusive || after >= text.length) return null
    // Lines only run forward, so the gap between two offsets on one line is on it too.
    if (lineOf(endExclusive - 1) != lineOf(after)) return null
    return endExclusive until after
}
