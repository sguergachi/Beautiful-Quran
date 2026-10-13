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
    fun pinnedSessionNeverStacksContinue() {
        assertEquals(
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
