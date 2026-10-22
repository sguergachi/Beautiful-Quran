package com.beautifulquran.ui.reader

/**
 * Waiting on the stream download: while the spinner holds, the whole page —
 * the recited tail and the opening basmalah included — waits under Upcoming
 * paper (user rule). The wait is the buffering itself, not a question of
 * whether anything has been spoken: a mid-recitation stall or an
 * item-boundary join dims the lit words exactly as a fresh play does, and
 * the page wakes when the buffer carries the voice again.
 */

/** How long [Player.STATE_BUFFERING] must hold before the page settles into
 * the wait. Media3 pulses buffering briefly between playlist items while
 * prefetch stitches; those dips must not flash the whole page dim. */
internal const val STREAM_WAIT_DEBOUNCE_MS = 500L
