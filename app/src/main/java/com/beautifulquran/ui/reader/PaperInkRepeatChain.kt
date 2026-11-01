package com.beautifulquran.ui.reader

/** The study's repeat tint holds across word handoffs, independently of wet ink. */
internal class PaperInkRepeatChain(private val fadeMs: Int) {
    var alpha = 0f
        private set
    private var holding = false
    private var releasedAt = 0L

    /** Pausing within a chain retains its color; leaving the chain releases it together. */
    fun update(repeating: Boolean, nowMs: Long) {
        if (repeating) {
            alpha = 1f
        } else {
            if (holding) releasedAt = nowMs
            if (alpha > 0f) {
                val phase = ((nowMs - releasedAt).toFloat() / fadeMs.coerceAtLeast(1)).coerceIn(0f, 1f)
                alpha = 1f - InkEngine.sweepEasing.transform(phase)
            }
        }
        holding = repeating
    }

    fun reset() {
        alpha = 0f
        holding = false
    }
}
