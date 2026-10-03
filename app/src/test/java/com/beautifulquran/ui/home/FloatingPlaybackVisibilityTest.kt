package com.beautifulquran.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp

class FloatingPlaybackVisibilityTest {

    @Test
    fun visibleWhenVerseLoadedAndCoverInView() {
        assertTrue(
            shouldShowFloatingPlayback(
                nowPlayingPresent = true,
                coverSheetVisible = true,
            ),
        )
    }

    @Test
    fun hiddenWhenSessionHasNoVerse() {
        assertFalse(
            shouldShowFloatingPlayback(
                nowPlayingPresent = false,
                coverSheetVisible = true,
            ),
        )
    }

    @Test
    fun hiddenWhenLeavingChapterSelection() {
        assertFalse(
            shouldShowFloatingPlayback(
                nowPlayingPresent = true,
                coverSheetVisible = false,
            ),
        )
    }

    @Test
    fun hiddenWhileSearchIsActive() {
        assertFalse(
            shouldShowFloatingPlayback(
                nowPlayingPresent = true,
                coverSheetVisible = true,
                searchActive = true,
            ),
        )
    }

    @Test
    fun coverVisibleThresholdKeepsFloatOnNearCover() {
        assertTrue(0f <= FloatingPlaybackCoverVisibleMaxPage)
        assertTrue(FloatingPlaybackCoverVisibleMaxPage < 1f)
    }

    @Test
    fun pinnedSessionKeepsItsMeasuredPaper() {
        assertEquals(
            128.dp,
            homeListBottomInset(
                pinnedSession = true,
                pinnedHeight = 128.dp,
                floatingVisible = false,
                floatingHeight = 96.dp,
                navigationBottom = 20.dp,
            ),
        )
    }

    @Test
    fun pinnedSessionFallsBackToClearanceBeforeFirstMeasure() {
        assertEquals(
            FloatingPlaybackListClearance,
            homeListBottomInset(
                pinnedSession = true,
                pinnedHeight = 0.dp,
                floatingVisible = true,
                floatingHeight = 96.dp,
                navigationBottom = 20.dp,
            ),
        )
    }

    @Test
    fun floatingBarClearsItsOwnHeight() {
        assertEquals(
            104.dp,
            homeListBottomInset(
                pinnedSession = false,
                pinnedHeight = 128.dp,
                floatingVisible = true,
                floatingHeight = 104.dp,
                navigationBottom = 20.dp,
            ),
        )
    }

    @Test
    fun noBarLeavesOnlyTheGestureInset() {
        assertEquals(
            20.dp,
            homeListBottomInset(
                pinnedSession = false,
                pinnedHeight = 0.dp,
                floatingVisible = false,
                floatingHeight = 104.dp,
                navigationBottom = 20.dp,
            ),
        )
    }
}
