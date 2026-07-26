package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkLabPanelTest {

    @Test
    fun formatTuningCopy_includesAllKnobFields() {
        val t = InkEngine.Tuning(
            upcomingAlpha = 0.31f,
            inkFadeMs = 512,
            glintGlowRadius = 4.25f,
            tajweedPacing = true,
            cruiseCap = 1.55f,
            holdGhunnah = true,
        )
        val text = formatTuningCopy(t)
        assertTrue(text.contains("InkEngine.Tuning("))
        assertTrue(text.contains("upcomingAlpha = 0.31f"))
        assertTrue(text.contains("inkFadeMs = 512"))
        assertTrue(text.contains("glintGlowRadius = 4.25f"))
        assertTrue(text.contains("tajweedPacing = true"))
        assertTrue(text.contains("cruiseCap = 1.55f"))
        assertTrue(text.contains("holdGhunnah = true"))
        assertTrue(text.contains("holdConnect ="))
        assertTrue(text.contains("waslHandoff ="))
        assertTrue(text.contains("waqfShare ="))
        assertTrue(text.contains("waqfLengthScale ="))
        // Fields without lab sliders still snapshot so nothing is lost on apply.
        assertTrue(text.contains("sweepEaseX1 ="))
        assertTrue(text.contains("sweepEaseY2 ="))
    }

    @Test
    fun formatHighlightCopy_includesSyncKnobs() {
        val prevWordLead = InkEngine.highlightLeadMs
        val prevFadeLead = InkEngine.fadeLeadMs
        val prevLag = InkEngine.outputLatencyOverrideMs
        try {
            InkEngine.highlightLeadMs = 1_200
            InkEngine.fadeLeadMs = 420
            InkEngine.outputLatencyOverrideMs = 200
            val text = formatHighlightCopy()
            assertTrue(text.contains("highlightLeadMs = 1200"))
            assertTrue(text.contains("fadeLeadMs = 420"))
            assertTrue(text.contains("outputLatencyOverrideMs = 200"))
            InkEngine.outputLatencyOverrideMs = null
            assertTrue(formatHighlightCopy().contains("null"))
        } finally {
            InkEngine.highlightLeadMs = prevWordLead
            InkEngine.fadeLeadMs = prevFadeLead
            InkEngine.outputLatencyOverrideMs = prevLag
        }
    }

    @Test
    fun highlightSyncDefaultsMatchShippedConstants() {
        // Fresh process defaults — if a prior test left overrides, restore.
        InkEngine.highlightLeadMs = InkEngine.DEFAULT_HIGHLIGHT_LEAD_MS
        InkEngine.fadeLeadMs = InkEngine.DEFAULT_FADE_LEAD_MS
        InkEngine.outputLatencyOverrideMs = null
        assertEquals(114, InkEngine.DEFAULT_HIGHLIGHT_LEAD_MS)
        assertEquals(InkEngine.DEFAULT_HIGHLIGHT_LEAD_MS, InkEngine.highlightLeadMs)
        assertEquals(500, InkEngine.DEFAULT_FADE_LEAD_MS)
        assertEquals(InkEngine.DEFAULT_FADE_LEAD_MS, InkEngine.fadeLeadMs)
        assertNull(InkEngine.outputLatencyOverrideMs)
    }

    @Test
    fun logSlider_roundTripsEndpointsAndMid() {
        val positive = 40f..600f
        assertEquals(0f, inkLabValueToPosition(40f, positive), 1e-5f)
        assertEquals(1f, inkLabValueToPosition(600f, positive), 1e-5f)
        assertEquals(40f, inkLabPositionToValue(0f, positive), 1e-4f)
        assertEquals(600f, inkLabPositionToValue(1f, positive), 1e-3f)
        // Geometric mid: sqrt(min * max)
        val mid = kotlin.math.sqrt(40f * 600f)
        assertEquals(0.5f, inkLabValueToPosition(mid, positive), 1e-4f)
        assertEquals(mid, inkLabPositionToValue(0.5f, positive), 1e-3f)

        val fromZero = 0f..1200f
        assertEquals(0f, inkLabValueToPosition(0f, fromZero), 1e-5f)
        assertEquals(1f, inkLabValueToPosition(1200f, fromZero), 1e-5f)
        assertEquals(0f, inkLabPositionToValue(0f, fromZero), 1e-4f)
        assertEquals(1200f, inkLabPositionToValue(1f, fromZero), 1e-2f)
        // Half track lands well below linear mid — more room at the low end.
        val half = inkLabPositionToValue(0.5f, fromZero)
        assertTrue(half < 600f)
        assertTrue(half > 0f)
        assertEquals(0.5f, inkLabValueToPosition(half, fromZero), 1e-4f)
    }

    @Test
    fun logSlider_roundTripsSampledValues() {
        val ranges = listOf(0.05f..0.6f, 100f..2400f, 1f..2f, 0f..1f)
        for (range in ranges) {
            for (t in listOf(0f, 0.1f, 0.33f, 0.5f, 0.75f, 0.9f, 1f)) {
                val v = inkLabPositionToValue(t, range)
                assertEquals(
                    t,
                    inkLabValueToPosition(v, range),
                    1e-4f,
                )
            }
        }
    }
}
