package com.beautifulquran.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sound-effects switch is opt-out: every reader starts with it on. */
class SoundEffectsSettingsTest {

    @Test
    fun `sound effects default on and toggle through copy`() {
        assertTrue(Settings().soundEffectsEnabled)
        val off = Settings().copy(soundEffectsEnabled = false)
        assertFalse(off.soundEffectsEnabled)
        assertTrue(off.copy(soundEffectsEnabled = true).soundEffectsEnabled)
    }
}
