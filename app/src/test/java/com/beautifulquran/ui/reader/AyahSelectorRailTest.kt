package com.beautifulquran.ui.reader

import androidx.compose.ui.geometry.Offset
import com.beautifulquran.data.model.Ayah
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AyahSelectorRailTest {
    @Test
    fun symbolicAyahBarCount_clampsShortAndLongSurahs() {
        assertEquals(4, symbolicAyahBarCount(1))
        assertEquals(4, symbolicAyahBarCount(7))
        assertEquals(17, symbolicAyahBarCount(286))
        assertEquals(18, symbolicAyahBarCount(10_000))
    }

    @Test
    fun collapsedAyahBarIndex_mapsCurrentVerseAcrossTheWholeStack() {
        assertEquals(0, collapsedAyahBarIndex(286, 1))
        assertEquals(8, collapsedAyahBarIndex(286, 143))
        assertEquals(16, collapsedAyahBarIndex(286, 286))
        assertEquals(0, collapsedAyahBarIndex(1, 1))
    }

    @Test
    fun collapsedStackSpan_matchesDrawLayout() {
        // count=4 → spacing 8, step 9.5, span = 3*9.5 + 1.5 = 30
        assertEquals(30f, collapsedStackSpanDp(1), 0.01f)
        // count=17 (Al-Baqarah) → spacing max(4, min(8, 72/17))=4.235…, step≈5.735
        val span286 = collapsedStackSpanDp(286)
        assertTrue(span286 > 30f)
        assertTrue(span286 < 120f)
    }

    @Test
    fun collapsedRailHitHeight_isStackPlusPadWithFloor() {
        // Short stack 30 + pad 24 = 54 (> 48 floor)
        assertEquals(54f, collapsedRailHitHeightDp(1), 0.01f)
        // Floor still applies if pad were zero on a tiny stack
        assertEquals(48f, collapsedRailHitHeightDp(1, padDp = 0f), 0.01f)
        // Hit height grows with longer surahs but stays well below a phone screen.
        assertTrue(collapsedRailHitHeightDp(286) < 200f)
        assertTrue(collapsedRailHitHeightDp(286) > collapsedRailHitHeightDp(1))
    }

    @Test
    fun collapsedRailGesture_allowsTapAndVerticalScrubButNotBackSwipe() {
        assertEquals(
            CollapsedRailActivation.TAP,
            collapsedRailActivation(Offset.Zero, released = true, touchSlopPx = 8f),
        )
        assertEquals(
            CollapsedRailActivation.VERTICAL_SCRUB,
            collapsedRailActivation(Offset(0f, 8.1f), released = false, touchSlopPx = 8f),
        )
        assertEquals(
            CollapsedRailActivation.IGNORE,
            collapsedRailActivation(Offset(8.1f, 0f), released = false, touchSlopPx = 8f),
        )
    }

    @Test
    fun pageStartByAyah_marksFirstAyahOfEachPage() {
        val ayahs = listOf(
            ayah(1, page = 2), ayah(2, page = 2), ayah(3, page = 2),
            ayah(4, page = 3), ayah(5, page = 3),
            ayah(6, page = 4),
        )
        assertEquals(mapOf(1 to 2, 4 to 3, 6 to 4), pageStartByAyah(ayahs))
    }

    @Test
    fun pageStartByAyah_skipsMissingPagesWithoutBreakingTheChain() {
        val ayahs = listOf(
            ayah(1, page = 0),
            ayah(2, page = 5), ayah(3, page = 0), ayah(4, page = 5),
            ayah(5, page = 6),
        )
        assertEquals(mapOf(2 to 5, 5 to 6), pageStartByAyah(ayahs))
    }

    private fun ayah(number: Int, page: Int) = Ayah(
        surahId = 1,
        number = number,
        text = "",
        translation = "",
        page = page,
        words = emptyList(),
    )
}
