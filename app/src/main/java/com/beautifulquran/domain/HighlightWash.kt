package com.beautifulquran.domain

/** A captured occurrence window on the calibrated media clock, independent of frame rate. */
data class HighlightWashWindow(
    val surah: Int,
    val ayah: Int,
    val inkGeneration: Long,
    val startMs: Long,
    val durationMs: Long,
    val reciterId: Int? = null,
) {
    fun matches(frame: HighlightFrame): Boolean = frame.item.surah == surah && frame.item.ayah == ayah &&
        frame.inkGeneration == inkGeneration && (reciterId == null || frame.item.reciter == reciterId)

    fun progress(frame: HighlightFrame): Float {
        if (durationMs <= 0L) return if (frame.inkMs >= startMs) 1f else 0f
        return ((frame.inkMs - startMs).toDouble() / durationMs).toFloat().coerceIn(0f, 1f)
    }
}

/** Entry and residual policy shared by ordinary ink and occurrence-aware repeat ink. */
data class HighlightWashState(
    val active: Boolean = false,
    val activation: Long = 0L,
    val window: HighlightWashWindow? = null,
    val provisional: Boolean = false,
)

/** Pure motion lifecycle. Drawing projects the captured window; it never starts a wall-time tween. */
object HighlightWash {
    /**
     * Ordinary ink rearms only on an active edge or explicit ink activation.
     * Repeat ink additionally rearms for a new occurrence of the same word.
     * An acknowledged tap can replace its provisional window without replaying it.
     * Actual player generation and reciter remain authoritative when UI activation is pinned.
     */
    fun transition(
        previous: HighlightWashState,
        active: Boolean,
        retain: Boolean,
        activation: Long,
        window: HighlightWashWindow?,
        provisional: Boolean = false,
        repeatOccurrence: Boolean = false,
    ): HighlightWashState {
        if (!active) {
            return if (retain) previous.copy(active = false) else HighlightWashState()
        }
        if (window == null) return HighlightWashState(active = true, activation = activation)
        val rearm = !previous.active || previous.activation != activation || previous.window == null ||
            previous.window.inkGeneration != window.inkGeneration || previous.window.surah != window.surah ||
            previous.window.ayah != window.ayah || previous.window.reciterId != window.reciterId || repeatOccurrence && previous.window.startMs != window.startMs
        if (rearm || previous.provisional && !provisional) {
            return HighlightWashState(true, activation, window, provisional)
        }
        return previous
    }

    /** A stale performance cannot advance a provisional target or replay an earlier residual. */
    fun progress(state: HighlightWashState, frame: HighlightFrame?): Float {
        val window = state.window ?: return if (state.active) 0f else 1f
        if (state.provisional) return 0f
        return if (frame != null && window.matches(frame)) window.progress(frame) else 1f
    }
}
