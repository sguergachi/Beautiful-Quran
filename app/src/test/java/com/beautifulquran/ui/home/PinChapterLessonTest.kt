package com.beautifulquran.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinChapterLessonTest {
    private fun eligibleVisit(chapter: Int = 80, duration: Long = 60_001L): PinChapterLesson =
        PinChapterLesson().apply {
            observe(chapter, reading = true, onChapters = false, nowMs = 1_000L)
            observe(chapter, reading = false, onChapters = true, nowMs = 1_000L + duration)
        }

    @Test
    fun chapters80Through114QualifyButEarlierChaptersDoNot() {
        for (chapter in 1..114) {
            assertEquals(chapter.takeIf { it >= 80 }, eligibleVisit(chapter).offeredChapter)
        }
        assertNull(eligibleVisit(115).offeredChapter)
    }

    @Test
    fun exactlyOneMinuteIsNotMoreThanOneMinute() {
        assertNull(eligibleVisit(duration = 59_999L).offeredChapter)
        assertNull(eligibleVisit(duration = 60_000L).offeredChapter)
        assertEquals(80, eligibleVisit(duration = 60_001L).offeredChapter)
    }

    @Test
    fun anyRouteIntoALateChapterStartsAVisitButEarlyChaptersNever() {
        val early = PinChapterLesson()
        early.observe(79, true, false, 0)
        early.observe(79, false, true, 600_000)
        assertNull(early.offeredChapter)
        // Continue, search, bookmarks: the reader simply reads chapter 114.
        val late = PinChapterLesson()
        late.observe(114, true, false, 700_000)
        late.observe(114, false, true, 1_000_000)
        assertEquals(114, late.offeredChapter)
    }

    @Test
    fun openingWhileChaptersIsStillVisibleDoesNotCompleteTheVisit() {
        val lesson = PinChapterLesson()
        lesson.observe(80, false, true, 0)
        lesson.observe(80, true, false, 10_000)
        lesson.observe(80, false, true, 190_001)
        assertEquals(80, lesson.offeredChapter)
    }

    @Test
    fun loadingAndTimeBeforeTheReaderAppearsDoNotCount() {
        val lesson = PinChapterLesson()
        lesson.observe(80, false, false, 0)
        lesson.observe(80, true, false, 600_000)
        lesson.observe(80, false, true, 660_000)
        assertNull(lesson.offeredChapter)
    }

    @Test
    fun backgroundAndCoveredTimeAreExcludedButReadingCanResume() {
        val lesson = PinChapterLesson()
        lesson.observe(80, true, false, 0)
        lesson.pauseReading(40_000)
        lesson.observe(80, false, false, 500_000)
        lesson.observe(80, true, false, 900_000)
        lesson.observe(80, false, true, 920_001)
        assertEquals(80, lesson.offeredChapter)
    }

    @Test
    fun timeSpentInTheBackgroundCannotCompleteAShortRead() {
        val lesson = PinChapterLesson()
        lesson.observe(80, true, false, 0)
        lesson.pauseReading(30_000)
        lesson.observe(80, false, true, 600_000)
        assertNull(lesson.offeredChapter)
    }

    @Test
    fun lessonWaitsForTheReturnToChaptersToSettle() {
        val lesson = PinChapterLesson()
        lesson.observe(80, true, false, 0)
        lesson.observe(80, false, false, 60_001)
        assertNull(lesson.offeredChapter)
        lesson.observe(80, false, true, 61_000)
        assertEquals(80, lesson.offeredChapter)
    }

    @Test
    fun continuingToTheNextChapterKeepsTimeAndOffersTheLastChapterRead() {
        val lesson = PinChapterLesson()
        lesson.observe(80, true, false, 0)
        lesson.observe(81, true, false, 40_000)
        lesson.observe(81, false, true, 60_001)
        assertEquals(81, lesson.offeredChapter)
    }

    @Test
    fun timeCarriesAcrossForwardJumpsAndBackwardMovesWithin80Through114() {
        val lesson = PinChapterLesson()
        lesson.observe(80, true, false, 0)
        lesson.observe(114, true, false, 20_000)
        lesson.observe(90, true, false, 40_000)
        lesson.observe(80, true, false, 60_000)
        lesson.observe(80, false, true, 60_001)
        assertEquals(80, lesson.offeredChapter)
    }

    @Test
    fun aggregateTimeMustStillExceedOneMinute() {
        for (duration in listOf(60_000L, 60_001L)) {
            val lesson = PinChapterLesson()
            lesson.observe(80, true, false, 0)
            lesson.observe(81, true, false, 40_000)
            lesson.observe(81, false, true, duration)
            assertEquals(81.takeIf { duration > 60_000L }, lesson.offeredChapter)
        }
    }

    @Test
    fun loadingTheNextChapterAndBackgroundTimeAreNotCounted() {
        val lesson = PinChapterLesson()
        lesson.observe(80, true, false, 0)
        lesson.observe(81, false, false, 20_000)
        lesson.observe(81, true, false, 600_000)
        lesson.pauseReading(620_000)
        lesson.observe(81, true, false, 900_000)
        lesson.observe(81, false, true, 910_000)
        assertNull(lesson.offeredChapter)
    }

    @Test
    fun enteringAnyChapterBelow80ResetsAccumulatedTime() {
        for (earlierChapter in 1..79) {
            val lesson = PinChapterLesson()
            lesson.observe(80, true, false, 0)
            lesson.observe(earlierChapter, true, false, 70_000)
            lesson.observe(81, true, false, 400_000)
            lesson.observe(81, false, true, 450_000)
            assertNull("Chapter $earlierChapter must reset the clock", lesson.offeredChapter)
        }
    }

    @Test
    fun returningTo80AfterTheResetCanStartAFreshMinute() {
        val lesson = PinChapterLesson()
        lesson.observe(80, true, false, 0)
        lesson.observe(79, true, false, 70_000)
        lesson.observe(80, true, false, 600_000)
        lesson.observe(81, true, false, 620_000)
        lesson.observe(81, false, true, 660_001)
        assertEquals(81, lesson.offeredChapter)
    }

    @Test
    fun returningToChaptersFromBelow80DoesNotOfferTheGuide() {
        val lesson = PinChapterLesson()
        lesson.observe(80, true, false, 0)
        lesson.observe(79, true, false, 70_000)
        lesson.observe(79, false, true, 600_000)
        assertNull(lesson.offeredChapter)
    }

    @Test
    fun separateShortVisitsAreNotAddedTogether() {
        val lesson = eligibleVisit(duration = 40_000)
        lesson.observe(80, true, false, 200_000)
        lesson.observe(80, false, true, 240_000)
        assertNull(lesson.offeredChapter)
    }

    @Test
    fun continuingIntoAnotherLateChapterKeepsTheVisitAndObserverDisposalIsHarmless() {
        val lesson = PinChapterLesson()
        lesson.observe(80, true, false, 0)
        lesson.pauseReading(200_000)
        lesson.observe(114, true, false, 200_000)
        lesson.observe(114, false, true, 260_001)
        assertEquals(114, lesson.offeredChapter)
    }

    @Test
    fun anotherReaderVisitExpiresAnUnshownOffer() {
        val lesson = eligibleVisit()
        assertEquals(80, lesson.offeredChapter)
        // The next reader visit replaces the offer; too short to earn its own.
        lesson.observe(80, true, false, 300_000)
        lesson.observe(80, false, true, 330_000)
        assertNull(lesson.offeredChapter)
    }

    @Test
    fun dismissalClearsTheOffer() {
        val lesson = eligibleVisit()
        lesson.dismiss()
        lesson.observe(80, false, true, 300_000)
        assertNull(lesson.offeredChapter)
    }
}
