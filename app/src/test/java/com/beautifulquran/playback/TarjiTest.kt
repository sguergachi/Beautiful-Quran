package com.beautifulquran.playback

import com.beautifulquran.ui.reader.TarjiWordGate
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
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

    private fun wavResource(name: String): FloatArray {
        val wav = javaClass.getResourceAsStream("/tarji/$name")
            ?.use { it.readBytes() }
            ?: throw AssertionError("missing test audio resource: $name")

        fun littleEndianInt(offset: Int): Int =
            (wav[offset].toInt() and 0xFF) or
                ((wav[offset + 1].toInt() and 0xFF) shl 8) or
                ((wav[offset + 2].toInt() and 0xFF) shl 16) or
                ((wav[offset + 3].toInt() and 0xFF) shl 24)
        fun littleEndianShort(offset: Int): Int =
            (wav[offset].toInt() and 0xFF) or
                ((wav[offset + 1].toInt() and 0xFF) shl 8)
        fun chunkAt(offset: Int, id: String): Boolean =
            id.indices.all { wav[offset + it] == id[it].code.toByte() }

        if (wav.size < 12 || !chunkAt(0, "RIFF") || !chunkAt(8, "WAVE")) {
            throw AssertionError("invalid WAV test audio: $name")
        }
        var offset = 12
        var pcm8kMono16 = false
        while (offset + 8 <= wav.size) {
            val size = littleEndianInt(offset + 4)
            if (chunkAt(offset, "fmt ") && size >= 16 && offset + 8 + size <= wav.size) {
                val format = offset + 8
                pcm8kMono16 = littleEndianShort(format) == 1 &&
                    littleEndianShort(format + 2) == 1 &&
                    littleEndianInt(format + 4) == Tarji.SAMPLE_RATE &&
                    littleEndianShort(format + 14) == 16
            }
            if (chunkAt(offset, "data")) {
                val dataOffset = offset + 8
                if (!pcm8kMono16 || size < 0 || dataOffset + size > wav.size) break
                return FloatArray(size / 2) { j ->
                    val i = dataOffset + j * 2
                    ((wav[i].toInt() and 0xFF) or (wav[i + 1].toInt() shl 8)).toShort() / 32768f
                }
            }
            if (size < 0) break
            offset += 8 + size + (size and 1)
        }
        throw AssertionError("test audio must be mono PCM16 at 8 kHz: $name")
    }

    private fun feed(detector: Tarji, samples: FloatArray) {
        var i = 0
        while (i < samples.size) {
            val n = minOf(Tarji.HOP_SAMPLES, samples.size - i)
            detector.onSamples8k(samples.copyOfRange(i, i + n), n)
            i += n
        }
    }

    private fun eventStartMs(detector: Tarji): Long =
        detector.syncEventStartHop
            .takeIf { it >= 0 }
            ?.toLong()
            ?.times(Tarji.HOP_MS)
            ?: VoiceEnergy.NO_EVENT_MS

    private fun realFlickerCorrelation(
        name: String,
        fromSeconds: Float,
        toSeconds: Float,
        rmsShiftHops: Int = 0,
    ): Float {
        val samples = wavResource(name)
        val detector = Tarji()
        val rms = mutableListOf<Float>()
        val shimmer = mutableListOf<Float>()
        var consumed = 0
        while (consumed + Tarji.HOP_SAMPLES <= samples.size) {
            var sumSq = 0f
            for (i in consumed until consumed + Tarji.HOP_SAMPLES) {
                sumSq += samples[i] * samples[i]
            }
            detector.onSamples8k(samples.copyOfRange(consumed, consumed + Tarji.HOP_SAMPLES))
            consumed += Tarji.HOP_SAMPLES
            val time = consumed / Tarji.SAMPLE_RATE.toFloat()
            if (time in fromSeconds..toSeconds && detector.reverberating) {
                rms += sqrt(sumSq / Tarji.HOP_SAMPLES)
                shimmer += detector.tremolo
            }
        }
        val from = maxOf(0, -rmsShiftHops)
        val until = minOf(shimmer.size, rms.size - rmsShiftHops)
        fun detrend(values: List<Float>, offset: Int): FloatArray {
            val count = until - from
            var mean = 0f
            for (i in 0 until count) mean += values[offset + i]
            mean /= count
            val centre = (count - 1) * 0.5f
            var numerator = 0f
            var denominator = 0f
            for (i in 0 until count) {
                val x = i - centre
                numerator += x * (values[offset + i] - mean)
                denominator += x * x
            }
            val slope = numerator / denominator
            return FloatArray(count) { i -> values[offset + i] - (mean + slope * (i - centre)) }
        }
        val x = detrend(shimmer, from)
        val y = detrend(rms, from + rmsShiftHops)
        var cross = 0f
        var xEnergy = 0f
        var yEnergy = 0f
        for (i in 1 until x.size) {
            val shimmerChange = x[i] - x[i - 1]
            val voiceChange = y[i] - y[i - 1]
            cross += shimmerChange * voiceChange
            xEnergy += shimmerChange * shimmerChange
            yEnergy += voiceChange * voiceChange
        }
        return cross / sqrt(xEnergy * yEnergy)
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

    /** A held note whose level changes without any periodic modulation. */
    private fun rampedHeldNote(
        seconds: Float,
        pitchHz: Float,
        fromLevel: Float,
        toLevel: Float,
    ): FloatArray {
        val n = (seconds * Tarji.SAMPLE_RATE).toInt()
        return FloatArray(n) {
            val t = it / Tarji.SAMPLE_RATE.toFloat()
            val level = fromLevel + (toLevel - fromLevel) * t / seconds
            (level * sin(2f * PI.toFloat() * pitchHz * t)).toFloat()
        }
    }

    /** A held note with independent pitch vibrato and intensity tremolo. */
    private fun modulatedHeldNote(
        seconds: Float,
        pitchHz: Float,
        rateHz: Float,
        pitchDepthCents: Float = 0f,
        amplitudeDepth: Float = 0f,
        amplitudePhaseRadians: Float = 0f,
        endPitchHz: Float = pitchHz,
        startLevel: Float = 0.3f,
        endLevel: Float = startLevel,
    ): FloatArray {
        val samples = FloatArray((seconds * Tarji.SAMPLE_RATE).toInt())
        var carrierPhase = 0f
        for (i in samples.indices) {
            val t = i / Tarji.SAMPLE_RATE.toFloat()
            val progress = i / samples.lastIndex.toFloat()
            val phase = 2f * PI.toFloat() * rateHz * t
            val pitchModulation = sin(phase)
            val amplitudeModulation = sin(phase + amplitudePhaseRadians)
            val basePitch = pitchHz + (endPitchHz - pitchHz) * progress
            val frequency = basePitch * 2f.pow(pitchDepthCents * pitchModulation / 1_200f)
            val level = startLevel + (endLevel - startLevel) * progress
            carrierPhase += 2f * PI.toFloat() * frequency / Tarji.SAMPLE_RATE
            samples[i] = level * (1f + amplitudeDepth * amplitudeModulation) * sin(carrierPhase)
        }
        return samples
    }

    private fun shimmerCorrelation(wave: FloatArray, rateHz: Float): Float {
        val detector = Tarji()
        var cross = 0f
        var voiceEnergy = 0f
        var shimmerEnergy = 0f
        var consumed = 0
        while (consumed + Tarji.HOP_SAMPLES <= wave.size) {
            detector.onSamples8k(wave.copyOfRange(consumed, consumed + Tarji.HOP_SAMPLES))
            if (detector.reverberating) {
                val t = (consumed + Tarji.HOP_SAMPLES / 2) / Tarji.SAMPLE_RATE.toFloat()
                val voice = sin(2f * PI.toFloat() * rateHz * t)
                cross += voice * detector.tremolo
                voiceEnergy += voice * voice
                shimmerEnergy += detector.tremolo * detector.tremolo
            }
            consumed += Tarji.HOP_SAMPLES
        }
        return cross / sqrt(voiceEnergy * shimmerEnergy)
    }

    @Test
    fun `hop clock includes the analysis frame warm-up`() {
        val d = Tarji()
        repeat(4) {
            feed(d, heldNote(seconds = 0.02f, pitchHz = 130f))
            assertEquals(it + 1, d.hopCount)
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
    fun `pitch-only vibrato is tarji even when intensity is steady`() {
        for (rateHz in listOf(2f, 5.5f, 9f)) {
            val detector = Tarji()
            feed(
                detector,
                modulatedHeldNote(
                    seconds = 4f,
                    pitchHz = 150f,
                    rateHz = rateHz,
                    pitchDepthCents = 30f,
                ),
            )
            assertTrue("$rateHz Hz pitch vibrato must be detected", detector.reverberating)
            assertTrue(
                "$rateHz Hz pitch vibrato rate must stay in band (${detector.lastRateHz})",
                detector.lastRateHz in Tarji.MIN_TREMOLO_HZ..Tarji.MAX_TREMOLO_HZ,
            )
        }
    }

    @Test
    fun `subthreshold pitch movement does not create a shimmer`() {
        val detector = Tarji()
        feed(
            detector,
            modulatedHeldNote(
                seconds = 4f,
                pitchHz = 150f,
                rateHz = 5.5f,
                pitchDepthCents = 4f,
            ),
        )
        assertFalse("imperceptible F0 estimator motion must stay still", detector.reverberating)
    }

    @Test
    fun `pitch vibrato crosses the perceptible depth boundary cleanly`() {
        val below = Tarji()
        feed(
            below,
            modulatedHeldNote(
                seconds = 4f,
                pitchHz = 150f,
                rateHz = 5.5f,
                pitchDepthCents = 8f,
            ),
        )
        assertFalse("subthreshold pitch motion must stay still", below.reverberating)

        val above = Tarji()
        feed(
            above,
            modulatedHeldNote(
                seconds = 4f,
                pitchHz = 150f,
                rateHz = 5.5f,
                pitchDepthCents = 12f,
            ),
        )
        assertTrue("audible pitch vibrato must cross the gate", above.reverberating)
    }

    @Test
    fun `pitch vibrato carries mixed modulation when amplitude is subthreshold`() {
        val detector = Tarji()
        feed(
            detector,
            modulatedHeldNote(
                seconds = 4f,
                pitchHz = 150f,
                rateHz = 5.5f,
                pitchDepthCents = 30f,
                amplitudeDepth = 0.01f,
            ),
        )
        assertTrue("pitch evidence must admit shallow mixed vibrato", detector.reverberating)
    }

    @Test
    fun `mixed event keeps its acquired phase channel across AM hysteresis`() {
        val detector = Tarji()
        val rateHz = 5.5f
        val wave = modulatedHeldNote(
            seconds = 4f,
            pitchHz = 150f,
            rateHz = rateHz,
            pitchDepthCents = 30f,
            amplitudeDepth = 0.03f,
            amplitudePhaseRadians = PI.toFloat(),
        )
        var cross = 0f
        var voiceEnergy = 0f
        var shimmerEnergy = 0f
        var consumed = 0
        while (consumed + Tarji.HOP_SAMPLES <= wave.size) {
            detector.onSamples8k(wave.copyOfRange(consumed, consumed + Tarji.HOP_SAMPLES))
            if (detector.reverberating) {
                val t = (consumed + Tarji.HOP_SAMPLES / 2) / Tarji.SAMPLE_RATE.toFloat()
                val pitchMotion = sin(2f * PI.toFloat() * rateHz * t)
                cross += pitchMotion * detector.tremolo
                voiceEnergy += pitchMotion * pitchMotion
                shimmerEnergy += detector.tremolo * detector.tremolo
            }
            consumed += Tarji.HOP_SAMPLES
        }
        val correlation = cross / sqrt(voiceEnergy * shimmerEnergy)
        assertTrue(
            "subthreshold AM must not flip an FM event (correlation=$correlation)",
            correlation > 0.85f,
        )
    }

    @Test
    fun `pitch-only shimmer follows the fundamental frequency motion`() {
        for (rateHz in listOf(2f, 5.5f, 9f)) {
            val detector = Tarji()
            val wave = modulatedHeldNote(
                seconds = 4f,
                pitchHz = 150f,
                rateHz = rateHz,
                pitchDepthCents = 35f,
            )
            var cross = 0f
            var voiceEnergy = 0f
            var shimmerEnergy = 0f
            var consumed = 0
            while (consumed + Tarji.HOP_SAMPLES <= wave.size) {
                detector.onSamples8k(wave.copyOfRange(consumed, consumed + Tarji.HOP_SAMPLES))
                if (detector.reverberating) {
                    val t = (consumed + Tarji.HOP_SAMPLES / 2) / Tarji.SAMPLE_RATE.toFloat()
                    val pitchMotion = sin(2f * PI.toFloat() * rateHz * t)
                    cross += pitchMotion * detector.tremolo
                    voiceEnergy += pitchMotion * pitchMotion
                    shimmerEnergy += detector.tremolo * detector.tremolo
                }
                consumed += Tarji.HOP_SAMPLES
            }
            val correlation = cross / sqrt(voiceEnergy * shimmerEnergy)
            assertTrue(
                "$rateHz Hz pitch shimmer must follow F0 (correlation=$correlation)",
                correlation > 0.85f,
            )
        }
    }

    @Test
    fun `AM shimmer stays phase locked through a crescendo`() {
        val rateHz = 5.5f
        val correlation = shimmerCorrelation(
            modulatedHeldNote(
                seconds = 4f,
                pitchHz = 150f,
                rateHz = rateHz,
                amplitudeDepth = 0.1f,
                startLevel = 0.15f,
                endLevel = 0.3f,
            ),
            rateHz,
        )
        assertTrue("crescendo AM phase must follow the voice (correlation=$correlation)", correlation > 0.85f)
    }

    @Test
    fun `FM shimmer stays phase locked through an allowed pitch glide`() {
        val rateHz = 5.5f
        val correlation = shimmerCorrelation(
            modulatedHeldNote(
                seconds = 4f,
                pitchHz = 150f,
                endPitchHz = 162f,
                rateHz = rateHz,
                pitchDepthCents = 30f,
            ),
            rateHz,
        )
        assertTrue("gliding FM phase must follow F0 (correlation=$correlation)", correlation > 0.85f)
    }

    @Test
    fun `pitch-only shimmer stays aligned at high carriers and the band ceiling`() {
        for (pitchHz in listOf(250f, 340f)) {
            val rateHz = 10f
            val correlation = shimmerCorrelation(
                modulatedHeldNote(
                    seconds = 4f,
                    pitchHz = pitchHz,
                    rateHz = rateHz,
                    pitchDepthCents = 35f,
                ),
                rateHz,
            )
            assertTrue(
                "$pitchHz Hz carrier at 10 Hz must stay phase locked (correlation=$correlation)",
                correlation > 0.9f,
            )
        }
    }

    @Test
    fun `mixed vibrato follows its audible intensity phase`() {
        val detector = Tarji()
        val rateHz = 6f
        val wave = modulatedHeldNote(
            seconds = 4f,
            pitchHz = 150f,
            rateHz = rateHz,
            pitchDepthCents = 35f,
            amplitudeDepth = 0.1f,
        )
        var cross = 0f
        var voiceEnergy = 0f
        var shimmerEnergy = 0f
        var locked = 0
        var consumed = 0
        while (consumed + Tarji.HOP_SAMPLES <= wave.size) {
            detector.onSamples8k(wave.copyOfRange(consumed, consumed + Tarji.HOP_SAMPLES))
            if (detector.reverberating) {
                val t = (consumed + Tarji.HOP_SAMPLES / 2) / Tarji.SAMPLE_RATE.toFloat()
                val voice = sin(2f * PI.toFloat() * rateHz * t)
                cross += voice * detector.tremolo
                voiceEnergy += voice * voice
                shimmerEnergy += detector.tremolo * detector.tremolo
                locked++
            }
            consumed += Tarji.HOP_SAMPLES
        }
        val correlation = cross / sqrt(voiceEnergy * shimmerEnergy)
        assertTrue("mixed vibrato never locked", locked > 20)
        assertTrue("mixed shimmer must follow intensity (correlation=$correlation)", correlation > 0.85f)
    }

    @Test
    fun `reset makes the detector equivalent to a fresh instance`() {
        val reset = Tarji()
        feed(
            reset,
            modulatedHeldNote(
                seconds = 2f,
                pitchHz = 240f,
                rateHz = 9f,
                pitchDepthCents = 35f,
                amplitudeDepth = 0.1f,
            ),
        )
        reset.reset()
        assertEquals(-1, reset.eventStartHop)
        assertEquals(-1, reset.syncEventStartHop)

        val fresh = Tarji()
        val wave = modulatedHeldNote(
            seconds = 2f,
            pitchHz = 150f,
            rateHz = 5.5f,
            pitchDepthCents = 30f,
            amplitudeDepth = 0.01f,
        )
        var consumed = 0
        while (consumed + Tarji.HOP_SAMPLES <= wave.size) {
            val hop = wave.copyOfRange(consumed, consumed + Tarji.HOP_SAMPLES)
            reset.onSamples8k(hop)
            fresh.onSamples8k(hop)
            assertEquals(fresh.reverberating, reset.reverberating)
            assertEquals(fresh.holdMs, reset.holdMs, 0f)
            assertEquals(fresh.tremolo, reset.tremolo, 1e-6f)
            assertEquals(fresh.tremoloGain, reset.tremoloGain, 1e-6f)
            consumed += Tarji.HOP_SAMPLES
        }
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
    fun `tremolo stays phase-locked across the admitted band`() {
        val cases = listOf(
            1.5f to 0.12f,
            5.5f to 0.12f,
            8f to 0.12f,
            Tarji.MAX_TREMOLO_HZ to 0.2f,
        )
        for ((amHz, amDepth) in cases) {
            val d = Tarji()
            val wave = heldNote(
                seconds = 5f,
                pitchHz = 130f,
                amHz = amHz,
                amDepth = amDepth,
            )
            var cross = 0f
            var voiceEnergy = 0f
            var tremoloEnergy = 0f
            var locked = 0
            var i = 0
            while (i + Tarji.HOP_SAMPLES <= wave.size) {
                d.onSamples8k(wave.copyOfRange(i, i + Tarji.HOP_SAMPLES))
                if (d.reverberating) {
                    val t = (i + Tarji.HOP_SAMPLES / 2) / Tarji.SAMPLE_RATE.toFloat()
                    val voice = sin(2f * PI.toFloat() * amHz * t)
                    cross += voice * d.tremolo
                    voiceEnergy += voice * voice
                    tremoloEnergy += d.tremolo * d.tremolo
                    locked++
                }
                i += Tarji.HOP_SAMPLES
            }

            assertTrue("$amHz Hz detector never locked", locked > 20)
            val correlation = cross / sqrt(voiceEnergy * tremoloEnergy)
            assertTrue(
                "$amHz Hz shimmer must follow the voice (correlation=$correlation)",
                correlation > 0.85f,
            )
        }
    }

    @Test
    fun `Alafasy and Hani one seven shimmer peaks land on the same acoustic hop`() {
        val cases = listOf(
            Triple("alfasy_1_7_8k.wav", 7.5f, 10.1f),
            Triple("hani_1_7_8k.wav", 8.4f, 10.1f),
        )
        for ((name, start, end) in cases) {
            val aligned = realFlickerCorrelation(name, start, end)
            val oneHopEarly = realFlickerCorrelation(name, start, end, -1)
            val oneHopLate = realFlickerCorrelation(name, start, end, 1)
            assertTrue("$name flicker must follow its live 20 ms RMS ($aligned)", aligned > 0.85f)
            assertTrue(
                "$name zero-lag peak must beat either adjacent hop ($oneHopEarly, $aligned, $oneHopLate)",
                aligned > maxOf(oneHopEarly, oneHopLate) + 0.15f,
            )
        }
    }

    @Test
    fun `steady hold without reverberation stays still`() {
        val d = Tarji()
        feed(d, heldNote(seconds = 2.5f, pitchHz = 130f))
        assertFalse(d.reverberating)
        assertEquals(0f, d.tremoloGain, 0.01f)
    }

    @Test
    fun `a plain crescendo is not periodic reverberation`() {
        val d = Tarji()
        feed(
            d,
            rampedHeldNote(
                seconds = 4f,
                pitchHz = 130f,
                fromLevel = 0.15f,
                toLevel = 0.35f,
            ),
        )
        assertFalse("an envelope trend must not masquerade as a 10 Hz pulse", d.reverberating)
    }

    @Test
    fun `a configured floor below the measurable band cannot admit a sub-band swell`() {
        // Even a stale Ink Lab value below the physical floor is clamped.
        val d = Tarji().apply { minTremoloHz = 0.5f }
        feed(d, heldNote(seconds = 4f, pitchHz = 130f, amHz = 0.8f, amDepth = 0.2f))
        assertFalse("0.8 Hz must not be reported as an in-band pulse", d.reverberating)
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
    fun `a configured ceiling above the product band cannot admit fast texture`() {
        // The 80 ms evidence window has its first null at 12.5 Hz. A stale
        // 25 Hz Lab value must not turn 17 Hz texture into held-note tarjīʿ.
        val d = Tarji().apply { maxTremoloHz = 25f }
        feed(d, heldNote(seconds = 2.5f, pitchHz = 130f, amHz = 17f, amDepth = 0.3f))
        assertFalse(d.reverberating)
    }

    @Test
    fun `a quieter pulse after a loud steady onset acquires the real event`() {
        val d = Tarji()
        val wordGate = TarjiWordGate()
        val loudOnset = heldNote(seconds = 1.5f, pitchHz = 130f, level = 0.35f)
        var consumed = 0
        while (consumed + Tarji.HOP_SAMPLES <= loudOnset.size) {
            d.onSamples8k(loudOnset.copyOfRange(consumed, consumed + Tarji.HOP_SAMPLES))
            wordGate.allows(
                gain = d.tremoloGain,
                detected = d.reverberating,
                eventStartMs = eventStartMs(d),
                wordStartMs = 0L,
            )
            consumed += Tarji.HOP_SAMPLES
        }
        assertFalse(d.reverberating)

        val quietPulse = heldNote(
            seconds = 2.5f,
            pitchHz = 130f,
            amHz = 5.5f,
            amDepth = 0.12f,
            level = 0.06f,
        )
        consumed = 0
        var lateVisibleHops = 0
        while (consumed + Tarji.HOP_SAMPLES <= quietPulse.size) {
            d.onSamples8k(quietPulse.copyOfRange(consumed, consumed + Tarji.HOP_SAMPLES))
            consumed += Tarji.HOP_SAMPLES
            val visible = wordGate.allows(
                gain = d.tremoloGain,
                detected = d.reverberating,
                eventStartMs = eventStartMs(d),
                wordStartMs = 0L,
            )
            if (consumed >= 1.7f * Tarji.SAMPLE_RATE && visible) {
                lateVisibleHops++
            }
        }

        assertTrue("the level step must not consume the later coherent pulse", d.reverberating)
        assertTrue(d.tremoloGain > 0.5f)
        assertTrue(d.lastRateHz in 5f..6f)
        assertTrue("the word gate must still show the real event", lateVisibleHops >= 30)
    }

    @Test
    fun `pitch vibrato acquires after an AM-only loud onset`() {
        val rateHz = 5.5f
        val seconds = 1.3f
        val samples = FloatArray((seconds * Tarji.SAMPLE_RATE).toInt())
        var carrierPhase = 0f
        for (i in samples.indices) {
            val t = i / Tarji.SAMPLE_RATE.toFloat()
            val pitchMotion = sin(2f * PI.toFloat() * rateHz * t)
            val frequency = 150f * 2f.pow(30f * pitchMotion / 1_200f)
            val level = if (t < 0.2f) 0.3f else 0.06f
            carrierPhase += 2f * PI.toFloat() * frequency / Tarji.SAMPLE_RATE
            samples[i] = level * sin(carrierPhase)
        }

        val detector = Tarji()
        var cross = 0f
        var voiceEnergy = 0f
        var shimmerEnergy = 0f
        var consumed = 0
        while (consumed + Tarji.HOP_SAMPLES <= samples.size) {
            detector.onSamples8k(samples.copyOfRange(consumed, consumed + Tarji.HOP_SAMPLES))
            if (detector.reverberating) {
                val t = (consumed + Tarji.HOP_SAMPLES / 2) / Tarji.SAMPLE_RATE.toFloat()
                val pitchMotion = sin(2f * PI.toFloat() * rateHz * t)
                cross += pitchMotion * detector.tremolo
                voiceEnergy += pitchMotion * pitchMotion
                shimmerEnergy += detector.tremolo * detector.tremolo
            }
            consumed += Tarji.HOP_SAMPLES
        }

        assertTrue("AM level balance must not veto coherent FM", detector.reverberating)
        assertTrue(detector.lastRateHz in 5f..6f)
        val correlation = cross / sqrt(voiceEnergy * shimmerEnergy)
        assertTrue("the loud AM onset must not capture FM phase ($correlation)", correlation > 0.85f)
    }

    @Test
    fun `a quieter coherent continuation remains the same event`() {
        val d = Tarji()
        feed(d, heldNote(seconds = 2.5f, pitchHz = 130f, amHz = 5f, amDepth = 0.12f))
        assertTrue(d.reverberating)
        val continuation = heldNote(
            seconds = 2f,
            pitchHz = 130f,
            amHz = 5f,
            amDepth = 0.12f,
            level = 0.12f,
        )
        var minimumGain = d.tremoloGain
        var consumed = 0
        while (consumed + Tarji.HOP_SAMPLES <= continuation.size) {
            d.onSamples8k(
                continuation.copyOfRange(consumed, consumed + Tarji.HOP_SAMPLES),
            )
            consumed += Tarji.HOP_SAMPLES
            minimumGain = minOf(minimumGain, d.tremoloGain)
        }
        assertTrue("a level change alone must not end a coherent pulse", d.reverberating)
        assertTrue("the quieter pulse must never close the visual event", minimumGain > 0.01f)
    }

    @Test
    fun `a new event may begin later on the same pitch`() {
        val d = Tarji()
        feed(d, heldNote(seconds = 2f, pitchHz = 130f, amHz = 5f, amDepth = 0.12f))
        assertTrue(d.reverberating)
        feed(d, heldNote(seconds = 2f, pitchHz = 130f))
        assertFalse(d.reverberating)
        feed(d, heldNote(seconds = 2f, pitchHz = 130f, amHz = 5f, amDepth = 0.12f))
        assertTrue("event identity must not be permanently tied to pitch", d.reverberating)
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
        assertEquals(9f, Tarji.earDelayHops(routeMs = 180, sinkMs = 0, downstreamMs = 0, speed = 1f), 0f)
        // At 0.75× the same wall latency spans fewer content hops.
        assertEquals(6.75f, Tarji.earDelayHops(routeMs = 180, sinkMs = 0, downstreamMs = 0, speed = 0.75f), 0f)
        // The sink buffer + output path add on top (emulator-shaped: 252 + 80).
        assertEquals(16.6f, Tarji.earDelayHops(routeMs = 0, sinkMs = 252, downstreamMs = 80, speed = 1f), 0.0001f)
        assertEquals(12.45f, Tarji.earDelayHops(routeMs = 0, sinkMs = 252, downstreamMs = 80, speed = 0.75f), 0.0001f)
        // The Sonic buffer is content-time: one hop, at any speed it exists.
        assertEquals(7.75f, Tarji.earDelayHops(routeMs = 180, sinkMs = 0, downstreamMs = 0, speed = 0.75f, sonicContentMs = 20f), 0f)
        assertEquals(10f, Tarji.earDelayHops(routeMs = 180, sinkMs = 0, downstreamMs = 0, speed = 1f, sonicContentMs = 20f), 0f)
    }

    @Test
    fun `measured content backlog is not scaled by playback speed twice`() {
        val fromWallClock = Tarji.earDelayHops(routeMs = 0, sinkMs = 100, speed = 2f)
        val fromContentClock = Tarji.earDelayHops(
            routeMs = 0,
            sinkMs = 0,
            speed = 2f,
            measuredSinkContentMs = 200.0,
        )

        assertEquals(10f, fromWallClock, 0f)
        assertEquals(fromWallClock, fromContentClock, 0f)
    }

    @Test
    fun `fractional ear delay interpolates between adjacent hops`() {
        val d = Tarji()
        val wave = heldNote(seconds = 3f, pitchHz = 130f, amHz = 5.5f, amDepth = 0.12f)
        var previousTremolo = 0f
        var currentTremolo = 0f
        var previousGain = 0f
        var currentGain = 0f
        var i = 0
        while (i + Tarji.HOP_SAMPLES <= wave.size) {
            previousTremolo = currentTremolo
            previousGain = currentGain
            d.onSamples8k(wave.copyOfRange(i, i + Tarji.HOP_SAMPLES))
            currentTremolo = d.tremolo
            currentGain = d.tremoloGain
            i += Tarji.HOP_SAMPLES
        }
        assertTrue(d.reverberating)
        assertTrue("the interpolation must cross the history-ring wrap", d.hopCount > 64)

        d.delayHops = 1f
        assertEquals(previousTremolo, d.syncTremolo, 0.0001f)
        assertEquals(previousGain, d.syncTremoloGain, 0.0001f)
        d.delayHops = 0.5f
        assertEquals((previousTremolo + currentTremolo) * 0.5f, d.syncTremolo, 0.0001f)
        assertEquals((previousGain + currentGain) * 0.5f, d.syncTremoloGain, 0.0001f)
    }

    @Test
    fun `fractional delay switches reverberation at the interpolated edge`() {
        val d = Tarji()
        val wave = heldNote(seconds = 3f, pitchHz = 130f, amHz = 5.5f, amDepth = 0.12f)
        var wasReverberating = false
        var i = 0
        while (i + Tarji.HOP_SAMPLES <= wave.size) {
            d.onSamples8k(wave.copyOfRange(i, i + Tarji.HOP_SAMPLES))
            if (d.reverberating && !wasReverberating) break
            wasReverberating = d.reverberating
            i += Tarji.HOP_SAMPLES
        }
        assertTrue(d.reverberating)

        d.delayHops = 0.49f
        assertTrue(d.syncReverberating)
        d.delayHops = 0.51f
        assertFalse(d.syncReverberating)
    }

    @Test
    fun `delayed event identity stays with the delayed pulse`() {
        val d = Tarji().apply { delayHops = 5f }
        val wave = heldNote(seconds = 3f, pitchHz = 130f, amHz = 5.5f, amDepth = 0.12f)
        var rawStartHop = -1
        var syncStartHop = -1
        var rawStartMs = -1L
        var syncStartMs = -1L
        var consumed = 0
        while (consumed + Tarji.HOP_SAMPLES <= wave.size) {
            d.onSamples8k(wave.copyOfRange(consumed, consumed + Tarji.HOP_SAMPLES))
            consumed += Tarji.HOP_SAMPLES
            val timeMs = consumed.toLong() * 1_000L / Tarji.SAMPLE_RATE
            if (d.reverberating && rawStartHop < 0) {
                rawStartHop = d.hopCount
                rawStartMs = timeMs
            }
            if (d.syncReverberating && syncStartHop < 0) {
                syncStartHop = d.syncEventStartHop
                syncStartMs = timeMs
            }
        }
        assertTrue("raw event must be detected", rawStartHop >= 0)
        assertTrue("delayed event must be detected", syncStartHop >= 0)
        assertEquals("delayed identity must name the same event", rawStartHop, syncStartHop)
        assertTrue("delayed pulse must arrive after its raw onset", syncStartMs > rawStartMs)

        feed(d, FloatArray(Tarji.SAMPLE_RATE))
        assertFalse(d.syncReverberating)
        assertEquals(-1, d.syncEventStartHop)
    }

    @Test
    fun `ear delay clamps negative and oversized inputs`() {
        assertEquals(0f, Tarji.earDelayHops(routeMs = -50, sinkMs = 0, downstreamMs = 0, speed = 1f), 0f)
        // 2× speed over a 1.5 s path: 150 hops → clamped to the 64-hop history.
        assertEquals(63f, Tarji.earDelayHops(routeMs = 1_500, sinkMs = 0, downstreamMs = 0, speed = 2f), 0f)
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
    fun `pitch tracking follows the decimated stream rate`() {
        fun pitchAt(sampleRate: Int, hopSamples: Int): Float {
            val d = Tarji().apply { this.hopSamples = hopSamples }
            val wave = FloatArray(sampleRate * 2) {
                val t = it / sampleRate.toFloat()
                (0.3f * sin(2f * PI.toFloat() * 130f * t)).toFloat()
            }
            feed(d, wave)
            return d.lastPitchHz
        }

        val pitch8k = pitchAt(sampleRate = 8_000, hopSamples = 160)
        val pitch8820 = pitchAt(sampleRate = 8_820, hopSamples = 176)
        assertEquals(
            "the same voice must not shift when 44.1 kHz decimates to 8.82 kHz",
            pitch8k,
            pitch8820,
            2f,
        )
    }

    @Test
    fun `delayed signal is phase-locked to the ear's envelope, not the tap's`() {
        val d = Tarji()
        d.delayHops = 10f // 200 ms of content: what the ear hears right now.
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
        // The real 1:7 closer: shimmer on the ḍād sustain, then still ink as
        // the articulation advances through lām and nūn.
        val samples = wavResource("alfasy_1_7_8k.wav")
        val d = Tarji()
        var t = 0f
        var onSpanStart = -1f
        var firstOn = -1f
        var spansInHold = 0
        var lastOnEnd = -1f
        var longestSpan = 0f
        var swellRms = 0f
        var swellCount = 0
        var troughRms = 0f
        var troughCount = 0
        var lateVisibleHops = 0
        var consumed = 0
        while (consumed + Tarji.HOP_SAMPLES <= samples.size) {
            d.onSamples8k(samples.copyOfRange(consumed, consumed + Tarji.HOP_SAMPLES))
            var sumSq = 0f
            for (i in consumed until consumed + Tarji.HOP_SAMPLES) {
                sumSq += samples[i] * samples[i]
            }
            val hopRms = kotlin.math.sqrt(sumSq / Tarji.HOP_SAMPLES)
            consumed += Tarji.HOP_SAMPLES
            t += Tarji.HOP_MS / 1000f
            if (t >= 10.2f && d.tremoloGain > 0.01f) lateVisibleHops++
            if (d.reverberating) {
                if (onSpanStart < 0f) onSpanStart = t
                if (t in 7.5f..12.2f) {
                    when {
                        d.tremolo > 0.45f -> {
                            swellRms += hopRms
                            swellCount++
                        }
                        d.tremolo < -0.45f -> {
                            troughRms += hopRms
                            troughCount++
                        }
                    }
                }
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
        assertEquals("the waqf crescendo must be one continuous acoustic event", 1, spansInHold)
        assertTrue(
            "the shimmer must start with the build, not late in the hold (first on: ${firstOn}s)",
            firstOn in 6.8f..8.2f,
        )
        assertTrue(
            "the shimmer must ride the ḍād crescendo, never dropping at the peak " +
                "(longest span: ${longestSpan}s)",
            longestSpan >= 2f,
        )
        assertTrue(
            "the lām and nūn must stay still after the ḍād event (last on-end: ${lastOnEnd}s)",
            lastOnEnd <= 10.1f,
        )
        assertEquals("the lām and nūn must carry no residual shimmer", 0, lateVisibleHops)
        assertTrue("real clip must expose both acoustic phases", swellCount > 5 && troughCount > 5)
        assertTrue(
            "positive tremolo must be the louder vocal swell, never the quiet trough",
            swellRms / swellCount > troughRms / troughCount,
        )
    }

    @Test
    fun `Alafasy one seven - the shimmer engages before the crest and dries after it`() {
        // The user-visible shape of tarjīʿ is build → peak → dissipate: the
        // shimmer must start ramping while the ḍād swell is still climbing
        // (before its own loudest crest), then ride the crest and dry with
        // the release — never arrive only at/after the peak. Detection uses a
        // short minimum analysis window so the first decisive hop lands while
        // the swell is still rising.
        val samples = wavResource("alfasy_1_7_8k.wav")
        val d = Tarji()
        var consumed = 0
        var firstVisible = -1f
        var rmsAtFirst = 0f
        var swellCrest = -1f
        var crestRms = 0f
        var dryEnd = -1f
        while (consumed + Tarji.HOP_SAMPLES <= samples.size) {
            var sumSq = 0f
            for (i in consumed until consumed + Tarji.HOP_SAMPLES) {
                sumSq += samples[i] * samples[i]
            }
            d.onSamples8k(samples.copyOfRange(consumed, consumed + Tarji.HOP_SAMPLES))
            consumed += Tarji.HOP_SAMPLES
            val t = consumed / Tarji.SAMPLE_RATE.toFloat()
            if (t !in 7.0f..12.4f) continue
            val hopRms = sqrt(sumSq / Tarji.HOP_SAMPLES)
            if (d.reverberating && firstVisible < 0f) {
                firstVisible = t
                rmsAtFirst = hopRms
            }
            if (hopRms > crestRms) {
                crestRms = hopRms
                swellCrest = t
            }
            if (d.reverberating) dryEnd = t
        }
        assertTrue(
            "shimmer must engage while the swell is still building, well before its crest " +
                "(first visible: ${firstVisible}s at ${"%.0f".format(100 * rmsAtFirst / crestRms)}% of " +
                "the ${"%.1f".format(swellCrest)}s crest)",
            firstVisible in 7.0f..8.2f && rmsAtFirst < 0.85f * crestRms,
        )
        assertTrue(
            "shimmer must dry with the release, not linger past the crest into the lām/nūn " +
                "(dry end: ${dryEnd}s, crest: ${swellCrest}s)",
            dryEnd > 0f && dryEnd <= 10.2f,
        )
    }

    @Test
    fun `Alafasy one seven - the pulse magnitude builds with the swell, soft at the start`() {
        // The shimmer must arrive soft: the on/off depth (tremoloGain) starts
        // near zero when the waqf's reverberation is first detected and only
        // reaches its full strength as the swell approaches the crest. A
        // full-depth pulse from the very first detected hop reads as a harsh
        // blink — the build is the crescendo itself.
        val samples = wavResource("alfasy_1_7_8k.wav")
        val d = Tarji()
        var consumed = 0
        var firstRev = -1
        var hops = 0
        var buildGain = -1f
        var nearCrestGain = -1f
        while (consumed + Tarji.HOP_SAMPLES <= samples.size) {
            d.onSamples8k(samples.copyOfRange(consumed, consumed + Tarji.HOP_SAMPLES))
            consumed += Tarji.HOP_SAMPLES
            hops++
            val t = consumed / Tarji.SAMPLE_RATE.toFloat()
            if (!d.reverberating) continue
            if (firstRev < 0) firstRev = hops
            val since = hops - firstRev
            if (since in 6..18) buildGain = maxOf(buildGain, d.tremoloGain)
            if (t in 9.6f..10.2f) nearCrestGain = maxOf(nearCrestGain, d.tremoloGain)
        }
        assertTrue("shimmer must engage within the waqf hold", firstRev >= 0)
        assertTrue(
            "the shimmer must settle to near full depth near the crest " +
                "(near-crest peak: ${"%.2f".format(nearCrestGain)})",
            nearCrestGain > 0.8f,
        )
        assertTrue(
            "the first pulses must be soft, far below the depth reached near the crest " +
                "(build: ${"%.2f".format(buildGain)}, near-crest: ${"%.2f".format(nearCrestGain)})",
            buildGain < 0.5f * nearCrestGain,
        )
    }

    @Test
    fun `the shimmer magnitude ramps into the swell instead of starting at full depth`() {
        // A held vibrato note must not blink at full on/off depth from the
        // first detected hop: the depth eases in over the event's build.
        val d = Tarji()
        var consumed = 0
        var firstRev = -1
        var hops = 0
        var early = -1f
        var settled = -1f
        val wave = heldNote(seconds = 3f, pitchHz = 130f, amHz = 2.5f, amDepth = 0.1f)
        while (consumed + Tarji.HOP_SAMPLES <= wave.size) {
            d.onSamples8k(wave.copyOfRange(consumed, consumed + Tarji.HOP_SAMPLES))
            consumed += Tarji.HOP_SAMPLES
            hops++
            if (!d.reverberating) continue
            if (firstRev < 0) firstRev = hops
            val since = hops - firstRev
            if (since in 5..15) early = maxOf(early, d.tremoloGain)
            if (since >= 70) settled = maxOf(settled, d.tremoloGain)
        }
        assertTrue("a vibrato hold must be detected", firstRev >= 0)
        assertTrue("the depth must settle to full", settled > 0.8f)
        assertTrue(
            "the first pulses must be soft, far below the settled depth " +
                "(early: ${"%.2f".format(early)}, settled: ${"%.2f".format(settled)})",
            early < 0.5f * settled,
        )
    }

    @Test
    fun `Hani one seven - the echoing fast hold engages without relighting the tail`() {
        val samples = wavResource("hani_1_7_8k.wav")
        val detector = Tarji()
        val wordGate = TarjiWordGate()
        var mainDetectionHops = 0
        var mainMaxRate = 0f
        val wordSpans = mutableListOf<Pair<Float, Float>>()
        var spanStart = -1f
        var visualStart = -1f
        var visualEnd = -1f
        var lateVisualHops = 0
        var consumed = 0
        while (consumed + Tarji.HOP_SAMPLES <= samples.size) {
            detector.onSamples8k(samples.copyOfRange(consumed, consumed + Tarji.HOP_SAMPLES))
            consumed += Tarji.HOP_SAMPLES
            val time = consumed / Tarji.SAMPLE_RATE.toFloat()
            if (time in 8.4f..10.1f && detector.reverberating) {
                mainDetectionHops++
                mainMaxRate = maxOf(mainMaxRate, detector.lastRateHz)
            }
            if (time >= 7.52f && detector.reverberating && spanStart < 0f) {
                spanStart = time
            } else if (!detector.reverberating && spanStart >= 0f) {
                wordSpans += spanStart to time
                spanStart = -1f
            }
            if (
                time >= 7.52f &&
                wordGate.allows(
                    gain = detector.tremoloGain,
                    detected = detector.reverberating,
                    eventStartMs = eventStartMs(detector),
                    wordStartMs = 0L,
                )
            ) {
                if (visualStart < 0f) visualStart = time
                visualEnd = time
                if (time >= 10.5f) lateVisualHops++
            }
        }
        if (spanStart >= 0f) {
            wordSpans += spanStart to samples.size / Tarji.SAMPLE_RATE.toFloat()
        }
        assertTrue(
            "Hani's fast echoing ḍād sustain must produce a substantial event",
            mainDetectionHops >= 50,
        )
        assertTrue("the fast event must reach the 10 Hz analysis bin", mainMaxRate >= 9.5f)
        val sustain = wordSpans.single { it.first <= 9f && it.second >= 9f }
        assertTrue("the event must begin on the ḍād sustain: $wordSpans", sustain.first in 8.2f..8.8f)
        assertTrue("the event must settle before the tail: $wordSpans", sustain.second in 9.8f..10.5f)
        assertTrue("the shimmer must be visible during the sustain", visualStart in 7.5f..8.8f)
        assertTrue("the shimmer must settle with the sustain", visualEnd in 9.8f..10.5f)
        assertEquals("the final consonant/echo tail must not relight the word", 0, lateVisualHops)
    }
}
