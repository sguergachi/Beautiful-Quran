package com.beautifulquran.ui.reader

import com.beautifulquran.playback.VoiceEnergy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TarjiWordGateTest {
    @Test
    fun `one active word cannot relight from a later tail`() {
        val gate = TarjiWordGate()
        assertFalse(
            gate.allows(
                gain = 0f,
                detected = false,
                eventStartMs = VoiceEnergy.NO_EVENT_MS,
                wordStartMs = WORD_START,
            ),
        )
        assertTrue(gate.allows(0.6f, detected = true, eventStartMs = 220L, wordStartMs = WORD_START))
        assertTrue(gate.allows(0.2f, detected = false, eventStartMs = 220L, wordStartMs = WORD_START))
        assertFalse(gate.allows(0f, detected = false, eventStartMs = 220L, wordStartMs = WORD_START))
        assertFalse(gate.allows(0.8f, detected = true, eventStartMs = 300L, wordStartMs = WORD_START))
    }

    @Test
    fun `a preceding word's release tail does not spend the event`() {
        val gate = TarjiWordGate()
        assertFalse(
            gate.allows(
                gain = 0.6f,
                detected = false,
                eventStartMs = VoiceEnergy.NO_EVENT_MS,
                wordStartMs = WORD_START,
            ),
        )
        assertFalse(
            gate.allows(
                gain = 0.2f,
                detected = false,
                eventStartMs = VoiceEnergy.NO_EVENT_MS,
                wordStartMs = WORD_START,
            ),
        )
        assertTrue(gate.allows(0.4f, detected = true, eventStartMs = 220L, wordStartMs = WORD_START))
    }

    @Test
    fun `an event already in progress when a word starts cannot tint that word`() {
        // Long underway: it belongs to an earlier utterance.
        val gate = TarjiWordGate()
        val long = WORD_START - 1_000L
        assertFalse(gate.allows(0.4f, detected = true, eventStartMs = long, wordStartMs = WORD_START))
        assertFalse(gate.allows(0.8f, detected = true, eventStartMs = long, wordStartMs = WORD_START))
        assertFalse(gate.allows(0.2f, detected = false, eventStartMs = long, wordStartMs = WORD_START))
        assertTrue(gate.allows(0.4f, detected = true, eventStartMs = 240L, wordStartMs = WORD_START))
        // Just begun, but the word before has already shown it.
        val ledger = TarjiEventLedger()
        val before = TarjiWordGate(ledger)
        assertTrue(before.allows(0.6f, detected = true, eventStartMs = 180L, wordStartMs = 0L))
        val next = TarjiWordGate(ledger)
        assertFalse(next.allows(0.8f, detected = true, eventStartMs = 180L, wordStartMs = WORD_START))
        assertTrue(next.allows(0.4f, detected = true, eventStartMs = 900L, wordStartMs = WORD_START))
    }

    @Test
    fun `a hold caught just before the word's mark is the word's own`() {
        // Word marks are not that exact: an event the detector caught a few
        // hundred ms early, which no other word showed, lights this word.
        val gate = TarjiWordGate(TarjiEventLedger())
        assertTrue(gate.allows(0.5f, detected = true, eventStartMs = WORD_START - 420L, wordStartMs = WORD_START))
        // Replaying the same audio finds the same owner.
        val ledger = TarjiEventLedger()
        assertTrue(TarjiWordGate(ledger).allows(0.5f, true, WORD_START - 420L, WORD_START))
        assertTrue(TarjiWordGate(ledger).allows(0.5f, true, WORD_START - 420L, WORD_START))
    }

    @Test
    fun `a slight early event does not spend the word's reverberation`() {
        // The syllable's own attack can open a brief, weak event; the hold's
        // real reverberation follows. Refusing it depended on hop phase.
        val gate = TarjiWordGate()
        assertTrue(gate.allows(0.12f, detected = true, eventStartMs = 240L, wordStartMs = WORD_START))
        assertFalse(gate.allows(0f, detected = false, eventStartMs = 240L, wordStartMs = WORD_START))
        assertTrue(gate.allows(0.7f, detected = true, eventStartMs = 900L, wordStartMs = WORD_START))
        // Once a real one has been seen, a later tail still cannot relight it.
        assertFalse(gate.allows(0f, detected = false, eventStartMs = 900L, wordStartMs = WORD_START))
        assertFalse(gate.allows(0.8f, detected = true, eventStartMs = 2_000L, wordStartMs = WORD_START))
    }

    @Test
    fun `delayed event identity prevents a raw generation from borrowing old gain`() {
        val gate = TarjiWordGate()
        assertFalse(
            gate.allows(
                gain = 0f,
                detected = false,
                eventStartMs = VoiceEnergy.NO_EVENT_MS,
                wordStartMs = WORD_START,
            ),
        )
        assertFalse(gate.allows(0.8f, detected = true, eventStartMs = -1_000L, wordStartMs = WORD_START))
        // A raw event may already exist, but the delayed signal still belongs
        // to the old event until its own delayed start arrives.
        assertFalse(gate.allows(0.8f, detected = true, eventStartMs = -1_000L, wordStartMs = WORD_START))
        assertTrue(gate.allows(0.4f, detected = true, eventStartMs = 240L, wordStartMs = WORD_START))
    }

    @Test
    fun `an event after word entry is admitted`() {
        val gate = TarjiWordGate()
        assertFalse(
            gate.allows(
                gain = 0f,
                detected = false,
                eventStartMs = VoiceEnergy.NO_EVENT_MS,
                wordStartMs = WORD_START,
            ),
        )
        assertTrue(gate.allows(0.5f, detected = true, eventStartMs = WORD_START + 1L, wordStartMs = WORD_START))
        assertFalse(gate.allows(0.01f, detected = true, eventStartMs = WORD_START + 1L, wordStartMs = WORD_START))
        assertFalse(gate.allows(0.4f, detected = true, eventStartMs = WORD_START + 1L, wordStartMs = WORD_START))
    }

    private companion object {
        const val WORD_START = 200L
    }
}
