package com.beautifulquran.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents the Settings field the Root Viewer / Timings Lab gate depends on.
 * Persistence itself needs Android SharedPreferences; this keeps the default
 * and copy semantics covered on the JVM.
 */
class DeveloperModeSettingsTest {

    @Test
    fun `developer mode defaults off`() {
        assertFalse(Settings().developerModeEnabled)
    }

    @Test
    fun `reading defaults to word gloss without ayah translation`() {
        val defaults = Settings()
        assertTrue(defaults.showWordGloss)
        assertFalse(defaults.showTranslation)
    }

    @Test
    fun `developer mode toggles via copy`() {
        val on = Settings().copy(developerModeEnabled = true)
        assertTrue(on.developerModeEnabled)
        assertFalse(on.copy(developerModeEnabled = false).developerModeEnabled)
    }

    @Test
    fun `English parentheticals stay visible unless the developer setting enables hiding`() {
        assertFalse(Settings().hideEnglishParentheticals)
        assertTrue(Settings().copy(hideEnglishParentheticals = true).hideEnglishParentheticals)
    }

    @Test
    fun `home bookmark style defaults to top bound and survives developer mode`() {
        val alternative = Settings().copy(
            homeBookmarkStyle = HomeBookmarkStyle.SAVED_PASSAGES,
            developerModeEnabled = true,
        )

        assertEquals(HomeBookmarkStyle.TOP_BOUND, Settings().homeBookmarkStyle)
        assertEquals(
            HomeBookmarkStyle.SAVED_PASSAGES,
            alternative.copy(developerModeEnabled = false).homeBookmarkStyle,
        )
    }

    @Test
    fun `brush circle style defaults to baseline and has ten variants beyond it`() {
        assertEquals(BrushCircleStyle.BASELINE, Settings().brushCircleStyle)
        // Baseline + 10 developer variants for A/B feel.
        assertEquals(11, BrushCircleStyle.entries.size)
        val dry = Settings().copy(brushCircleStyle = BrushCircleStyle.DRY_BRUSH)
        assertEquals(BrushCircleStyle.DRY_BRUSH, dry.brushCircleStyle)
    }
}
