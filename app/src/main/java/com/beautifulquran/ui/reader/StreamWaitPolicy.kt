package com.beautifulquran.ui.reader

/**
 * Waiting on the stream download: the spinner is up and this reciting
 * performance has not spoken yet, so the whole page — the due verse and the
 * opening basmalah included — waits under Upcoming paper. Not just the
 * verse the voice is coming to.
 *
 * The spoken mark is performance-scoped ([ReaderScreen] keeps it): the word
 * parked by the performance before the pause is stale the moment play is
 * pressed again, so only a word heard inside the open session may hold the
 * page awake — that is what keeps a mid-chapter rebuffer frozen on the wash
 * instead of dimming it.
 */
internal fun readerWaitingOnStream(isBuffering: Boolean, spokenInPerformance: Boolean): Boolean =
    isBuffering && !spokenInPerformance

/**
 * How long [Player.STATE_BUFFERING] must hold before the page settles into
 * the wait. Media3 pulses buffering briefly between playlist items while
 * prefetch stitches; those dips must not flash the whole page dim.
 */
internal const val STREAM_WAIT_DEBOUNCE_MS = 500L
