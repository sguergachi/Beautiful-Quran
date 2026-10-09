package com.beautifulquran.playback

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

class TarjiExperimentsTest {
    private fun knobs() = Tarji().apply {
        minTremoloHz = 1.5f
        maxTremoloHz = 10f
        minTremoloDepth = 0.035f
        minPeriodicity = 0.4f
        holdMinMs = 300f
        attackMs = 50f
        releaseMs = 100f
    }

    private fun frames(rate: Double = 2.7, pitch: Boolean = false, hopMs: Double = 20.0) =
        List(250) { i ->
            val wave = sin(2.0 * PI * rate * i * hopMs / 1000.0)
            TarjiFrame(
                hop = i, hopMs = hopMs,
                hopRms = if (pitch) 0.2f else (0.2 * (1.0 + 0.3 * wave)).toFloat(),
                rms80 = 0.2f, level = 0.2f, voiced = true,
                holdMs = ((i + 1) * hopMs).toFloat(), holdStartHop = 0,
                holdPitchHz = 140f, holdClarity = 1f,
                f0Hz = if (pitch) (140.0 * 2.0.pow(40.0 * wave / 1200.0)).toFloat() else 140f,
                f0Valid = true, pitchQuality = 1f,
            )
        }

    private fun gains(frames: List<TarjiFrame>, mode: TarjiDetectorMode): FloatArray {
        val knobs = knobs()
        if (mode == TarjiDetectorMode.Recording) return TarjiRecordingDetector.analyze(frames, knobs).gain
        val detector = TarjiExperimentalDetector()
        return FloatArray(frames.size) { i ->
            detector.next(frames[i], mode, knobs)
            detector.decision.gain
        }
    }

    @Test fun eachMethodRecognizesAmplitudeAndPitchCyclesAtActualHopDurations() {
        val failures = mutableListOf<String>()
        for (mode in listOf(TarjiDetectorMode.Cycles, TarjiDetectorMode.Spectrum, TarjiDetectorMode.Recording)) {
            for (pitch in listOf(false, true)) for (hop in listOf(20.0, 176_000.0 / 8820.0)) {
                for (rate in listOf(1.5, 2.7, 5.0, 8.0, 10.0)) {
                    val peak = gains(frames(rate, pitch, hop), mode).maxOrNull()!!
                    if (peak <= 0.3f) failures += "$mode pitch=$pitch hop=$hop rate=$rate gain=$peak"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun nonRepeatingContoursDoNotAcquire() {
        val still = frames().map { it.copy(hopRms = 0.2f, f0Hz = 140f) }
        val ramp = still.mapIndexed { i, f -> f.copy(hopRms = 0.08f + 0.001f * i) }
        val step = still.mapIndexed { i, f -> f.copy(hopRms = if (i < 125) 0.1f else 0.4f) }
        val slide = still.mapIndexed { i, f -> f.copy(f0Hz = 140f + 0.1f * i) }
        val bump = still.mapIndexed { i, f ->
            f.copy(hopRms = (0.2 + 0.2 * exp(-((i - 125) / 15.0).pow(2))).toFloat())
        }
        for (mode in listOf(TarjiDetectorMode.Cycles, TarjiDetectorMode.Spectrum, TarjiDetectorMode.Recording)) {
            for ((name, contour) in listOf("still" to still, "ramp" to ramp, "step" to step,
                "slide" to slide, "bump" to bump)) {
                assertEquals("$mode $name", 0f, gains(contour, mode).maxOrNull()!!, 0f)
            }
        }
    }

    @Test fun outOfBandModulationCannotAcquire() {
        for (mode in listOf(TarjiDetectorMode.Cycles, TarjiDetectorMode.Spectrum, TarjiDetectorMode.Recording))
            for (pitch in listOf(false, true)) for (rate in listOf(0.8, 1.3, 11.0, 14.0))
                assertEquals("$mode pitch=$pitch rate=$rate", 0f, gains(frames(rate, pitch), mode).maxOrNull()!!, 0f)
    }

    @Test fun invalidFreshPitchCannotAcquireOrKeepPitchCycles() {
        val missing = frames(pitch = true).map { it.copy(f0Valid = false) }
        for (mode in listOf(TarjiDetectorMode.Cycles, TarjiDetectorMode.Spectrum, TarjiDetectorMode.Recording))
            assertEquals("$mode", 0f, gains(missing, mode).maxOrNull()!!, 0f)
        val modulation = TarjiModulation()
        val valid = frames(pitch = true)
        for (i in 0 until 120) modulation.next(valid[i], TarjiDetectorMode.Cycles, knobs())
        assertTrue(modulation.fm.keep)
        for (i in 120 until 124) modulation.next(valid[i].copy(f0Valid = false), TarjiDetectorMode.Cycles, knobs())
        assertFalse(modulation.fm.keep)
    }

    @Test fun slowCycleKeepDoesNotFlapBetweenTurningPoints() {
        val modulation = TarjiModulation()
        val source = frames(2.7)
        for (i in source.indices) {
            modulation.next(source[i], TarjiDetectorMode.Cycles, knobs())
            if (i in 120..230) assertTrue("hop $i", modulation.am.keep)
        }
    }

    @Test fun recordingAdmissionUsesForwardHoldGateAndClearsOutOfBoundsReads() {
        val knobs = knobs().apply { holdMinMs = 1200f }
        val result = TarjiRecordingDetector.analyze(frames(), knobs)
        assertTrue(result.eventStart.filter { it >= 0 }.all { it * result.hopMs >= 1200.0 })
        assertTrue(result.acousticOnset.filter { it >= 0 }.minOrNull()!! < 1200)
        val out = TarjiEarSample()
        assertTrue(result.sample(2500.0, out))
        assertTrue(out.gain > 0f)
        assertFalse(result.sample(5000.0, out))
        assertEquals(0f, out.gain, 0f)
        assertFalse(result.sample(-1.0, out))
        assertEquals(0f, out.gain, 0f)
    }

    @Test fun recordingAnalysisObservesCancellationDuringLongReplay() {
        var checks = 0
        try {
            TarjiRecordingDetector.analyze(frames(), knobs()) { ++checks < 3 }
            fail("analysis should cancel")
        } catch (_: CancellationException) {
            assertEquals(3, checks)
        }
    }

    @Test fun constantCarriersAndSilenceDoNotInventFreshPitchReverberation() {
        for (carrier in listOf(70.0, 117.0, 220.0, 340.0)) {
            val baseline = knobs()
            val experiment = TarjiExperimentalDetector()
            val hop = FloatArray(Tarji.HOP_SAMPLES)
            var peak = 0f
            repeat(250) { i ->
                for (j in hop.indices) hop[j] =
                    (0.3 * sin(2.0 * PI * carrier * (i * hop.size + j) / Tarji.SAMPLE_RATE)).toFloat()
                baseline.onSamples8k(hop)
                experiment.next(baseline.measurements, TarjiDetectorMode.Cycles, baseline)
                peak = maxOf(peak, experiment.decision.gain)
            }
            assertEquals("carrier $carrier", 0f, peak, 0f)
            repeat(20) { baseline.onSamples8k(FloatArray(Tarji.HOP_SAMPLES)) }
            assertFalse(baseline.measurements.f0Valid)
            assertTrue(baseline.measurements.f0Hz.isNaN())
        }
    }
}
