package com.beautifulquran.tarjilab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec for the listener-authored ground truth carried by lab exports. */
class TarjiLabExpectationTest {

    @Test
    fun `hold window is the labeled ground truth`() {
        val expectation = TarjiLabExpectation()
            .withWindow(TarjiHoldWindow(200f, 900f), 2_000f)
            .labeled(TarjiExpectationKind.PULSES)
            .withEnvelope(listOf(0.2f, 0.8f, 0.4f))

        assertTrue(expectation.hasWindow)
        assertEquals(200f, expectation.startMs)
        assertEquals(900f, expectation.endMs)
        assertEquals(listOf(0.2f, 0.8f, 0.4f), expectation.envelope)
        assertTrue(expectation.canPreview)
    }

    @Test
    fun `crest marks derive a robust target frequency`() {
        val expectation = TarjiLabExpectation()
            .markStart(200f, 2_000f)
            .addCrest(300f, 2_000f)
            .addCrest(500f, 2_000f)
            .addCrest(710f, 2_000f)
            .markEnd(900f, 2_000f)

        assertEquals(TarjiExpectationKind.PULSES, expectation.kind)
        assertEquals(200f, expectation.startMs)
        assertEquals(900f, expectation.endMs)
        assertEquals(3, expectation.crestMs.size)
        assertEquals(5_000f / 1_025f, expectation.rateHz!!, 0.001f)
    }

    @Test
    fun `edge edits keep only labels inside the expected span`() {
        val expectation = TarjiLabExpectation()
            .addCrest(100f, 1_000f)
            .addCrest(400f, 1_000f)
            .addCrest(800f, 1_000f)
            .markStart(200f, 1_000f)
            .markEnd(700f, 1_000f)

        assertEquals(listOf(400f), expectation.crestMs)
        assertEquals(TarjiExpectationKind.NO_SHIMMER, TarjiLabExpectation.noShimmer().kind)
        assertNull(TarjiLabExpectation.noShimmer().rateHz)
    }

    @Test
    fun `rate slider preserves phase anchor and fills the authored span`() {
        val expectation = TarjiLabExpectation()
            .markStart(100f, 1_200f)
            .addCrest(350f, 1_200f)
            .markEnd(1_100f, 1_200f)
            .withRate(5f)

        assertEquals(listOf(150f, 350f, 550f, 750f, 950f), expectation.crestMs)
        assertEquals(350f, expectation.phaseAnchorMs)
        assertEquals(5f, expectation.rateHz!!, 0.001f)
        assertTrue(expectation.canPreview)
    }

    @Test
    fun `target pulse follows authored crests build and dry down`() {
        val expectation = TarjiLabExpectation(
            kind = TarjiExpectationKind.PULSES,
            startMs = 100f,
            endMs = 900f,
            crestMs = listOf(250f, 450f, 650f, 850f),
            style = TarjiTargetStyle(buildMs = 200f, dryMs = 100f),
        )

        assertFalse(targetTarjiPointAt(expectation, 50f).holding)
        assertEquals(0f, targetTarjiPointAt(expectation, 100f).gain, 0f)
        val crest = targetTarjiPointAt(expectation, 450f)
        assertTrue(crest.holding)
        assertEquals(1f, crest.tremolo, 1e-4f)
        assertEquals(1f, crest.gain, 1e-4f)
        assertEquals(-1f, targetTarjiPointAt(expectation, 550f).tremolo, 1e-4f)
        assertEquals(0f, targetTarjiPointAt(expectation, 900f).gain, 0f)
    }

    @Test
    fun `comparison reports exact detector timing and frequency`() {
        val tremolo = floatArrayOf(0f, 0f, 1f, 0f, -1f, 0f, 0f, 1f, 0f, -1f, 0f, 0f)
        val reverberating = BooleanArray(12) { it in 1..9 }
        val rate = FloatArray(12) { if (it in 1..9) 10f else 0f }
        val trace = TarjiLabTrace(
            hopCount = 12,
            hopDurationMs = 20f,
            firstAnalysisHop = 0,
            envRms = FloatArray(12),
            tremolo = tremolo,
            gain = FloatArray(12) { 1f },
            reverberating = reverberating,
            rateHz = rate,
            pitchHz = FloatArray(12),
            holdMs = FloatArray(12),
        )
        val expectation = TarjiLabExpectation(
            kind = TarjiExpectationKind.PULSES,
            startMs = 30f,
            endMs = 190f,
            crestMs = listOf(50f, 150f),
        )

        val comparison = compareTarjiExpectation(expectation, trace)
        assertEquals(0f, comparison.startErrorMs!!, 0f)
        assertEquals(0f, comparison.endErrorMs!!, 0f)
        assertEquals(0f, comparison.rateErrorHz!!, 0f)
        assertEquals(0f, comparison.meanCrestErrorMs!!, 0f)
    }
}
