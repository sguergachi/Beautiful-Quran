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

/**
 * The reader's NEXT chapter for the chapter on screen. A pinned chapter
 * leads where the Play-on queue leads ([chapterAfter]: the next pin, then
 * the book where it sits); any other chapter keeps the book's next,
 * pinned or not.
 */
internal fun readerNextChapter(currentId: Int, allIds: List<Int>, pinned: List<Int>): Int? {
    val band = pinned.filter { it in allIds }.distinct()
    if (currentId in band) return chapterAfter(currentId, allIds, pinned)
    val index = allIds.indexOf(currentId)
    return if (index >= 0) allIds.getOrNull(index + 1) else null
}
