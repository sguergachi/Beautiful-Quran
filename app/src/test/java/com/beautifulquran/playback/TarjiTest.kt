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
    fun `raising the ceiling to 50 Hz admits fast vocal texture`() {
        // ~17 Hz envelope texture with strong depth: rejected at the shipped
        // ceiling but admitted when the Ink Lab slider is opened to 50 Hz.
        val capped = Tarji()
        feed(capped, heldNote(seconds = 2.5f, pitchHz = 130f, amHz = 17f, amDepth = 0.3f))
        assertFalse(capped.reverberating)
        val open = Tarji()
        open.maxTremoloHz = 50f
        feed(open, heldNote(seconds = 2.5f, pitchHz = 130f, amHz = 17f, amDepth = 0.3f))
        assertTrue(open.reverberating)
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

    @Test
    fun `slow tarji wins over faster envelope texture`() {
        // Alafasy/Hani 1:7 closers carry both a slow tarjīʿ swell and ~25 Hz
        // texture. The detector must lock the slow pulse, not let the texture
        // veto it (the failure mode that left the Ink Lab on "no tarjīʿ yet").
        val d = Tarji()
        val n = (2.5f * Tarji.SAMPLE_RATE).toInt()
        val wave = FloatArray(n) {
            val t = it / Tarji.SAMPLE_RATE.toFloat()
            val slow = 1f + 0.10f * sin(2f * PI.toFloat() * 2.2f * t)
            val texture = 1f + 0.06f * sin(2f * PI.toFloat() * 25f * t)
            (0.3f * slow * texture * sin(2f * PI.toFloat() * 140f * t)).toFloat()
        }
        feed(d, wave)
        assertTrue("slow tarjīʿ must lock despite faster texture", d.reverberating)
        assertTrue(
            "rate should be the slow swell, not the 25 Hz texture (${d.lastRateHz})",
            d.lastRateHz in 1.5f..6f,
        )
    }

    @Test
    fun `ear delay scales wall-time latency by speed in content hops`() {
        // Route preset only, 1×: 180 ms → 9 hops of 20 ms content.
        assertEquals(9, Tarji.earDelayHops(routeMs = 180, sinkMs = 0, downstreamMs = 0, speed = 1f))
        // At 0.75× the same wall latency spans fewer content hops.
        assertEquals(6, Tarji.earDelayHops(routeMs = 180, sinkMs = 0, downstreamMs = 0, speed = 0.75f))
        // The sink buffer + output path add on top (emulator-shaped: 252 + 80).
        assertEquals(16, Tarji.earDelayHops(routeMs = 0, sinkMs = 252, downstreamMs = 80, speed = 1f))
        assertEquals(12, Tarji.earDelayHops(routeMs = 0, sinkMs = 252, downstreamMs = 80, speed = 0.75f))
        // The Sonic buffer is content-time: one hop, at any speed it exists.
        assertEquals(7, Tarji.earDelayHops(routeMs = 180, sinkMs = 0, downstreamMs = 0, speed = 0.75f, sonicContentMs = 20f))
        assertEquals(10, Tarji.earDelayHops(routeMs = 180, sinkMs = 0, downstreamMs = 0, speed = 1f, sonicContentMs = 20f))
    }

    @Test
    fun `ear delay clamps negative and oversized inputs`() {
        assertEquals(0, Tarji.earDelayHops(routeMs = -50, sinkMs = 0, downstreamMs = 0, speed = 1f))
        // 2× speed over a 1.5 s path: 150 hops → clamped to the 64-hop history.
        assertEquals(63, Tarji.earDelayHops(routeMs = 1_500, sinkMs = 0, downstreamMs = 0, speed = 2f))
    }

    @Test
    fun `hop length at a real stream rate keeps the rate read in content time`() {
        // 44.1 kHz decimates to 8820 Hz → floor gives 176 samples per 20 ms
        // content hop (the old fixed 160 read 5 Hz as 5.63 Hz).
        val d = Tarji()
        d.hopSamples = 176
        val n = (3f * 8_820f).toInt()
        val wave = FloatArray(n) {
            val t = it / 8_820f
            val am = 1f + 0.1f * sin(2f * PI.toFloat() * 5f * t)
            (0.3f * am * sin(2f * PI.toFloat() * 130f * t)).toFloat()
        }
        var i = 0
        while (i + 176 <= wave.size) {
            d.onSamples8k(wave.copyOfRange(i, i + 176), 176)
            i += 176
        }
        assertTrue(d.reverberating)
        assertTrue(
            "rate must read ~5 Hz, not ~5.6 (${d.lastRateHz})",
            d.lastRateHz in 4.5f..5.5f,
        )
    }

    @Test
    fun `delayed signal is phase-locked to the ear's envelope, not the tap's`() {
        val d = Tarji()
        d.delayHops = 10 // 200 ms of content: what the ear hears right now.
        val amHz = 5.5f
        val wave = heldNote(seconds = 3.5f, pitchHz = 130f, amHz = amHz, amDepth = 0.1f)
        val hop = Tarji.HOP_SAMPLES
        var locked = 0
        var agreeing = 0
        var i = 0
        while (i + hop <= wave.size) {
            d.onSamples8k(wave.copyOfRange(i, i + hop), hop)
            if (d.reverberating) {
                // The ear hears this hop 10 hops (200 ms) later than the tap.
                val t = (i + hop / 2 - 10 * hop) / Tarji.SAMPLE_RATE.toFloat()
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
            "delayed tremolo must ride the ear's swell ($agreeing/$locked)",
            agreeing.toFloat() / locked > 0.8f,
        )
    }

    @Test
    fun `a deep slow vibrato never trips its own climax gate`() {
        // A strong low-rate swell (Hani-style ghunnah) must not extinguish
        // itself: the level EMA rides above the troughs.
        val d = Tarji()
        feed(d, heldNote(seconds = 3f, pitchHz = 130f, amHz = 2f, amDepth = 0.3f, level = 0.25f))
        assertTrue("deep slow vibrato must hold", d.reverberating)
        assertEquals("hold clock is running", 3_000f, d.holdMs, 100f)
    }

    @Test
    fun `the climactic hold stops when the voice releases`() {
        val d = Tarji()
        // Vibrato hold, then the voice releases: level decaying exponentially.
        feed(d, heldNote(seconds = 2f, pitchHz = 130f, amHz = 5.5f, amDepth = 0.12f))
        assertTrue("hold must lock before the release", d.reverberating)
        val releaseStart = d.holdMs
        val release = FloatArray(Tarji.SAMPLE_RATE) {
            val t = it / Tarji.SAMPLE_RATE.toFloat()
            val decay = kotlin.math.exp(-t / 0.12f)
            val am = 1f + 0.12f * sin(2f * PI.toFloat() * 5.5f * t)
            (0.3f * decay * am * sin(2f * PI.toFloat() * 130f * t)).toFloat()
        }
        feed(d, release)
        assertFalse("reverberation must end with the voice", d.reverberating)
        assertTrue("the climax release must dry the gain fast", d.tremoloGain < 0.2f)
        assertTrue("the dry signal must not pulse", kotlin.math.abs(d.tremolo) < 0.3f)
    }

    @Test
    fun `Alafasy one seven - the waqf hold rides the build and stops at the release`() {
        // The real 1:7 closer: a long vibrato hold on ٱلضَّالِّينَ followed by
        // the voice releasing. The shimmer must ride the hold and stop when
        // the climax ends — never re-lock on the decaying tail.
        val wav = javaClass.classLoader.getResourceAsStream("tarji/alfasy_1_7_8k.wav")
            ?: throw AssertionError("missing test audio resource")
        val pcm = wav.use { it.readBytes() }
        val samples = FloatArray((pcm.size - 44) / 2)
        for (j in samples.indices) {
            val i = 44 + j * 2
            samples[j] = (pcm[i].toInt() and 0xFF or (pcm[i + 1].toInt() shl 8)).toShort() / 32768f
        }
        val d = Tarji()
        var t = 0f
        var onSpanStart = -1f
        var firstOn = -1f
        var spansInHold = 0
        var lastOnEnd = -1f
        var longestSpan = 0f
        var consumed = 0
        while (consumed + Tarji.HOP_SAMPLES <= samples.size) {
            d.onSamples8k(samples.copyOfRange(consumed, consumed + Tarji.HOP_SAMPLES))
            consumed += Tarji.HOP_SAMPLES
            t += Tarji.HOP_MS / 1000f
            if (d.reverberating) {
                if (onSpanStart < 0f) onSpanStart = t
            } else if (onSpanStart >= 0f) {
                if (onSpanStart in 7.0f..12.4f && t - onSpanStart >= 0.4f) spansInHold++
                if (onSpanStart >= 7.0f) lastOnEnd = t
                if (firstOn < 0f && onSpanStart >= 6.8f && t - onSpanStart >= 0.2f) {
                    firstOn = onSpanStart
                }
                if (onSpanStart >= 7.0f) longestSpan = maxOf(longestSpan, t - onSpanStart)
                onSpanStart = -1f
            }
        }
        if (onSpanStart >= 0f && onSpanStart in 7.0f..12.4f) {
            spansInHold++
            longestSpan = maxOf(longestSpan, t - onSpanStart)
        }
        assertTrue("the waqf hold must engage (spans: $spansInHold)", spansInHold >= 1)
        assertTrue(
            "the shimmer must start with the build, not late in the hold (first on: ${firstOn}s)",
            firstOn in 6.8f..8.2f,
        )
        assertTrue(
            "the shimmer must ride the whole crescendo, never dropping at the peak " +
                "(longest span: ${longestSpan}s)",
            longestSpan >= 3f,
        )
        assertTrue(
            "no shimmer may ride the decayed tail (last on-end: ${lastOnEnd}s)",
            lastOnEnd <= 12.45f,
        )
    }
}
