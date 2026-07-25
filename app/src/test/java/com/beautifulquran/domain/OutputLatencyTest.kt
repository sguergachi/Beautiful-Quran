package com.beautifulquran.domain

import com.beautifulquran.domain.OutputLatency.OutputKind
import com.beautifulquran.domain.OutputLatency.Route
import org.junit.Assert.assertEquals
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
    fun `preset values are the documented table`() {
        assertEquals(0L, OutputLatency.LOCAL_MS)
        assertEquals(180L, OutputLatency.A2DP_MS)
        assertEquals(80L, OutputLatency.LE_MS)
    }
}
