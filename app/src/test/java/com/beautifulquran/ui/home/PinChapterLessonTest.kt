package com.beautifulquran.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinChapterLessonTest {
    private fun eligibleVisit(chapter: Int = 80, duration: Long = 180_001L): PinChapterLesson =
        PinChapterLesson().apply {
            openFromList(chapter, scrolledDown = true)
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
    fun exactlyThreeMinutesIsNotMoreThanThreeMinutes() {
        assertNull(eligibleVisit(duration = 179_999L).offeredChapter)
        assertNull(eligibleVisit(duration = 180_000L).offeredChapter)
        assertEquals(80, eligibleVisit(duration = 180_001L).offeredChapter)
    }

    @Test
    fun searchAutomaticScrollAndOtherEntryPointsCannotQualify() {
        val lesson = PinChapterLesson()
        lesson.openFromList(80, scrolledDown = false)
        lesson.observe(80, true, false, 0)
        lesson.observe(80, false, true, 600_000)
        assertNull(lesson.offeredChapter)
        // A reader opened without a list-row event has no qualifying visit.
        lesson.observe(114, true, false, 700_000)
        lesson.observe(114, false, true, 1_000_000)
        assertNull(lesson.offeredChapter)
    }

    @Test
    fun openingWhileChaptersIsStillVisibleDoesNotCompleteTheVisit() {
        val lesson = PinChapterLesson()
        lesson.openFromList(80, true)
        lesson.observe(80, false, true, 0)
        lesson.observe(80, true, false, 10_000)
        lesson.observe(80, false, true, 190_001)
        assertEquals(80, lesson.offeredChapter)
    }

    @Test
    fun loadingAndTimeBeforeTheReaderAppearsDoNotCount() {
        val lesson = PinChapterLesson()
        lesson.openFromList(80, true)
        lesson.observe(80, false, false, 0)
        lesson.observe(80, true, false, 600_000)
        lesson.observe(80, false, true, 660_000)
        assertNull(lesson.offeredChapter)
    }

    @Test
    fun backgroundAndCoveredTimeAreExcludedButReadingCanResume() {
        val lesson = PinChapterLesson()
        lesson.openFromList(80, true)
        lesson.observe(80, true, false, 0)
        lesson.pauseReading(100_000)
        lesson.observe(80, false, false, 500_000)
        lesson.observe(80, true, false, 900_000)
        lesson.observe(80, false, true, 980_001)
        assertEquals(80, lesson.offeredChapter)
    }

    @Test
    fun threeMinutesSpentInTheBackgroundCannotCompleteAShortRead() {
        val lesson = PinChapterLesson()
        lesson.openFromList(80, true)
        lesson.observe(80, true, false, 0)
        lesson.pauseReading(30_000)
        lesson.observe(80, false, true, 600_000)
        assertNull(lesson.offeredChapter)
    }

    @Test
    fun lessonWaitsForTheReturnToChaptersToSettle() {
        val lesson = PinChapterLesson()
        lesson.openFromList(80, true)
        lesson.observe(80, true, false, 0)
        lesson.observe(80, false, false, 180_001)
        assertNull(lesson.offeredChapter)
        lesson.observe(80, false, true, 181_000)
        assertEquals(80, lesson.offeredChapter)
    }

    @Test
    fun continuingToTheNextChapterKeepsTimeAndOffersTheLastChapterRead() {
        val lesson = PinChapterLesson()
        lesson.openFromList(80, true)
        lesson.observe(80, true, false, 0)
        lesson.observe(81, true, false, 120_000)
        lesson.observe(81, false, true, 180_001)
        assertEquals(81, lesson.offeredChapter)
    }

    @Test
    fun timeCarriesAcrossForwardJumpsAndBackwardMovesWithin80Through114() {
        val lesson = PinChapterLesson()
        lesson.openFromList(80, true)
        lesson.observe(80, true, false, 0)
        lesson.observe(114, true, false, 60_000)
        lesson.observe(90, true, false, 120_000)
        lesson.observe(80, true, false, 180_000)
        lesson.observe(80, false, true, 180_001)
        assertEquals(80, lesson.offeredChapter)
    }

    @Test
    fun aggregateTimeMustStillExceedThreeMinutes() {
        for (duration in listOf(180_000L, 180_001L)) {
            val lesson = PinChapterLesson()
            lesson.openFromList(80, true)
            lesson.observe(80, true, false, 0)
            lesson.observe(81, true, false, 120_000)
            lesson.observe(81, false, true, duration)
            assertEquals(81.takeIf { duration > 180_000L }, lesson.offeredChapter)
        }
    }

    @Test
    fun loadingTheNextChapterAndBackgroundTimeAreNotCounted() {
        val lesson = PinChapterLesson()
        lesson.openFromList(80, true)
        lesson.observe(80, true, false, 0)
        lesson.observe(81, false, false, 60_000)
        lesson.observe(81, true, false, 600_000)
        lesson.pauseReading(660_000)
        lesson.observe(81, true, false, 900_000)
        lesson.observe(81, false, true, 960_000)
        assertNull(lesson.offeredChapter)
    }

    @Test
    fun enteringAnyChapterBelow80ResetsAccumulatedTime() {
        for (earlierChapter in 1..79) {
            val lesson = PinChapterLesson()
            lesson.openFromList(80, true)
            lesson.observe(80, true, false, 0)
            lesson.observe(earlierChapter, true, false, 190_000)
            lesson.observe(81, true, false, 400_000)
            lesson.observe(81, false, true, 460_000)
            assertNull("Chapter $earlierChapter must reset the clock", lesson.offeredChapter)
        }
    }

    @Test
    fun returningTo80AfterTheResetCanStartAFreshThreeMinutes() {
        val lesson = PinChapterLesson()
        lesson.openFromList(80, true)
        lesson.observe(80, true, false, 0)
        lesson.observe(79, true, false, 190_000)
        lesson.observe(80, true, false, 600_000)
        lesson.observe(81, true, false, 720_000)
        lesson.observe(81, false, true, 780_001)
        assertEquals(81, lesson.offeredChapter)
    }

    @Test
    fun returningToChaptersFromBelow80DoesNotOfferTheGuide() {
        val lesson = PinChapterLesson()
        lesson.openFromList(80, true)
        lesson.observe(80, true, false, 0)
        lesson.observe(79, true, false, 190_000)
        lesson.observe(79, false, true, 600_000)
        assertNull(lesson.offeredChapter)
    }

    @Test
    fun separateShortVisitsAreNotAddedTogether() {
        val lesson = eligibleVisit(duration = 120_000)
        lesson.openFromList(80, true)
        lesson.observe(80, true, false, 200_000)
        lesson.observe(80, false, true, 320_000)
        assertNull(lesson.offeredChapter)
    }

    @Test
    fun aNewOpeningReplacesTheOldVisitAndOldObserverDisposalIsHarmless() {
        val lesson = PinChapterLesson()
        lesson.openFromList(80, true)
        lesson.observe(80, true, false, 0)
        lesson.openFromList(114, true)
        lesson.pauseReading(200_000)
        lesson.observe(114, true, false, 200_000)
        lesson.observe(114, false, true, 380_001)
        assertEquals(114, lesson.offeredChapter)
    }

    @Test
    fun anotherReaderVisitExpiresAnUnshownOffer() {
        val lesson = eligibleVisit()
        lesson.observe(80, true, false, 300_000)
        lesson.observe(80, false, true, 600_000)
        assertNull(lesson.offeredChapter)
    }

    @Test
    fun dismissalAndNewListOpensClearTheOffer() {
        val lesson = eligibleVisit()
        lesson.dismiss()
        lesson.observe(80, false, true, 300_000)
        assertNull(lesson.offeredChapter)
        val another = eligibleVisit()
        another.openFromList(79, true)
        assertNull(another.offeredChapter)
    }
}
