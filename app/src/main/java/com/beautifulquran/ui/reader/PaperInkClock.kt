package com.beautifulquran.ui.reader

/** The study's existing media-clock reveal and wall-clock glimmer dry-down. */
internal class PaperInkClock(
    val startMs: Long,
    val endMs: Long,
    private val fadeMs: Int,
) {
    var progress = 0f
        private set
    var wet = 0f
        private set
    /** Whether the current word may animate its advancing liquid contact. */
    var applying = false
        private set
    private var dryingAt = 0L
    private var dryingFrom = 0f

    /** Pausing freezes the pen but lets its ink dry; a seek clears future ink. */
    fun update(positionMs: Long, playing: Boolean, nowMs: Long) {
        progress = ((positionMs - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
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
