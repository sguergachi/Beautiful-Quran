package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure encode/decode + engine apply for [InkLabSnapshot]. SharedPreferences
 * wiring needs a device; the wire format and engine restore are what must
 * not drift.
 */
class InkLabStoreTest {

    @Test
    fun encodeThenDecode_roundTripsAllFields() {
        val original = InkLabSnapshot(
            upcomingAlpha = 0.31f,
            inkFadeMs = 512,
            glintGlowRadius = 4.25f,
            tajweedPacing = true,
            cruiseCap = 1.55f,
            holdGhunnah = true,
            highlightLeadMs = 900,
            fadeLeadMs = 333,
            outputLatencyOverrideMs = 180,
        )
        val restored = InkLabSnapshot.decode(InkLabSnapshot.encode(original))
        assertEquals(original, restored)
    }

    @Test
    fun decode_returnsNullForMalformedJson() {
        assertNull(InkLabSnapshot.decode(""))
        assertNull(InkLabSnapshot.decode("{"))
        assertNull(InkLabSnapshot.decode("not-json"))
    }

    @Test
    fun decode_fillsMissingFieldsWithShippedDefaults() {
        // Older save before highlight lead existed — only a couple of knobs.
        val partial = """{"schema":1,"upcomingAlpha":0.4,"inkFadeMs":700}"""
        val snap = InkLabSnapshot.decode(partial)
        assertNotNull(snap)
        assertEquals(0.4f, snap!!.upcomingAlpha, 0.0001f)
        assertEquals(700, snap.inkFadeMs)
        assertEquals(InkEngine.DEFAULT_HIGHLIGHT_LEAD_MS, snap.highlightLeadMs)
        assertEquals(InkEngine.DEFAULT_FADE_LEAD_MS, snap.fadeLeadMs)
        assertNull(snap.outputLatencyOverrideMs)
        // Untouched Tuning fields still match a fresh Tuning().
        val defaults = InkEngine.Tuning()
        assertEquals(defaults.repeatInkAlpha, snap.repeatInkAlpha, 0.0001f)
        assertEquals(defaults.washFeather, snap.washFeather, 0.0001f)
        assertEquals(defaults.tajweedPacing, snap.tajweedPacing)
        assertEquals(defaults.waslHandoff, snap.waslHandoff, 0.0001f)
    }

    @Test
    fun applyLabSnapshot_restoresTuningAndHighlightKnobs() {
        val prev = InkEngine.captureLabSnapshot()
        try {
            val snap = InkLabSnapshot(
                upcomingAlpha = 0.44f,
                inkFadeMs = 640,
                highlightLeadMs = 1_100,
                fadeLeadMs = 250,
                outputLatencyOverrideMs = 90,
            )
            InkEngine.applyLabSnapshot(snap, persist = false)
            assertEquals(0.44f, InkEngine.tuning.upcomingAlpha, 0.0001f)
            assertEquals(640, InkEngine.tuning.inkFadeMs)
            assertEquals(1_100, InkEngine.highlightLeadMs)
            assertEquals(250, InkEngine.fadeLeadMs)
            assertEquals(90, InkEngine.outputLatencyOverrideMs)
        } finally {
            InkEngine.applyLabSnapshot(prev, persist = false)
        }
    }

    @Test
    fun resetLabToShippedDefaults_clearsLiveKnobs() {
        val prev = InkEngine.captureLabSnapshot()
        try {
            InkEngine.tuning = InkEngine.Tuning(upcomingAlpha = 0.5f)
            InkEngine.highlightLeadMs = 800
            InkEngine.fadeLeadMs = 100
            InkEngine.outputLatencyOverrideMs = 50
            InkEngine.resetLabToShippedDefaults()
            assertEquals(InkEngine.Tuning(), InkEngine.tuning)
            assertEquals(InkEngine.DEFAULT_HIGHLIGHT_LEAD_MS, InkEngine.highlightLeadMs)
            assertEquals(InkEngine.DEFAULT_FADE_LEAD_MS, InkEngine.fadeLeadMs)
            assertNull(InkEngine.outputLatencyOverrideMs)
        } finally {
            InkEngine.applyLabSnapshot(prev, persist = false)
        }
    }

    @Test
    fun capture_includesLiveEngineState() {
        val prev = InkEngine.captureLabSnapshot()
        try {
            InkEngine.tuning = InkEngine.Tuning(glintTintAlpha = 0.77f, holdWaqf = false)
            InkEngine.highlightLeadMs = 42
            InkEngine.outputLatencyOverrideMs = null
            val cap = InkEngine.captureLabSnapshot()
            assertEquals(0.77f, cap.glintTintAlpha, 0.0001f)
            assertEquals(false, cap.holdWaqf)
            assertEquals(42, cap.highlightLeadMs)
            assertNull(cap.outputLatencyOverrideMs)
            assertTrue(cap.schema == InkLabSnapshot.SCHEMA)
        } finally {
            InkEngine.applyLabSnapshot(prev, persist = false)
        }
    }
}
