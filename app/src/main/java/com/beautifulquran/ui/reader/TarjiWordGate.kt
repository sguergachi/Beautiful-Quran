package com.beautifulquran.ui.reader

/**
 * Allows one acoustic tarjīʿ event during a word utterance. It ignores gain
 * inherited from the preceding word until the delayed detector is live; once
 * its own event settles, later consonant or room-echo pulses cannot relight it.
 */
internal class TarjiWordGate {
    private var heard = false
    private var finished = false

    fun allows(
        gain: Float,
        detected: Boolean,
        eventStartMs: Long,
        wordStartMs: Long,
    ): Boolean {
        if (finished) return false
        // Event start and word start share the playback media clock. An event
        // already underway belongs to the preceding utterance; a delayed
        // event start cannot be paired with a newer raw detector generation.
        val priorEvent = eventStartMs == NO_EVENT_MS ||
            wordStartMs == NO_EVENT_MS ||
            eventStartMs < wordStartMs
        if (!heard && (!detected || priorEvent)) return false
        if (gain > MIN_GAIN) {
            heard = true
            return true
        }
        if (heard) finished = true
        return false
    }

    private companion object {
        const val MIN_GAIN = 0.01f
        const val NO_EVENT_MS = Long.MIN_VALUE
    }
}
