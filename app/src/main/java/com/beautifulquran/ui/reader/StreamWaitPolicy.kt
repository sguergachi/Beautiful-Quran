package com.beautifulquran.ui.reader

/**
 * Waiting on the stream download: while the spinner holds, the whole page —
 * the recited tail and the opening basmalah included — waits under Upcoming
 * paper (user rule). The wait is the buffering itself, not a question of
 * whether anything has been spoken: a mid-recitation stall or an
 * item-boundary join dims the lit words exactly as a fresh play does. The
 * page fades as one synchronized action — no engage delay — and wakes when
 * the buffer has carried the voice for [STREAM_WAIT_RELEASE_HOLD_MS].
 */

/** How long the buffer must carry the voice before the page wakes from the
 * stream wait. A starving stream alternates brief ready windows with
 * buffering; waking on the first would strobe the page bright between
 * download bursts instead of holding the one simultaneous fade. */
internal const val STREAM_WAIT_RELEASE_HOLD_MS = 700L

