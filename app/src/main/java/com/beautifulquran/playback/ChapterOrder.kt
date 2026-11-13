package com.beautifulquran.playback

/**
 * The order Chapters reads top to bottom: pinned chapters as they were pinned,
 * then every other chapter in book order. Autoplay walks this same page, so
 * what plays next is always what the reader can see below the current row.
 */
internal fun chapterListOrder(allIds: List<Int>, pinned: List<Int>): List<Int> {
    val onBand = pinned.filter { it in allIds }.distinct()
    return onBand + allIds.filterNot { it in onBand }
}

/** The chapter after [finished] down the page, or null at the foot of it. */
internal fun chapterAfter(finished: Int, allIds: List<Int>, pinned: List<Int>): Int? =
    chapterListOrder(allIds, pinned).let { order ->
        order.getOrNull(order.indexOf(finished) + 1).takeIf { finished in order }
    }
