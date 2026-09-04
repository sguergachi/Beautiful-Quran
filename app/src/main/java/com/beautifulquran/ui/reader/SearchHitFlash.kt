package com.beautifulquran.ui.reader

import androidx.compose.animation.core.LinearEasing

/**
 * Timing for the cover-sheet search-hit locator: one uninterrupted loop of
 * narrow directional orange wipes.
 */
object SearchHitFlash {
    /** Pause after the initial ayah focus so the word is on-screen first. */
    const val START_DELAY_MS = 140L

    const val SWEEP_MS = 720
    const val WIPES = 4
    /** Orange window width and its soft leading/trailing edges. */
    const val BAND_FRACTION = 0.72f
    const val EDGE_SHARE = 0.24f
    /** The rest of the chapter yields just enough to make every target legible. */
    const val BACKGROUND_ALPHA = 0.4f
    const val FOCUS_FADE_MS = 280

    /** Constant velocity keeps one pass flowing directly into the next. */
    val EASING = LinearEasing

    /** Tight glyph-following spread that makes the orange fill read heavier. */
    const val EMPHASIS_GLOW_ALPHA = 0.92f
    const val EMPHASIS_GLOW_RADIUS = 1.2f

    /** One visible side wipe. */
    fun wipeMs(): Long = SWEEP_MS.toLong()

    /** Total animation time after [START_DELAY_MS]. */
    fun totalMs(): Long = WIPES * wipeMs()

    /** The scrolling reader and Mushaf have different focus authorities. */
    internal fun isTargetSettled(
        mushafMode: Boolean,
        scrollingVerseSettled: Boolean,
        mushafLeafSettled: Boolean,
    ): Boolean = if (mushafMode) mushafLeafSettled else scrollingVerseSettled

    /** Exact text ranges for a translator-only hit; prefix matches own the full word. */
    internal fun textRanges(text: String, rawQuery: String?): List<IntRange> {
        val query = rawQuery?.trim()?.let { value ->
            if (value.length >= 2 && value.first() in setOf('"', '“') &&
                value.last() in setOf('"', '”')
            ) {
                value.substring(1, value.lastIndex).trim()
            } else {
                value
            }
        }.orEmpty()
        if (query.isEmpty()) return emptyList()
        val expandWord = query.all(Char::isLetterOrDigit)
        return buildList {
            var from = 0
            while (from < text.length) {
                val match = text.indexOf(query, from, ignoreCase = true)
                if (match < 0) break
                var start = match
                var end = match + query.length
                if (expandWord) {
                    while (start > 0 && text[start - 1].isLetterOrDigit()) start--
                    while (end < text.length && text[end].isLetterOrDigit()) end++
                }
                add(start until end)
                from = end.coerceAtLeast(match + 1)
            }
        }.distinct()
    }
}
