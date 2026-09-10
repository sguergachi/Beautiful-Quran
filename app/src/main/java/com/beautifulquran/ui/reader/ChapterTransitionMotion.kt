package com.beautifulquran.ui.reader

/** The held and released page use the same resistance curve, including partial pulls. */
internal fun previousChapterPullOffset(progress: Float, distance: Float): Float {
    val t = progress.coerceIn(0f, 1f)
    return distance * t * (2f - t)
}

/** Ink follows the arriving page gently, without a second sequential entrance. */
internal fun chapterVerseAlpha(progress: Float): Float {
    val t = ((progress - 0.08f) / 0.92f).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
