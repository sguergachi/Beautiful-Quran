package com.beautifulquran.playback

/**
 * What plays after a chapter ends (Play on to the next chapter), and what the
 * reader's NEXT footer offers: the next chapter in book order. Pinning does
 * not reorder either — a pin queue was tried and read as confusing, since the
 * chapter that plays next stopped being the chapter the footer and the book
 * name. The book's final chapter has no next: playback ends there.
 */
internal fun chapterAfter(finished: Int, allIds: List<Int>): Int? {
    val index = allIds.indexOf(finished)
    return if (index >= 0) allIds.getOrNull(index + 1) else null
}
