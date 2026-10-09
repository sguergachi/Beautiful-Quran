package com.beautifulquran.ui.reader

/**
 * Which word has shown which acoustic event, shared by every word's
 * [TarjiWordGate] in one reader so an event lights one word only. Keyed by
 * the event's start on the media-item clock: a replay of the same audio finds
 * the same owner. Main thread only.
 */
internal class TarjiEventLedger {
    private val owners = object : LinkedHashMap<Long, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?) = size > 64
    }

    /** True when a word other than [wordStartMs] has already shown [eventStartMs]. */
    fun shownElsewhere(eventStartMs: Long, wordStartMs: Long): Boolean =
        owners[eventStartMs]?.let { it != wordStartMs } ?: false

    fun show(eventStartMs: Long, wordStartMs: Long) {
        owners.putIfAbsent(eventStartMs, wordStartMs)
    }
}

/**
 * Allows one acoustic tarjīʿ event during a word utterance. It ignores gain
 * inherited from the preceding word until the delayed detector is live; once
 * its own event settles, later consonant or room-echo pulses cannot relight it.
 *
 * Two loosenings, both found replaying real recitations through the reader's
 * chain (consecutive verses, played on and played afresh):
 *  - A brief, weak event — often the syllable's own attack — no longer spends
 *    the word. Only one that reached [SPEND_GAIN] does; until then a later
 *    event may still light it. Otherwise the reverberation that followed was
 *    refused, and whether it was depended on where the 20 ms hops fell.
 *  - An event the detector caught up to [PRIOR_TOLERANCE_MS] before the word's
 *    timing mark is this word's, unless another word has already shown it
 *    ([ledger]). Word marks are not that exact, and a hold heard a few hundred
 *    ms early was refused as the previous word's although that word never
 *    showed it.
 */
internal class TarjiWordGate(private val ledger: TarjiEventLedger = TarjiEventLedger()) {
    private var heard = false
    private var finished = false
    private var peak = 0f
    private var heardEvent = NO_EVENT_MS

    fun allows(
        gain: Float,
        detected: Boolean,
        eventStartMs: Long,
        wordStartMs: Long,
    ): Boolean {
        if (finished) return false
        // Event start and word start share the playback media clock. An event
        // well underway before the word belongs to the preceding utterance;
        // a delayed event start cannot be paired with a newer raw detector
        // generation.
        val priorEvent = eventStartMs == NO_EVENT_MS ||
            wordStartMs == NO_EVENT_MS ||
            eventStartMs < wordStartMs - PRIOR_TOLERANCE_MS ||
            ledger.shownElsewhere(eventStartMs, wordStartMs)
        if (!heard && (!detected || priorEvent)) return false
        if (gain > MIN_GAIN) {
            if (!heard) heardEvent = eventStartMs
            heard = true
            peak = maxOf(peak, gain)
            if (heardEvent != NO_EVENT_MS) ledger.show(heardEvent, wordStartMs)
            return true
        }
        if (heard) {
            if (peak >= SPEND_GAIN) {
                finished = true
            } else {
                // Too slight to have been the word's reverberation: let the
                // next event in.
                heard = false
                peak = 0f
                heardEvent = NO_EVENT_MS
            }
        }
        return false
    }

    private companion object {
        const val MIN_GAIN = 0.01f
        /** Peak gain at which an event has been seen, and spends the word. */
        const val SPEND_GAIN = 0.3f
        /** How early before its timing mark a word's own event may be caught. */
        const val PRIOR_TOLERANCE_MS = 600L
        const val NO_EVENT_MS = Long.MIN_VALUE
    }
}
