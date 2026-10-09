package com.beautifulquran.tarjilab

import com.beautifulquran.playback.Tarji
import com.beautifulquran.playback.TarjiDetectorMode
import com.beautifulquran.playback.TarjiLabCapture
import org.junit.Assert.*
import org.junit.Test

class TarjiLabAnalysisTest {
    private val capture = TarjiLabCapture(
        Tarji.SAMPLE_RATE, Tarji.HOP_SAMPLES,
        FloatArray(8) { it * 20f }, FloatArray(8 * Tarji.HOP_SAMPLES),
    )

    @Test
    fun `mode changes clear decisions and reference without interrupting preview`() {
        val knobs = TarjiLabKnobs()
        val trace = analyzeTarjiCapture(capture, knobs)
        val before = TarjiLabViewModel.TarjiLabUiState(
            capture = capture, trace = trace, mode = TarjiDetectorMode.Current, knobs = knobs,
            reference = TarjiLabReference(knobs, trace), showingReference = true,
            previewPlaying = true, previewPositionMs = 47f,
        )
        val pending = before.forMode(TarjiDetectorMode.Recording)
        assertNull(pending.trace)
        assertNull(pending.reference)
        assertNull(pending.displayTrace)
        assertFalse(pending.showingReference)
        assertTrue(pending.analyzing)
        assertSame(capture, pending.capture)
        assertEquals(knobs, pending.knobs)
        assertTrue(pending.previewPlaying)
        assertEquals(47f, pending.previewPositionMs, 0f)
        assertEquals(TarjiDetectorMode.Recording, pending.mode)
    }

    @Test
    fun `a stale capture mode or detector tuning cannot publish its result`() {
        val knobs = TarjiLabKnobs()
        val live = TarjiLabViewModel.TarjiLabUiState(capture = capture, knobs = knobs, mode = TarjiDetectorMode.Spectrum)
        val replacement = TarjiLabCapture(capture.sampleRate, capture.hopSamples,
            capture.hopContentMs.copyOf(), capture.pcm.copyOf())
        assertTrue(live.acceptsAnalysis(capture, knobs, TarjiDetectorMode.Spectrum))
        assertFalse(live.acceptsAnalysis(replacement, knobs, TarjiDetectorMode.Spectrum))
        assertFalse(live.acceptsAnalysis(capture, knobs, TarjiDetectorMode.Current))
        assertFalse(live.acceptsAnalysis(capture, knobs.copy(minTremoloHz = 2.7f), TarjiDetectorMode.Spectrum))
        assertTrue(live.copy(knobs = knobs.copy(glintBrightness = 2f))
            .acceptsAnalysis(capture, knobs, TarjiDetectorMode.Spectrum))
    }

    @Test
    fun `choosing a method before capture does not claim analysis is pending`() {
        val pending = TarjiLabViewModel.TarjiLabUiState(mode = TarjiDetectorMode.Current)
            .forMode(TarjiDetectorMode.Cycles)
        assertFalse(pending.analyzing)
        assertNull(pending.displayTrace)
    }

    @Test
    fun `developer method never enters the exported sample or its reciter knobs`() {
        val state = TarjiLabViewModel.TarjiLabUiState(
            capture = capture, mode = TarjiDetectorMode.Current,
            sampleReciterId = 7, sampleReciterName = "Hani Ar-Rifai",
        )
        val current = state.sampleForExport()!!
        for (mode in TarjiDetectorMode.entries) {
            assertEquals(current, state.copy(mode = mode).sampleForExport())
        }
    }
}
