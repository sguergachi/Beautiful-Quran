package com.beautifulquran.ui.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Pinned chapters are an ordered list, and a row's grip carries it to another
 * seat in the band. The rules for that live here, free of the screen, so they
 * can be tested.
 */

/** [order] with [id] carried to seat [toIndex] (clamped to the band). A chapter
 * that is not pinned leaves the order as it was. */
internal fun movePinned(order: List<Int>, id: Int, toIndex: Int): List<Int> {
    val from = order.indexOf(id)
    if (from < 0) return order
    val to = toIndex.coerceIn(0, order.lastIndex)
    if (to == from) return order
    return order.toMutableList().apply {
        removeAt(from)
        add(to, id)
    }
}

/** A chapter's seat in the band, as laid out: its [index] among the pins and
 * where it sits in the list's own space. */
internal data class PinSeat(val index: Int, val top: Int, val size: Int)

/**
 * The seat a lifted row's [center] has reached: the one whose rows it
 * overlaps, or the nearest end of the band when it is carried past it. Null
 * when no band row is on screen to answer.
 */
internal fun seatAt(center: Float, seats: List<PinSeat>): Int? {
    if (seats.isEmpty()) return null
    seats.firstOrNull { center >= it.top && center < it.top + it.size }?.let { return it.index }
    val first = seats.minBy { it.top }
    val last = seats.maxBy { it.top }
    return if (center < first.top) first.index else last.index
}

/**
 * A pin order the list may adopt: only a rearrangement of the pins already
 * kept. Anything else (a drop landing after the chapter was unpinned from
 * elsewhere) keeps the order that is stored.
 */
internal fun acceptPinOrder(current: List<Int>, proposed: List<Int>): List<Int> =
    if (proposed.size == current.size && proposed.toSet() == current.toSet() &&
        proposed.distinct().size == proposed.size
    ) {
        proposed
    } else {
        current
    }

/**
 * The one lifted row. [center] is where its middle is held, in the list's
 * viewport space; [draft] is the band's order while it is carried (null at
 * rest), kept until the stored order has caught up so the rows never
 * flicker back for a frame.
 */
@Stable
internal class PinReorderState {
    var liftedId by mutableStateOf<Int?>(null)
    var center by mutableFloatStateOf(0f)
    var draft by mutableStateOf<List<Int>?>(null)
}
