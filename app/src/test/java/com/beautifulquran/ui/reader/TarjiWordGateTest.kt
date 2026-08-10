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
        val gate = TarjiWordGate()
        assertFalse(gate.allows(0.4f, detected = true, eventStartMs = 180L, wordStartMs = WORD_START))
        assertFalse(gate.allows(0.8f, detected = true, eventStartMs = 180L, wordStartMs = WORD_START))
        assertFalse(gate.allows(0.2f, detected = false, eventStartMs = 180L, wordStartMs = WORD_START))
        assertTrue(gate.allows(0.4f, detected = true, eventStartMs = 240L, wordStartMs = WORD_START))
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
        assertFalse(gate.allows(0.8f, detected = true, eventStartMs = 180L, wordStartMs = WORD_START))
        // A raw event may already exist, but the delayed signal still belongs
        // to the old event until its own delayed start arrives.
        assertFalse(gate.allows(0.8f, detected = true, eventStartMs = 180L, wordStartMs = WORD_START))
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
        assertTrue(gate.allows(0.2f, detected = true, eventStartMs = WORD_START + 1L, wordStartMs = WORD_START))
        assertFalse(gate.allows(0.01f, detected = true, eventStartMs = WORD_START + 1L, wordStartMs = WORD_START))
        assertFalse(gate.allows(0.4f, detected = true, eventStartMs = WORD_START + 1L, wordStartMs = WORD_START))
    }

    private companion object {
        const val WORD_START = 200L
    }
}
