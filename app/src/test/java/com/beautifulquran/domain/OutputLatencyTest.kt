package com.beautifulquran.domain

import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.OutputLatency.OutputKind
import com.beautifulquran.domain.OutputLatency.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputLatencyTest {

    @Test
    fun `empty kinds are local with zero latency`() {
        assertEquals(Route.LOCAL, OutputLatency.classify(emptySet()))
        assertEquals(0L, OutputLatency.latencyMs(emptySet()))
    }

    @Test
    fun `local only is zero latency`() {
        assertEquals(Route.LOCAL, OutputLatency.classify(setOf(OutputKind.LOCAL)))
        assertEquals(OutputLatency.LOCAL_MS, OutputLatency.latencyMs(Route.LOCAL))
    }

    @Test
    fun `A2DP wins over speaker still listed as an output`() {
        val kinds = setOf(OutputKind.LOCAL, OutputKind.BLUETOOTH_A2DP)
        assertEquals(Route.BLUETOOTH_A2DP, OutputLatency.classify(kinds))
        assertEquals(OutputLatency.A2DP_MS, OutputLatency.latencyMs(kinds))
    }

    @Test
    fun `LE is used when no classic A2DP is present`() {
        val kinds = setOf(OutputKind.LOCAL, OutputKind.BLUETOOTH_LE)
        assertEquals(Route.BLUETOOTH_LE, OutputLatency.classify(kinds))
        assertEquals(OutputLatency.LE_MS, OutputLatency.latencyMs(kinds))
    }

    @Test
    fun `A2DP wins over LE when both are present`() {
        val kinds = setOf(
            OutputKind.BLUETOOTH_A2DP,
            OutputKind.BLUETOOTH_LE,
            OutputKind.LOCAL,
        )
        assertEquals(Route.BLUETOOTH_A2DP, OutputLatency.classify(kinds))
        assertEquals(OutputLatency.A2DP_MS, OutputLatency.latencyMs(kinds))
    }

    @Test
    fun `heardMs subtracts latency and never goes negative`() {
        assertEquals(820L, OutputLatency.heardMs(1_000L, 180L))
        assertEquals(0L, OutputLatency.heardMs(50L, 180L))
        assertEquals(1_000L, OutputLatency.heardMs(1_000L, 0L))
    }

    @Test
    fun `highlightMs advances the clock by lead after lag`() {
        // Word timed at 5000 should light when media is at 3800 with 1200 lead.
        assertEquals(5_000L, OutputLatency.highlightMs(3_800L, latencyMs = 0L, leadMs = 1_200L))
        // Lag and lead net: 1000 − 180 + 1200 = 2020.
        assertEquals(2_020L, OutputLatency.highlightMs(1_000L, latencyMs = 180L, leadMs = 1_200L))
        // Zero lead matches heardMs.
        assertEquals(
            OutputLatency.heardMs(1_000L, 180L),
            OutputLatency.highlightMs(1_000L, latencyMs = 180L, leadMs = 0L),
        )
        assertEquals(0L, OutputLatency.highlightMs(50L, latencyMs = 180L, leadMs = 0L))
    }

    @Test
    fun `word lead does not move the heard clock used by non-word surfaces`() {
        assertEquals(820L, OutputLatency.heardMs(1_000L, latencyMs = 180L))
        assertEquals(
            2_020L,
            OutputLatency.highlightMs(1_000L, latencyMs = 180L, leadMs = 1_200L),
        )
    }

    @Test
    fun `word lead cannot cross encoded opening silence`() {
        val segments = listOf(
            Segment(position = 1, startMs = 1_179, endMs = 2_094),
            Segment(position = 2, startMs = 2_094, endMs = 2_814),
        )
        val duringSilence = OutputLatency.highlightMs(
            mediaPositionMs = 1_100,
            latencyMs = 0,
            leadMs = 114,
            leadNotBeforeMs = segments.first().startMs,
        )
        assertEquals(1_100L, duringSilence)
        assertEquals(null, HighlightEngine.activeWord(segments, duringSilence))

        val voiceStart = OutputLatency.highlightMs(
            mediaPositionMs = 1_179,
            latencyMs = 0,
            leadMs = 114,
            leadNotBeforeMs = segments.first().startMs,
        )
        // At the gate, ramped lead is still 0 — continuous with silence.
        assertEquals(1_179L, voiceStart)
        assertEquals(1, HighlightEngine.activeWord(segments, voiceStart))
    }

    @Test
    fun `word lead ramps in after the first word so handoff settle cannot skip it`() {
        // Hard +lead at the gate (210 → 324) exceeds HighlightClock.MAX_SETTLE_STEP_MS
        // (100) and freezes the post-ayah-handoff clock through a short word 1.
        val gate = 210L
        val lead = 114L
        assertEquals(
            210L,
            OutputLatency.highlightMs(210, latencyMs = 0, leadMs = lead, leadNotBeforeMs = gate),
        )
        // Half-way through the ramp: applied lead == pastGate.
        assertEquals(
            310L, // 260 + min(114, 50)
            OutputLatency.highlightMs(260, latencyMs = 0, leadMs = lead, leadNotBeforeMs = gate),
        )
        // Full lead once pastGate >= leadMs.
        assertEquals(
            438L, // 324 + 114
            OutputLatency.highlightMs(324, latencyMs = 0, leadMs = lead, leadNotBeforeMs = gate),
        )
        // No gate: full lead from the first sample (unchanged).
        assertEquals(
            374L,
            OutputLatency.highlightMs(260, latencyMs = 0, leadMs = lead, leadNotBeforeMs = 0),
        )
    }

    @Test
    fun `ramped lead plus settle lights word 1 after media-item handoff`() {
        // Hani 3:7-shaped row: short word 1 inside the post-handoff settle window.
        val segments = listOf(
            Segment(position = 1, startMs = 210, endMs = 490),
            Segment(position = 2, startMs = 490, endMs = 1_340),
        )
        val clock = HighlightClock()
        val words = mutableListOf<Int?>()
        for (tick in 0 until 25) {
            val pos = tick * 33L
            val raw = OutputLatency.highlightMs(
                mediaPositionMs = pos,
                latencyMs = 0,
                leadMs = 114,
                leadNotBeforeMs = segments.first().startMs,
            )
            val key = "ayah7"
            val ms = clock.sample(key, raw)
            words.add(HighlightEngine.activeWord(segments, ms))
        }
        assertTrue(
            "word 1 must light before word 2 after handoff; saw $words",
            words.any { it == 1 },
        )
        val firstLit = words.first { it != null }
        assertEquals(1, firstLit)
    }

    @Test
    fun `preset values are the documented table`() {
        assertEquals(0L, OutputLatency.LOCAL_MS)
        assertEquals(180L, OutputLatency.A2DP_MS)
        assertEquals(80L, OutputLatency.LE_MS)
    }
}
