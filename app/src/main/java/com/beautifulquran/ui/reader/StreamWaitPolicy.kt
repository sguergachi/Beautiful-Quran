package com.beautifulquran.ui.reader

/**
 * Waiting on the stream download: the spinner is up and the reciter has not
 * spoken for this performance, so the whole page — the due verse and the
 * opening basmalah included — waits under Upcoming paper. Not just the
 * verse the voice is coming to.
 *
 * The wait is gated on the absence of a recited word anchor ([ActiveWord]
 * from the 33 ms poll): a mid-chapter rebuffer keeps the last spoken word
 * lit and the ink frozen, and must not dim the page the wash left behind.
 * A provisionally-lit tap word ([ActiveWord.fromTap]) does not count as
 * spoken — until audio talks, the voice owns nothing but the wait.
 */
internal fun readerWaitingOnStream(isBuffering: Boolean, activeWord: ActiveWord?): Boolean =
    isBuffering && activeWord?.takeIf { !it.fromTap } == null

/**
 * How long [Player.STATE_BUFFERING] must hold before the page settles into
 * the wait. Media3 pulses buffering briefly between playlist items while
 * prefetch stitches; those dips must not flash the whole page dim.
 */
internal const val STREAM_WAIT_DEBOUNCE_MS = 500L
