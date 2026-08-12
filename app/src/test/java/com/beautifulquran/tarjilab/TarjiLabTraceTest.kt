package com.beautifulquran.tarjilab

import com.beautifulquran.data.model.Segment
import com.beautifulquran.playback.Tarji
import com.beautifulquran.playback.TarjiLabCapture
import com.beautifulquran.playback.TarjiLabTrim
import com.beautifulquran.ui.reader.InkEngine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec for the Tarjīʿ Lab's offline pipeline: capture → re-analysis with
 * editable knobs → trace/fit → trim. Waves are synthesized 8 kHz mono and
 * cut into hops exactly as [com.beautifulquran.playback.VoiceEnergy]
 * decimates them, so the pure detector sees the live shape.
 */
class TarjiLabTraceTest {

    /** [seconds] of a held note at [pitchHz] with optional AM at [amHz]. */
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

    /** Hop-chunked capture of [pcm] at the detector's native 8 kHz/160. */
    private fun captureOf(pcm: FloatArray): TarjiLabCapture {
        val hop = Tarji.HOP_SAMPLES
        val n = pcm.size / hop
        return TarjiLabCapture(
            sampleRate = Tarji.SAMPLE_RATE,
            hopSamples = hop,
            hopContentMs = FloatArray(n) { it * Tarji.HOP_MS.toFloat() },
            pcm = FloatArray(n * hop) { pcm[it] },
        )
    }

    @Test
    fun `trace finds a clean reverberating span at the AM rate`() {
        val pcm = heldNote(seconds = 2.5f, pitchHz = 130f, amHz = 5f, amDepth = 0.25f)
        val trace = analyzeTarjiCapture(captureOf(pcm), TarjiLabKnobs())

        assertEquals("warmup: first analysis at hop 3", 3, trace.firstAnalysisHop)
        assertNotNull(trace.reverberatingSpan)
        val span = trace.reverberatingSpan!!
        // Hold is 2.5 s; the detector needs ≥300 ms hold + band evidence.
        assertTrue("span long: ${span.first}..${span.last}", span.last - span.first > 40)
        assertTrue(
            "rate tracks the AM: ${trace.meanRateHz} Hz",
            trace.meanRateHz in 4f..6f,
        )
        assertTrue(
            "loudness channel exposes its candidate rate",
            span.any { trace.amplitudeRateHz[it] in 4f..6f },
        )
        assertTrue(
            "loudness channel exposes depth and periodicity",
            span.any { trace.amplitudeDepth[it] > 0.1f && trace.amplitudePeriodicity[it] > 0.2f },
        )
        assertTrue("AM supplies the visible pulse", span.any { trace.visualUsesAmplitude[it] })
        // Envelope RMS of level 0.3 sin ≈ 0.3/√2 ≈ 0.21 (modulated ±25%).
        val mid = trace.envRms[(span.first + span.last) / 2]
        assertTrue("env rms $mid", mid in 0.15f..0.3f)
        // The shimmer builds with the swell and rides the sustain.
        assertTrue("gain engages in span", trace.gain[span.first + 5] > 0.02f)
        assertTrue("gain full late in span", trace.gain[span.last - 2] > 0.5f)
        // Phase-locked: the tremolo leads the 80 ms frame envelope by two
        // hops (the detector's live-sample phase lead) — so the tremolo's
        // rise and fall must agree with the envelope's, shifted by that lead.
        var agree = 0
        var total = 0
        for (i in span.first + 4 until span.last - 2) {
            val voiceSwell = trace.envRms[i + 2] - trace.envRms[i]
            val tremRise = trace.tremolo[i] - trace.tremolo[i - 2]
            if (voiceSwell * tremRise >= 0f) agree++
            total++
        }
        assertTrue(
            "tremolo phase-locked to the envelope ($agree/$total)",
            agree > total * 0.85,
        )
    }

    @Test
    fun `knobs gate the trace like the live detector`() {
        val pcm = heldNote(seconds = 2.5f, pitchHz = 130f, amHz = 5f, amDepth = 0.25f)
        val capture = captureOf(pcm)

        assertNull("hold min above the note kills the span",
            analyzeTarjiCapture(capture, TarjiLabKnobs(holdMinMs = 5000f)).reverberatingSpan)
        assertNull("depth gate above the AM kills the span",
            analyzeTarjiCapture(capture, TarjiLabKnobs(minTremoloDepth = 0.5f)).reverberatingSpan)
        assertNull("rate ceiling below the AM kills the span",
            analyzeTarjiCapture(capture, TarjiLabKnobs(maxTremoloHz = 3f)).reverberatingSpan)

        // A shallow AM (3% → nominal depth 0.021, well under the 0.035 gate)
        // keeps the pitch-vibrato track quiet, so an out-of-band rate floor
        // can be proven to silence the detector (deep AM leaks a few alias
        // hops into the FM track at the band edge).
        val shallow = heldNote(seconds = 2.5f, pitchHz = 130f, amHz = 5f, amDepth = 0.03f)
        assertNull("rate floor above the AM kills the span",
            analyzeTarjiCapture(captureOf(shallow), TarjiLabKnobs(minTremoloHz = 6f))
                .reverberatingSpan)
        assertNull("default depth rejects shallow AM",
            analyzeTarjiCapture(captureOf(shallow), TarjiLabKnobs()).reverberatingSpan)
        assertNotNull("lowered depth admits shallow AM",
            analyzeTarjiCapture(captureOf(shallow), TarjiLabKnobs(minTremoloDepth = 0.01f))
                .reverberatingSpan)
    }

    @Test
    fun `trace point interpolates between hops`() {
        val pcm = heldNote(seconds = 2.5f, pitchHz = 130f, amHz = 5f, amDepth = 0.25f)
        val trace = analyzeTarjiCapture(captureOf(pcm), TarjiLabKnobs())
        assertNotNull(trace.reverberatingSpan)
        val span = trace.reverberatingSpan!!
        val mid = (span.first + span.last) / 2
        val before = tracePointAt(trace, (mid - 1) * trace.hopDurationMs)
        val at = tracePointAt(trace, mid * trace.hopDurationMs)
        val after = tracePointAt(trace, (mid + 1) * trace.hopDurationMs)
        assertTrue(before.reverberating && at.reverberating && after.reverberating)
        // Mid-hop interpolation lands between the two neighbouring values.
        val half = tracePointAt(trace, (mid + 0.5f) * trace.hopDurationMs)
        val lo = minOf(trace.tremolo[mid], trace.tremolo[mid + 1])
        val hi = maxOf(trace.tremolo[mid], trace.tremolo[mid + 1])
        assertTrue("half between hops", half.tremolo in lo - 1e-4f..hi + 1e-4f)
        // Outside the trace the point clamps, never crashes.
        tracePointAt(trace, -5000f)
        tracePointAt(trace, trace.hopCount * trace.hopDurationMs * 4f)
    }

    @Test
    fun `sine fit matches the AM rate and amplitude`() {
        val pcm = heldNote(seconds = 2.5f, pitchHz = 130f, amHz = 5f, amDepth = 0.25f)
        val trace = analyzeTarjiCapture(captureOf(pcm), TarjiLabKnobs())
        assertNotNull(fitTarjiSine(trace))
        val fit = fitTarjiSine(trace)!!
        assertTrue("fit rate ${fit.rateHz}", fit.rateHz in 4f..6f)
        // The detector's visual signal runs hot (the 20 ms hop RMS swings
        // wider than the 80 ms frame it is normalized against), so the fitted
        // amplitude is above the nominal 0.25 AM depth — but bounded.
        assertTrue("fit amplitude ${fit.amplitude}", fit.amplitude in 0.3f..1.1f)
        // The fit's own crest reaches its amplitude somewhere in its span.
        var peak = 0f
        var hop = fit.startHop
        while (hop <= fit.endHop) {
            peak = maxOf(peak, fit.valueAt(hop * trace.hopDurationMs, trace.hopDurationMs))
            hop++
        }
        assertTrue("fit peak ≈ amplitude: $peak", abs(peak - fit.amplitude) < 0.02f)
        assertTrue("fit null outside its span", fit.valueAt(-100f, trace.hopDurationMs) == 0f)
    }

    @Test
    fun `word span covers all occurrences and the trim maps hops`() {
        val segs = listOf(
            Segment(1, 0L, 500L),
            Segment(2, 500L, 1200L),
            Segment(3, 1200L, 2000L),
            Segment(3, 2600L, 3200L),
            Segment(4, 3200L, 4000L),
        )
        assertNotNull(TarjiLabTrim.wordSpanMs(segs, 3, leadMs = 300L, tailMs = 1000L))
        val span = TarjiLabTrim.wordSpanMs(segs, 3, leadMs = 300L, tailMs = 1000L)!!
        assertEquals(900L, span.first)
        assertEquals(4200L, span.last)

        val hop = Tarji.HOP_SAMPLES
        val capture = TarjiLabCapture(
            sampleRate = Tarji.SAMPLE_RATE,
            hopSamples = hop,
            hopContentMs = FloatArray(300) { it * 20f },
            pcm = FloatArray(300 * hop),
        )
        val range = TarjiLabTrim.hopRangeInSpan(capture, firstHopMediaMs = 900.0, span)
        assertEquals(0, range.first)
        assertEquals(165, range.last) // (4200 − 900) / 20
        val sliced = capture.slice(range)
        assertEquals(166, sliced.hopCount)
        assertEquals(0f, sliced.hopContentMs[0])
        assertEquals(3300f, sliced.hopContentMs[165])
    }

    @Test
    fun `knobs map to and from the Ink Lab tuning`() {
        val t = InkEngine.Tuning()
        val knobs = TarjiLabKnobs.fromTuning(t)
        assertEquals(t.glintResonanceMaxHz, knobs.maxTremoloHz, 0f)
        assertEquals(t.tarjiHoldMinMs, knobs.holdMinMs, 0f)
        val back = TarjiLabKnobs.applyToTuning(knobs, t)
        assertEquals(t, back)
        val modified = TarjiLabKnobs(maxTremoloHz = 4f, holdMinMs = 900f)
        val applied = TarjiLabKnobs.applyToTuning(modified, t)
        assertEquals(4f, applied.glintResonanceMaxHz, 0f)
        assertEquals(900f, applied.tarjiHoldMinMs, 0f)
    }

    @Test
    fun `words without marks span the gap between neighbours`() {
        val segs = listOf(
            Segment(1, 0L, 500L),
            Segment(2, 500L, 1200L),
            Segment(4, 2000L, 2600L),
        )
        assertNotNull(TarjiLabTrim.wordSpanMs(segs, 3, leadMs = 0L, tailMs = 0L))
        val span = TarjiLabTrim.wordSpanMs(segs, 3, leadMs = 0L, tailMs = 0L)!!
        assertEquals(1200L, span.first)
        assertEquals(2000L, span.last)
        assertNull(TarjiLabTrim.wordSpanMs(emptyList(), 1, 0L, 0L))
    }
}
