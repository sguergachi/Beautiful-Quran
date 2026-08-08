package com.beautifulquran.playback

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec for [Tarji], the tarjīʿ (ترجيع) detector: the repeated reverberation
 * of the voice on a single held note. All waves are synthesized 8 kHz mono,
 * fed in 20 ms hops exactly as [VoiceEnergy] decimates them.
 */
class TarjiTest {

    private fun feed(detector: Tarji, samples: FloatArray) {
        var i = 0
        while (i < samples.size) {
            val n = minOf(Tarji.HOP_SAMPLES, samples.size - i)
            detector.onSamples8k(samples.copyOfRange(i, i + n), n)
            i += n
        }
    }

    /** [seconds] of a held note at [pitchHz], with optional AM at [amHz]. */
    private fun heldNote(
        seconds: Float,
        pitchHz: Float,
        amHz: Float = 0f,
        amDepth: Float = 0f,
        level: Float = 0.3f,
    ): FloatArray {
        val n = (seconds * Tarji.SAMPLE_RATE).toInt()
        return FloatArray(n) {
            val t = it / Tarji.SAMPLE_RATE.toFloat()
            val am = if (amHz > 0f) 1f + amDepth * sin(2f * PI.toFloat() * amHz * t) else 1f
            (level * am * sin(2f * PI.toFloat() * pitchHz * t)).toFloat()
        }
    }

    @Test
    fun `tarji on a held note is detected and rides the reverberation`() {
        val d = Tarji()
        assertFalse(d.reverberating)
        feed(d, heldNote(seconds = 0.3f, pitchHz = 130f, amHz = 5.5f, amDepth = 0.08f))
        assertFalse("too brief to call it a hold yet", d.reverberating)
        feed(d, heldNote(seconds = 1.5f, pitchHz = 130f, amHz = 5.5f, amDepth = 0.08f))
        assertTrue("reverberating hold must be detected", d.reverberating)
        assertTrue("attack envelope ramps in", d.tremoloGain > 0.5f)
        assertTrue("hold clock is running", d.holdMs >= 1_000f)
    }

    @Test
    fun `tremolo signal is phase-locked to the voice envelope`() {
        val d = Tarji()
        // Feed hop by hop and collect the tremolo output against the AM phase.
        val amHz = 5.5f
        val wave = heldNote(seconds = 2.5f, pitchHz = 130f, amHz = amHz, amDepth = 0.1f)
        val hop = Tarji.HOP_SAMPLES
        var locked = 0
        var agreeing = 0
        var i = 0
        while (i + hop <= wave.size) {
            d.onSamples8k(wave.copyOfRange(i, i + hop), hop)
            if (d.reverberating) {
                // Envelope phase at the centre of this hop.
                val t = (i + hop / 2) / Tarji.SAMPLE_RATE.toFloat()
                val am = sin(2f * PI.toFloat() * amHz * t)
                if (kotlin.math.abs(am) > 0.5f) {
                    locked++
                    if (d.tremolo * am > 0f) agreeing++
                }
            }
            i += hop
        }
        assertTrue("detector never locked", locked > 20)
        assertTrue(
            "tremolo must rise when the voice swells ($agreeing/$locked)",
            agreeing.toFloat() / locked > 0.8f,
        )
    }

    @Test
    fun `steady hold without reverberation stays still`() {
        val d = Tarji()
        feed(d, heldNote(seconds = 2.5f, pitchHz = 130f))
        assertFalse(d.reverberating)
        assertEquals(0f, d.tremoloGain, 0.01f)
    }

    @Test
    fun `silence and gliding notes are not tarji`() {
        val d = Tarji()
        feed(d, FloatArray(Tarji.SAMPLE_RATE)) // 1 s of silence
        assertFalse(d.reverberating)

        // A note gliding upward: never a single held note.
        val n = Tarji.SAMPLE_RATE * 2
        val glide = FloatArray(n) {
            val t = it / Tarji.SAMPLE_RATE.toFloat()
            val f = 130f + 70f * (t / 2f)
            (0.3f * sin(2f * PI.toFloat() * f * t)).toFloat()
        }
        feed(d, glide)
        assertFalse("a gliding note is not a hold", d.reverberating)
    }

    @Test
    fun `flutter faster than the tarji band is ignored`() {
        val d = Tarji()
        feed(d, heldNote(seconds = 2.5f, pitchHz = 130f, amHz = 14f, amDepth = 0.12f))
        assertFalse("14 Hz flutter is not tarji", d.reverberating)
    }

    @Test
    fun `the rate ceiling rejects pulses faster than it`() {
        // 5.5 Hz pulse with a 4 Hz ceiling: out — including via the
        // half-rate autocorrelation harmonic (5.5 Hz correlates at lag 2τ).
        val capped = Tarji()
        capped.maxTremoloHz = 4f
        feed(capped, heldNote(seconds = 2.5f, pitchHz = 130f, amHz = 5.5f, amDepth = 0.08f))
        assertFalse("5.5 Hz is above the 4 Hz ceiling", capped.reverberating)

        // Default ceiling (10 Hz) hears the same pulse.
        val open = Tarji()
        feed(open, heldNote(seconds = 2.5f, pitchHz = 130f, amHz = 5.5f, amDepth = 0.08f))
        assertTrue(open.reverberating)
    }

    @Test
    fun `slow swells below a low ceiling still count`() {
        val d = Tarji()
        d.maxTremoloHz = 3f
        feed(d, heldNote(seconds = 3f, pitchHz = 130f, amHz = 2f, amDepth = 0.08f))
        assertTrue("a 2 Hz swell is under the 3 Hz ceiling", d.reverberating)
    }

    @Test
    fun `detection releases smoothly when the reverberation stops`() {
        val d = Tarji()
        feed(d, heldNote(seconds = 2f, pitchHz = 130f, amHz = 5.5f, amDepth = 0.08f))
        assertTrue(d.reverberating)
        // The note holds steady — reverberation gone but the note continues.
        feed(d, heldNote(seconds = 1.5f, pitchHz = 130f))
        assertFalse(d.reverberating)
        assertTrue("release ramp keeps some gain briefly", d.tremoloGain < 1f)
    }
}
