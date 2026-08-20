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
        assertFalse(Settings().educationGuidesEnabled)
    }

    @Test
    fun `reading defaults to word gloss without ayah translation`() {
        val defaults = Settings()
        assertTrue(defaults.showWordGloss)
        assertFalse(defaults.showTranslation)
        assertEquals(ReadingMode.ARABIC_ENGLISH, defaults.readingMode)
        assertEquals(ReadingLayout.SCROLL, defaults.readingLayout)
        assertEquals(VerseNumberScript.ARABIC, defaults.verseNumberScript)
        assertEquals(PageNumberScript.BOTH, defaults.pageNumberScript)
    }

    @Test
    fun `reading layout defaults to the scrolling reader`() {
        // Mushaf pages are something a reader turns on, not something an
        // upgrade turns on for them: the leaf has no word gloss and no
        // translation, and those settings vanish with it.
        assertEquals(ReadingLayout.SCROLL, Settings().readingLayout)
        assertEquals(
            ReadingLayout.MUSHAF,
            Settings().copy(readingLayout = ReadingLayout.MUSHAF).readingLayout,
        )
    }

    @Test
    fun `developer mode toggles via copy`() {
        val on = Settings().copy(developerModeEnabled = true)
        assertTrue(on.developerModeEnabled)
        assertFalse(on.copy(developerModeEnabled = false).developerModeEnabled)
    }

    @Test
    fun `contextual guides are an independent developer toggle`() {
        val enabled = Settings().copy(
            developerModeEnabled = true,
            educationGuidesEnabled = true,
        )

        assertTrue(enabled.educationGuidesEnabled)
        assertFalse(enabled.copy(educationGuidesEnabled = false).educationGuidesEnabled)
    }

    @Test
    fun `contextual lessons keep independent persisted moments`() {
        assertEquals(2, EducationMoment.entries.size)
        assertEquals(
            EducationMoment.entries.size,
            EducationMoment.entries.map { it.preferenceKey }.distinct().size,
        )
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
