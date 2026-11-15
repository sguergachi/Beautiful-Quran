package com.beautifulquran.playback

/**
 * What plays after a chapter ends (Play on to the next chapter). The pinned
 * chapters are a playlist first: a pinned chapter hands to the next pin in
 * pin order. When the pins run out — or the chapter was never pinned — the
 * book resumes where the finished chapter sits: the next chapter in book
 * order, skipping anything pinned (the band plays those in its own turn).
 * A pinned book-final chapter wraps to the top of the book; an unpinned
 * one ends it.
 */
internal fun chapterAfter(finished: Int, allIds: List<Int>, pinned: List<Int>): Int? {
    val finishedIndex = allIds.indexOf(finished)
    if (finishedIndex < 0) return null
    val band = pinned.filter { it in allIds }.distinct()
    val pinIndex = band.indexOf(finished)
    if (pinIndex >= 0 && pinIndex < band.lastIndex) return band[pinIndex + 1]
    val rest = allIds.filterNot { it in band }
    val after = allIds.drop(finishedIndex + 1).firstOrNull { it in rest }
    // Only a pinned chapter wraps: the book's last chapter, pinned, still
    // hands on to the top of the book as the band promised.
    return after ?: if (pinIndex >= 0) rest.firstOrNull() else null
}
