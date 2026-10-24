package com.beautifulquran.ui.home

import androidx.compose.ui.unit.dp
import com.beautifulquran.data.model.Surah
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
        // Air, flag, then the pinned rows: 67's copy is the fourth item.
        assertEquals(3, continueLazyIndex(ids, 67, false, pinned))
        // One key per chapter, on the band or in the list, so a pin moves it.
        assertEquals("chapter-67", placeRowKey(67))
        // Pinned chapters leave the list below, so unpinned ones sit after
        // air, flag and both rows, at their place among the rest.
        val listed = ids - pinned.toSet()
        assertEquals(4, continueLazyIndex(listed, 1, false, pinned))
        assertEquals(4 + 18, continueLazyIndex(listed, 20, false, pinned))
        assertEquals("chapter-1", placeRowKey(1))
        assertEquals(null, placeRowKey(null))
    }

    @Test
    fun chapterRowsNeverEmitAChapterTwice() {
        fun surah(id: Int) = Surah(id, "arabic", "name$id", "meaning", "meccan", 7)
        val ids = listOf(1, 2, 3)
        val pinned = listOf(surah(2))
        // The band leads the list and the list below loses the pinned chapter,
        // so a pin is a reorder inside one interval: one row per chapter.
        val rows = chapterRows(pinned, ids.map(::surah), showPinned = true)
        assertEquals(listOf(2, 1, 3), rows.map { it.id })
        // Even if the two sources disagreed, the band's copy wins and the
        // duplicate is dropped rather than reaching the list as a second key.
        val noisy = chapterRows(pinned, listOf(surah(2), surah(1)), showPinned = true)
        assertEquals(listOf(2, 1), noisy.map { it.id })
        // Search takes the band down and lists every chapter in its own order.
        assertEquals(ids, chapterRows(pinned, ids.map(::surah), showPinned = false).map { it.id })
    }

    @Test
    fun pinLessonTakesTheOpenHalfOfTheSheet() {
        assertEquals(true, pinTipBodyBelow(targetY = 300f, sheetHeight = 2000f))
        assertEquals(false, pinTipBodyBelow(targetY = 1500f, sheetHeight = 2000f))
    }

    @Test
    fun aQuickReleaseOpensTheChapterAndALongerOneChangesNothing() {
        assertEquals(HoldOutcome.Tap, holdOutcome(80L))
        assertEquals(HoldOutcome.Tap, holdOutcome(HoldTapMaxMs - 1))
        assertEquals(HoldOutcome.Cancel, holdOutcome(HoldTapMaxMs))
        assertEquals(HoldOutcome.Cancel, holdOutcome(650L))
    }

    @Test
    fun theHoldFillsOnTheClockNotTheAnimatorScale() {
        // A tap's worth shows nothing; then the row deepens evenly to full.
        assertEquals(0f, holdFillProgress(0L), 0f)
        assertEquals(0f, holdFillProgress(150L), 0f)
        assertEquals(0.5f, holdFillProgress(425L), 0.001f)
        assertEquals(1f, holdFillProgress(700L), 0f)
        assertEquals(1f, holdFillProgress(5_000L), 0f)
    }

    @Test
    fun aLongerSystemTouchAndHoldDelayStretchesTheHold() {
        // The default delay and quicker keep the designed timings.
        assertEquals(1f, holdPace(400L), 0f)
        assertEquals(1f, holdPace(300L), 0f)
        // "Long" (1500 ms) stretches everything alike: a slow tap still opens.
        val pace = holdPace(1500L)
        assertEquals(3.75f, pace, 0.001f)
        assertEquals(HoldOutcome.Tap, holdOutcome(900L, pace))
        assertEquals(0f, holdFillProgress(500L, pace), 0f)
        assertEquals(1f, holdFillProgress(2_625L, pace), 0f)
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
