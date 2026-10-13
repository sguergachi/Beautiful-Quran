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
    fun continueStacksAboveFloatingTransport() {
        assertEquals(
            104.dp + 64.dp + ContinuePillGap,
            homeListBottomInset(
                pinnedSession = false,
                pinnedHeight = 0.dp,
                floatingVisible = true,
                floatingHeight = 104.dp,
                navigationBottom = 20.dp,
                continueVisible = true,
                continueHeight = 64.dp,
            ),
        )
    }

    @Test
    fun dockHoldsOpaqueThenFadesIntoRow() {
        assertEquals(1f, continueDockAlpha(0f), 0.001f)
        assertEquals(1f, continueDockAlpha(ContinueDockFadeFrom), 0.001f)
        assertEquals(0.5f, continueDockAlpha(0.85f), 0.001f)
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
        assertEquals(3000L, ContinueDockDwellMs)
    }

    @Test
    fun undockNeedsACompletedDock() {
        assertFalse(undockFlightReady(0L))
        assertTrue(undockFlightReady(10_000L))
    }

    @Test
    fun continueRowSitsAfterTopBreath() {
        val ids = (1..114).toList()
        assertEquals(1, continueLazyIndex(ids, 1, false))
        assertEquals(1 + 17, continueLazyIndex(ids, 18, false))
        assertEquals(114, continueLazyIndex(ids, 114, false))
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
