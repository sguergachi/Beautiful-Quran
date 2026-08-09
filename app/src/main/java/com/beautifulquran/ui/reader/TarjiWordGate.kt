package com.beautifulquran.ui.reader

/**
 * Allows one acoustic tarjīʿ event during a word utterance. It ignores gain
 * inherited from the preceding word until the delayed detector is live; once
 * its own event settles, later consonant or room-echo pulses cannot relight it.
 */
internal class TarjiWordGate {
    private var heard = false
    private var finished = false

    fun allows(gain: Float, detected: Boolean): Boolean {
        if (finished) return false
        // A fresh word may inherit the preceding word's fading gain. It has
        // not heard its own event until the delayed detector is live too.
        if (!heard && !detected) return false
        if (gain > MIN_GAIN) {
            heard = true
            return true
        }
        if (heard) finished = true
        return false
    }

    private companion object {
        const val MIN_GAIN = 0.01f
    }
}
