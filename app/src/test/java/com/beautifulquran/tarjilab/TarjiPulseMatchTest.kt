package com.beautifulquran.tarjilab

import com.beautifulquran.playback.Tarji
import com.beautifulquran.playback.TarjiLabCapture
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class TarjiPulseMatchTest {
    private fun note(rate: Float, volumeDepth: Float = 0.15f, pitchCents: Float = 0f): FloatArray {
        var phase = 0f
        return FloatArray(2 * Tarji.SAMPLE_RATE) { i ->
            val wobble = sin(2f * PI.toFloat() * rate * i / Tarji.SAMPLE_RATE)
            phase += 2f * PI.toFloat() * 150f * 2f.pow(pitchCents * wobble / 1200f) / Tarji.SAMPLE_RATE
            0.3f * (1f + volumeDepth * wobble) * sin(phase)
        }
    }

    private fun capture(pcm: FloatArray): TarjiLabCapture = TarjiLabCapture(
        Tarji.SAMPLE_RATE, Tarji.HOP_SAMPLES,
        FloatArray(pcm.size / Tarji.HOP_SAMPLES) { 800f + it * 20f }, pcm,
    )

    @Test
    fun `match finds fast wavering even when current band and sensitivity reject it`() {
        val audio = capture(note(9f, volumeDepth = 0.03f))
        val knobs = TarjiLabKnobs(minTremoloHz = 1.5f, maxTremoloHz = 3f, minTremoloDepth = 0.25f)
        val match = matchTarjiPulse(audio, TarjiHoldWindow(0f, 2000f), knobs)!!
        assertEquals(9f, match.rateHz, 0.7f)
        assertTrue(9f in match.minHz..match.maxHz)
        assertTrue(match.maxHz <= 10f)
        assertEquals(3f, knobs.maxTremoloHz, 0f)
        assertEquals(0.25f, knobs.minTremoloDepth, 0f)
    }

    @Test
    fun `match measures pitch-only vibrato`() {
        val match = matchTarjiPulse(capture(note(5.5f, volumeDepth = 0f, pitchCents = 30f)),
            TarjiHoldWindow(0f, 2000f), TarjiLabKnobs())!!
        assertEquals(5.5f, match.rateHz, 0.5f)
        assertTrue(5.5f in match.minHz..match.maxHz)
    }

    @Test
    fun `only selected audio contributes to the match`() {
        val audio = capture(note(2f) + note(8f))
        val slow = matchTarjiPulse(audio, TarjiHoldWindow(0f, 2000f), TarjiLabKnobs())!!
        val fast = matchTarjiPulse(audio, TarjiHoldWindow(2000f, 4000f), TarjiLabKnobs())!!
        assertEquals(2f, slow.rateHz, 0.3f)
        assertEquals(8f, fast.rateHz, 0.6f)
        assertTrue(slow.minHz >= 1.5f)
        assertTrue(fast.minHz > slow.maxHz)
    }

    @Test
    fun `conflicting rhythms need a narrower selection`() {
        val audio = capture(note(2f) + note(8f))
        assertNull(matchTarjiPulse(audio, TarjiHoldWindow(0f, 4000f), TarjiLabKnobs()))
    }

    @Test
    fun `fast pitch-only vibrato is measured without tapping`() {
        val match = matchTarjiPulse(capture(note(9f, volumeDepth = 0f, pitchCents = 30f)),
            TarjiHoldWindow(0f, 2000f), TarjiLabKnobs())!!
        assertEquals(9f, match.rateHz, 0.7f)
        assertTrue(9f in match.minHz..match.maxHz)
    }

    @Test
    fun `silence steady notes and brief selections produce no invented rate`() {
        val knobs = TarjiLabKnobs()
        assertNull(matchTarjiPulse(capture(FloatArray(16000)), TarjiHoldWindow(0f, 2000f), knobs))
        assertNull(matchTarjiPulse(capture(note(0f, volumeDepth = 0f)), TarjiHoldWindow(0f, 2000f), knobs))
        assertNull(matchTarjiPulse(capture(note(9f)), TarjiHoldWindow(100f, 300f), knobs))
    }

    @Test
    fun `empty and outside selections are safely refused`() {
        val audio = capture(note(5f))
        val knobs = TarjiLabKnobs()
        assertNull(matchTarjiPulse(audio, TarjiHoldWindow(3000f, 4000f), knobs))
        assertNull(matchTarjiPulse(audio, TarjiHoldWindow(500f, 500f), knobs))
        assertNull(matchTarjiPulse(audio, TarjiHoldWindow(Float.NaN, 1000f), knobs))
        assertNull(matchTarjiPulse(capture(floatArrayOf()), TarjiHoldWindow(0f, 2000f), knobs))
    }
}
