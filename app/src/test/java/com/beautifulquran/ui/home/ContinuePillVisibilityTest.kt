package com.beautifulquran.ui.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuePillVisibilityTest {

    @Test
    fun visibleWhenRowScrolledOutOfView() {
        assertTrue(
            shouldShowContinuePill(
                continuePresent = true,
                searchActive = false,
                coverVisible = true,
                continueRowVisible = false,
            ),
        )
    }

    @Test
    fun heldBackUntilTheListHasLaidOut() {
        // First composition: no measured rows yet, so the rule cannot tell
        // that the reading-place row is already in view. Hold the pill.
        assertFalse(
            shouldShowContinuePill(
                continuePresent = true,
                searchActive = false,
                coverVisible = true,
                continueRowVisible = false,
                listLaidOut = false,
            ),
        )
        // Once the list has measured, geometry decides as usual.
        assertTrue(
            shouldShowContinuePill(
                continuePresent = true,
                searchActive = false,
                coverVisible = true,
                continueRowVisible = false,
                listLaidOut = true,
            ),
        )
    }

    @Test
    fun hiddenWhenChapterRowInView() {
        assertFalse(
            shouldShowContinuePill(
                continuePresent = true,
                searchActive = false,
                coverVisible = true,
                continueRowVisible = true,
            ),
        )
    }

    @Test
    fun hiddenWithoutReadingPlace() {
        assertFalse(
            shouldShowContinuePill(
                continuePresent = false,
                searchActive = false,
                coverVisible = true,
                continueRowVisible = false,
            ),
        )
    }

    @Test
    fun hiddenWhileSearchIsActive() {
        assertFalse(
            shouldShowContinuePill(
                continuePresent = true,
                searchActive = true,
                coverVisible = true,
                continueRowVisible = false,
            ),
        )
    }

    @Test
    fun hiddenWhenLeavingChapterSelection() {
        assertFalse(
            shouldShowContinuePill(
                continuePresent = true,
                searchActive = false,
                coverVisible = false,
                continueRowVisible = false,
            ),
        )
    }

    @Test
    fun continueCarriesItsOwnGestureInset() {
        // Measured while the transport is off, so the pill height already
        // includes the navigation-bars pad — no second gesture inset.
        assertEquals(
            ContinuePillListClearance + ContinuePillGap,
            homeListBottomInset(
                pinnedSession = false,
                pinnedHeight = 0.dp,
                floatingVisible = false,
                floatingHeight = 104.dp,
                navigationBottom = 20.dp,
                continueVisible = true,
                continueHeight = ContinuePillListClearance,
            ),
        )
    }

    @Test
    fun continueFallbackKeepsGestureInsetBeforeFirstMeasure() {
        assertEquals(
            ContinuePillListClearance + 20.dp + ContinuePillGap,
            homeListBottomInset(
                pinnedSession = false,
                pinnedHeight = 0.dp,
                floatingVisible = false,
                floatingHeight = 104.dp,
                navigationBottom = 20.dp,
                continueVisible = true,
                continueHeight = 0.dp,
            ),
        )
    }

    @Test
    fun dockHoldsOpaqueThenFadesIntoRow() {
        assertEquals(1f, continueDockAlpha(0f), 0.001f)
        assertEquals(1f, continueDockAlpha(ContinueDockFadeFrom), 0.001f)
        assertEquals(0.3f, continueDockAlpha(0.85f), 0.001f)
        assertEquals(0f, continueDockAlpha(1f), 0.001f)
    }
    @Test
    fun dockCollapsesPillRadiusToRow() {
        assertEquals(ContinuePillRadius, continueDockRadius(0f))
        assertEquals(0.dp, continueDockRadius(1f))
    }

    @Test
    fun dockSettlesOntoRowGeometry() {
        assertEquals(16.dp, continueDockMargin(0f))
        assertEquals(0.dp, continueDockMargin(1f))
        assertEquals(8.dp, continueDockStartInset(0f))
        assertEquals(0.dp, continueDockStartInset(1f))
        assertEquals(16.dp, continueDockEndInset(0f))
        assertEquals(28.dp + 4.dp, continueDockEndInset(1f))
        assertEquals(1f, continueDockCapsAlpha(0f), 0.001f)
        assertEquals(0f, continueDockCapsAlpha(1f), 0.001f)
    }

    @Test
    fun dockWaitsForASettledRow() {
        assertEquals(500L, ContinueDockDwellMs)
    }

    @Test
    fun hiddenWhileTransportOwnsThePaper() {
        assertFalse(
            shouldShowContinuePill(
                continuePresent = true,
                searchActive = false,
                coverVisible = true,
                continueRowVisible = false,
                transportVisible = true,
            ),
        )
    }

    @Test
    fun continueRowIndexIsItsSurahPosition() {
        val ids = (1..114).toList()
        assertEquals(0, continueLazyIndex(ids, 1, false))
        assertEquals(17, continueLazyIndex(ids, 18, false))
        assertEquals(113, continueLazyIndex(ids, 114, false))
    }

    @Test
    fun pinnedChapterOwnsThePlaceAtopTheList() {
        val ids = (1..114).toList()
        val pinned = listOf(18, 67)
        // Label, then the pinned rows: 67's copy is the third item.
        assertEquals(2, continueLazyIndex(ids, 67, false, pinned))
        assertEquals("pinned-67", placeRowKey(67, pinned))
        // Unpinned chapters sit below the label, both rows and the gap.
        assertEquals(4, continueLazyIndex(ids, 1, false, pinned))
        assertEquals("surah-1", placeRowKey(1, pinned))
        assertEquals(null, placeRowKey(null, pinned))
    }

    @Test
    fun pinnedWashesJoinIntoOneRoundedBlock() {
        // A lone pin is a whole pill.
        assertEquals(true to true, pinnedWashCorners(0, 1))
        // Three pins: rounded top, square middle, rounded bottom.
        assertEquals(true to false, pinnedWashCorners(0, 3))
        assertEquals(false to false, pinnedWashCorners(1, 3))
        assertEquals(false to true, pinnedWashCorners(2, 3))
    }

    @Test
    fun continueRowOffSheetWhileSearching() {
        val ids = (1..114).toList()
        assertEquals(-1, continueLazyIndex(ids, 18, true))
        assertEquals(-1, continueLazyIndex(ids, null, false))
        assertEquals(-1, continueLazyIndex(ids, 200, false))
    }

    @Test
    fun pinnedSessionNeverStacksContinue() {        assertEquals(
            128.dp,
            homeListBottomInset(
                pinnedSession = true,
                pinnedHeight = 128.dp,
                floatingVisible = false,
                floatingHeight = 104.dp,
                navigationBottom = 20.dp,
                continueVisible = true,
                continueHeight = 64.dp,
            ),
        )
    }
}
