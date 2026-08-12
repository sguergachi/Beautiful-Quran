package com.beautifulquran.tarjilab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TarjiLabCapturePolicyTest {
    @Test
    fun `capture waits for seek to land near target start`() {
        assertFalse(captureSeekHasLanded(positionMs = 13_900L, targetStartMs = 11_460L))
        assertTrue(captureSeekHasLanded(positionMs = 11_500L, targetStartMs = 11_460L))
        assertTrue(captureSeekHasLanded(positionMs = 12_180L, targetStartMs = 11_460L))
        assertFalse(captureSeekHasLanded(positionMs = 12_220L, targetStartMs = 11_460L))
    }
}
