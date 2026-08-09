package com.beautifulquran.ui.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TarjiWordGateTest {
    @Test
    fun `one active word cannot relight from a later tail`() {
        val gate = TarjiWordGate()
        assertFalse(gate.allows(0f, detected = false))
        assertTrue(gate.allows(0.6f, detected = true))
        assertTrue(gate.allows(0.2f, detected = false))
        assertFalse(gate.allows(0f, detected = false))
        assertFalse(gate.allows(0.8f, detected = true))
    }

    @Test
    fun `a preceding word's release tail does not spend the event`() {
        val gate = TarjiWordGate()
        assertFalse(gate.allows(0.6f, detected = false))
        assertFalse(gate.allows(0.2f, detected = false))
        assertTrue(gate.allows(0.4f, detected = true))
    }

    @Test
    fun `the visual threshold belongs to the settled side`() {
        val gate = TarjiWordGate()
        assertTrue(gate.allows(0.2f, detected = true))
        assertFalse(gate.allows(0.01f, detected = true))
        assertFalse(gate.allows(0.4f, detected = true))
    }
}
