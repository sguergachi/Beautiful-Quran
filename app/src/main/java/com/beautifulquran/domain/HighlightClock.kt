package com.beautifulquran.domain

/**
 * Smooths the player's raw position samples into the clock the word
 * highlight reads.
 *
 * `MediaController.currentPosition` is an estimate extrapolated from the
 * session's last update, so two consecutive polls can step *backward* by a
 * few dozen milliseconds when a fresh update lands. When such a step crosses
 * a word-segment boundary, the active word bounces back to the previous word
 * for one 33 ms tick and then forward again. The renderer treats that bounce
 * as a real re-activation and restarts the word's reveal — the word flashes
 * full ink, drops to the faint upcoming floor, then sweeps in again. Because
 * it needs a poll tick, an update arrival, and a word boundary to line up,
 * it strikes random words at random times.
 *
 * After a seek or media-item handoff the estimate is worse still: the first
 * samples can overshoot by hundreds of milliseconds and then snap back. That
 * lands on word 2 or 3, starts its ink wash, then bounces to an earlier word
 * and re-enters — a full reset of the sweep. With tajweed pacing the wash is
 * parked mid-letter on a hold, so the same bounce is obvious instead of
 * silent (the plain sweep has usually already reached full ink).
 *
 * The clock therefore:
 * - never moves backward within one [key] for small regressions (jitter)
 * - after a key change, [acceptNextSample], or a large accepted seek, enters
 *   a **settle** window that holds *all* regressions and ignores implausible
 *   forward jumps, so post-seek corrections cannot bounce the word. The
 *   window lasts at least [MIN_SETTLE_POLLS] polls and ends once the
 *   estimate has tracked realtime playback for [STABLE_POLLS_NEEDED]
 *   consecutive polls — an estimate that is still creeping never converges,
 *   so its eventual snap-back stays inside the window no matter how late it
 *   arrives — with a hard cap at [SETTLE_CAP_POLLS] polls so a pathological
 *   stream cannot wedge the clock.
 * - still accepts a large backward step outside settle as a genuine seek
 *   (loop restart, scrub) and re-arms settle
 */
class HighlightClock(
    private val seekThresholdMs: Long = SEEK_THRESHOLD_MS,
    private val minSettlePolls: Int = MIN_SETTLE_POLLS,
    private val stablePollsNeeded: Int = STABLE_POLLS_NEEDED,
    private val settleCapPolls: Int = SETTLE_CAP_POLLS,
    private val maxSettleStepMs: Long = MAX_SETTLE_STEP_MS,
    private val believableStepMs: Long = BELIEVABLE_STEP_MS,
) {

    private var key: Any? = null
    private var clockMs = 0L
    /** When true, the next [sample] accepts [rawMs] without the jitter hold —
     * used after a user word-tap / seek so ink tracks the new position. */
    private var acceptNext = false
    /** Polls since the last arm (settle bookkeeping). */
    private var settlePolls = 0
    /** Consecutive polls where the raw series tracked realtime playback. */
    private var stablePolls = 0

    /** Settle ends only when the window ran its minimum *and* the estimate
     * has converged onto realtime playback (or the cap forces it). */
    private val inSettle: Boolean
        get() = settlePolls < settleCapPolls &&
            (settlePolls < minSettlePolls || stablePolls < stablePollsNeeded)

    /** [rawMs] filtered: monotonic within one [key], except genuine seeks. */
    fun sample(key: Any, rawMs: Long): Long {
        if (acceptNext) {
            acceptNext = false
            return arm(key, rawMs)
        }
        if (key != this.key) {
            return arm(key, rawMs)
        }
        val regression = clockMs - rawMs
        val settling = inSettle
        if (regression > 0) {
            // Settle holds every backward step; outside settle only small jitter.
            if (settling || regression < seekThresholdMs) {
                stablePolls = 0
                if (settling) settlePolls++
                return clockMs
            }
            // Genuine large seek (loop restart, unnoted scrub).
            return arm(key, rawMs)
        }
        val advance = rawMs - clockMs
        if (settling && advance > maxSettleStepMs) {
            // Post-seek estimate overshot — ignore until the playhead is
            // believable again rather than lighting word 2/3 early.
            stablePolls = 0
            settlePolls++
            return clockMs
        }
        clockMs = rawMs
        stablePolls = if (advance <= believableStepMs) stablePolls + 1 else 0
        if (settling) settlePolls++
        return rawMs
    }

    /**
     * Next [sample] must take the raw position (word tap, scrub, loop).
     * Without this, a short backward seek is held as jitter and the ink
     * wash never restarts on the word the listener just played.
     */
    fun acceptNextSample() {
        acceptNext = true
    }

    private fun arm(key: Any, rawMs: Long): Long {
        this.key = key
        clockMs = rawMs
        settlePolls = 0
        stablePolls = 0
        return rawMs
    }

    companion object {
        /** Backward steps smaller than this are sampling jitter and held;
         * larger ones outside settle are real seeks (loop restart) and pass. */
        const val SEEK_THRESHOLD_MS = 250L

        /** Minimum settle length (~400 ms at the reader's 33 ms tick). */
        const val MIN_SETTLE_POLLS = 12

        /** Consecutive realtime-tracking polls that prove the estimate has
         * converged and settle may end. */
        const val STABLE_POLLS_NEEDED = 4

        /** Hard ceiling on settle (~1.5 s) so a stream that never converges
         * cannot hold regressions forever. */
        const val SETTLE_CAP_POLLS = 45

        /** Largest forward step accepted during settle (~3× realtime at 33 ms). */
        const val MAX_SETTLE_STEP_MS = 100L

        /** A forward step at most this large (~2× realtime) counts as the
         * estimate tracking playback rather than still creeping. */
        const val BELIEVABLE_STEP_MS = 66L
    }
}
