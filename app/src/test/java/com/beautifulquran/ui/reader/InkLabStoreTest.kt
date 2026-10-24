package com.beautifulquran.ui.reader

import com.beautifulquran.playback.Tarji
import com.beautifulquran.ui.theme.ContextualGuideTuning
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
    fun shippedDefaults_matchSelectedInkAndVellumProfile() {
        val ink = InkEngine.Tuning()
        assertEquals(1.1092f, ink.pacedFeather, 0.0001f)
        assertEquals(120, ink.waslPrefixMs)
        assertEquals(1.4185f, ink.cruiseCap, 0.0001f)
        assertEquals(0.3f, ink.holdCreep, 0.0001f)

        val guide = ContextualGuideTuning()
        assertEquals(0.5f, guide.bodyEdge, 0.0001f)
        assertEquals(0.2819f, guide.featherWidth, 0.0001f)
        assertEquals(1.3329f, guide.fadeSoftness, 0.0001f)
        assertEquals(24f, guide.blurRadiusDp, 0.0001f)
        assertEquals(1f, guide.blurStrength, 0.0001f)
        assertEquals(0.0297f, guide.vellumGrain, 0.0001f)
        assertEquals(0.24f, guide.verticalTaper, 0.0001f)

        assertEquals(ink, InkLabSnapshot().toTuning())
        assertEquals(guide, InkLabSnapshot().toContextualGuideTuning())
        assertEquals(0, InkEngine.DEFAULT_HIGHLIGHT_LEAD_MS)
        assertEquals(500, InkEngine.DEFAULT_FADE_LEAD_MS)
        // The camera's lead is its own number, not a copy of the ink's.
        assertEquals(100, InkEngine.DEFAULT_SCROLL_LEAD_MS)
        assertNull(InkLabSnapshot().outputLatencyOverrideMs)
    }

    @Test
    fun oldSaves_adoptScrollLeadWithoutResettingOtherTuning() {
        for ((schema, lead) in listOf(2 to 500, 3 to 0)) {
            val saved = InkLabSnapshot(schema = schema, scrollLeadMs = lead,
                glintGlowRadius = 8f, paperInkPooling = .4f, fadeLeadMs = 650)
            val migrated = InkLabSnapshot.decode(InkLabSnapshot.encode(saved))!!
            assertEquals(saved.copy(schema = InkLabSnapshot.SCHEMA, scrollLeadMs = 100), migrated)
            // The migration runs once; subsequent scroll-lead auditions still persist.
            val retuned = migrated.copy(scrollLeadMs = 444)
            assertEquals(retuned, InkLabSnapshot.decode(InkLabSnapshot.encode(retuned)))
        }
    }

    @Test
    fun encodeThenDecode_roundTripsAllFields() {
        val original = InkLabSnapshot(
            upcomingAlpha = 0.31f,
            inkFadeMs = 512,
            glintGlowRadius = 4.25f,
            glintBrightness = 1.3f,
            tajweedPacing = true,
            cruiseCap = 1.55f,
            holdGhunnah = true,
            highlightLeadMs = 900,
            fadeLeadMs = 333,
            scrollLeadMs = 444,
            outputLatencyOverrideMs = 180,
        )
        val restored = InkLabSnapshot.decode(InkLabSnapshot.encode(original))
        assertEquals(original, restored)
    }

    @Test
    fun oldSaves_adoptSlowerRepeatReleaseWithoutResettingTheCameraOrInk() {
        val saved = InkLabSnapshot(schema = 4, repeatFadeOutMs = 900,
            scrollLeadMs = 444, paperInkPooling = .4f, glintGlowRadius = 8f, fadeLeadMs = 650)
        val migrated = InkLabSnapshot.decode(InkLabSnapshot.encode(saved))!!
        assertEquals(saved.copy(schema = InkLabSnapshot.SCHEMA, repeatFadeOutMs = 1_800), migrated)
        // Only old profiles migrate; later auditions still survive a restart.
        val retuned = migrated.copy(repeatFadeOutMs = 2_400)
        assertEquals(retuned, InkLabSnapshot.decode(InkLabSnapshot.encode(retuned)))
    }

    @Test
    fun oldSaves_matchChromeFadesWithoutResettingCustomTuning() {
        val saved = InkLabSnapshot(schema = 5, inkFadeMs = 400,
            ayahMarkFadeMs = 400, recessMs = 400, repeatFadeOutMs = 2_400,
            scrollLeadMs = 444, paperInkPooling = .4f)
        val migrated = InkLabSnapshot.decode(InkLabSnapshot.encode(saved))!!
        assertEquals(saved.copy(schema = InkLabSnapshot.SCHEMA,
            inkFadeMs = InkEngine.CHROME_RECEDE_MS,
            ayahMarkFadeMs = InkEngine.CHROME_RECEDE_MS,
            recessMs = InkEngine.CHROME_RECEDE_MS), migrated)

        val custom = saved.copy(inkFadeMs = 640, ayahMarkFadeMs = 700, recessMs = 800)
        assertEquals(custom.copy(schema = InkLabSnapshot.SCHEMA),
            InkLabSnapshot.decode(InkLabSnapshot.encode(custom)))
        // A new audition at the old duration must still survive a restart.
        val retuned = migrated.copy(inkFadeMs = 400, ayahMarkFadeMs = 400, recessMs = 400)
        assertEquals(retuned, InkLabSnapshot.decode(InkLabSnapshot.encode(retuned)))
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
        // A save written before the camera had its own lead picks the default.
        assertEquals(InkEngine.DEFAULT_SCROLL_LEAD_MS, snap.scrollLeadMs)
        assertNull(snap.outputLatencyOverrideMs)
        // Untouched Tuning fields still match a fresh Tuning().
        val defaults = InkEngine.Tuning()
        assertEquals(defaults.repeatInkAlpha, snap.repeatInkAlpha, 0.0001f)
        assertEquals(1f, snap.glintBrightness, 0f)
        assertEquals(defaults.washFeather, snap.washFeather, 0.0001f)
        assertEquals(defaults.tajweedPacing, snap.tajweedPacing)
        assertEquals(defaults.waslHandoff, snap.waslHandoff, 0.0001f)
        assertEquals(ContextualGuideTuning(), snap.toContextualGuideTuning())
    }

    @Test
    fun aGlowSavedBeforeItsNumbersChangedMeaning_returnsToShipped() {
        // Schema 1 measured the word-by-word blur in px and scaled alpha, not
        // light: carried over, an old save is a different and far wider glow.
        val old = """{"schema":1,"inkFadeMs":700,"glintGlowRadius":10.0,"glintGlowAlpha":0.78,
            "glintBloomAlpha":0.5,"glintVeilAlpha":0.16,"tarjiLightRise":0.1,"tarjiLightFall":0.06,
            "tarjiGlowGain":2.0,"glintVeilWarmth":0.8}"""
        val snap = InkLabSnapshot.decode(old)!!
        val shipped = InkEngine.Tuning()
        assertEquals(InkLabSnapshot.SCHEMA, snap.schema)
        assertEquals(shipped.glintGlowRadius, snap.glintGlowRadius, 0f)
        assertEquals(shipped.glintGlowAlpha, snap.glintGlowAlpha, 0f)
        assertEquals(shipped.glintBloomAlpha, snap.glintBloomAlpha, 0f)
        assertEquals(shipped.glintVeilAlpha, snap.glintVeilAlpha, 0f)
        assertEquals(shipped.tarjiLightRise, snap.tarjiLightRise, 0f)
        assertEquals(shipped.tarjiLightFall, snap.tarjiLightFall, 0f)
        assertEquals(shipped.tarjiGlowGain, snap.tarjiGlowGain, 0f)
        // Everything else in the save is the user's and stays.
        assertEquals(700, snap.inkFadeMs)
        assertEquals(0.8f, snap.glintVeilWarmth, 0f)
        // A save made since is taken as it is.
        val kept = InkLabSnapshot.decode(InkLabSnapshot.encode(snap.copy(glintGlowRadius = 8f)))!!
        assertEquals(8f, kept.glintGlowRadius, 0f)
    }

    @Test
    fun staleTarjiCeiling_isClampedWhenRestored() {
        val tuning = InkLabSnapshot(glintResonanceMaxHz = 25f).toTuning()

        assertEquals(
            Tarji.MAX_MEASURABLE_TREMOLO_HZ,
            tuning.glintResonanceMaxHz,
            0f,
        )
    }

    @Test
    fun applyLabSnapshot_restoresTuningAndHighlightKnobs() {
        val prev = InkEngine.captureLabSnapshot()
        try {
            val snap = InkLabSnapshot(
                upcomingAlpha = 0.44f,
                inkFadeMs = 640,
                guideBlurRadiusDp = 14f,
                guideFeatherWidth = 0.41f,
                highlightLeadMs = 1_100,
                fadeLeadMs = 250,
                outputLatencyOverrideMs = 90,
            )
            InkEngine.applyLabSnapshot(snap, persist = false)
            assertEquals(0.44f, InkEngine.tuning.upcomingAlpha, 0.0001f)
            assertEquals(640, InkEngine.tuning.inkFadeMs)
            assertEquals(14f, InkEngine.contextualGuideTuning.blurRadiusDp, 0.0001f)
            assertEquals(0.41f, InkEngine.contextualGuideTuning.featherWidth, 0.0001f)
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
            InkEngine.contextualGuideTuning = ContextualGuideTuning(blurStrength = 0.2f)
            InkEngine.highlightLeadMs = 800
            InkEngine.fadeLeadMs = 100
            InkEngine.outputLatencyOverrideMs = 50
            InkEngine.resetLabToShippedDefaults()
            assertEquals(InkEngine.Tuning(), InkEngine.tuning)
            assertEquals(ContextualGuideTuning(), InkEngine.contextualGuideTuning)
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
            InkEngine.contextualGuideTuning = ContextualGuideTuning(verticalTaper = 0.06f)
            InkEngine.highlightLeadMs = 42
            InkEngine.outputLatencyOverrideMs = null
            val cap = InkEngine.captureLabSnapshot()
            assertEquals(0.77f, cap.glintTintAlpha, 0.0001f)
            assertEquals(false, cap.holdWaqf)
            assertEquals(0.06f, cap.guideVerticalTaper, 0.0001f)
            assertEquals(42, cap.highlightLeadMs)
            assertNull(cap.outputLatencyOverrideMs)
            assertTrue(cap.schema == InkLabSnapshot.SCHEMA)
        } finally {
            InkEngine.applyLabSnapshot(prev, persist = false)
        }
    }
}
