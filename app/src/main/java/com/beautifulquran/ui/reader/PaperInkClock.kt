package com.beautifulquran.ui.reader

/** Reading-order wash on the media clock, followed by the shared glimmer dry-down. */
internal class PaperInkClock(
    val startMs: Long,
    val endMs: Long,
    private val fadeMs: Int,
) {
    var progress = 0f
        private set
    var wet = 0f
        private set
    /** Before the word starts: how far ahead it is, in its own durations (-1..0). */
    var ahead = -1f
        private set
    /** Whether the reciter is currently applying fresh ink to this word. */
    var applying = false
        private set
    private var dryingAt = 0L
    private var dryingFrom = 0f

    /** Pausing freezes filling but lets ink dry; a seek clears future ink. */
    fun update(positionMs: Long, playing: Boolean, nowMs: Long) {
        val phase = (positionMs - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1)
        progress = phase.coerceIn(0f, 1f)
        ahead = phase.coerceIn(-1f, 0f)
        val active = playing && positionMs >= startMs && positionMs < endMs
        if (positionMs < startMs) {
            wet = 0f
            dryingFrom = 0f
        } else if (active) {
            wet = 1f
        } else {
            if (applying) {
                dryingAt = nowMs
                dryingFrom = wet
            }
            val phase = ((nowMs - dryingAt).toFloat() / fadeMs.coerceAtLeast(1)).coerceIn(0f, 1f)
            wet = dryingFrom * (1f - InkEngine.sweepEasing.transform(phase))
        }
        applying = active
    }
}
