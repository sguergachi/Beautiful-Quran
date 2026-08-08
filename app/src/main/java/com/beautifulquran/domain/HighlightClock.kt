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
 * - after a key change, [acceptNextSample], or a large accepted seek, enters a
 *   short **settle** window that holds *all* regressions and ignores
 *   implausible forward jumps, so post-seek corrections cannot bounce the word
 * - still accepts a large backward step outside settle as a genuine seek
 *   (loop restart, scrub) and re-arms settle
 */
class HighlightClock(
    private val seekThresholdMs: Long = SEEK_THRESHOLD_MS,
    private val settleSamples: Int = SETTLE_SAMPLES,
    private val maxSettleStepMs: Long = MAX_SETTLE_STEP_MS,
) {

    private var key: Any? = null
    private var clockMs = 0L
    /** When true, the next [sample] accepts [rawMs] without the jitter hold —
     * used after a user word-tap / seek so ink tracks the new position. */
    private var acceptNext = false
    /** Polls remaining in the post-seek settle window. */
    private var settleLeft = 0

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
        if (regression > 0) {
            // Settle holds every backward step; outside settle only small jitter.
            if (settleLeft > 0 || regression < seekThresholdMs) {
                tickSettle()
                return clockMs
            }
            // Genuine large seek (loop restart, unnoted scrub).
            return arm(key, rawMs)
        }
        val advance = rawMs - clockMs
        if (settleLeft > 0 && advance > maxSettleStepMs) {
            // Post-seek estimate overshot — ignore until the playhead is
            // believable again rather than lighting word 2/3 early.
            tickSettle()
            return clockMs
        }
        clockMs = rawMs
        tickSettle()
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
        settleLeft = settleSamples
        return rawMs
    }

    private fun tickSettle() {
        if (settleLeft > 0) settleLeft--
    }

    companion object {
        /** Backward steps smaller than this are sampling jitter and held;
         * larger ones outside settle are real seeks (loop restart) and pass. */
        const val SEEK_THRESHOLD_MS = 250L

        /**
         * Polls after a seek/handoff during which every regression is held and
         * forward jumps larger than [MAX_SETTLE_STEP_MS] are ignored.
         * ~1.2 s at the reader's 33 ms tick — long enough that a slow-converging
         * post-handoff estimate (the audio pipeline can take the better part of
         * a second to settle its playhead after an item change) snaps back
         * *inside* the window instead of being read as a seek and replaying
         * word 2/3's wash.
         */
        const val SETTLE_SAMPLES = 36

        /** Largest forward step accepted during settle (~3× realtime at 33 ms). */
        const val MAX_SETTLE_STEP_MS = 100L
    }
}
