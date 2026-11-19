package com.beautifulquran.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatedBasmalahTest {
    private fun drops(
        enabled: Boolean = true,
        hasLeadIn: Boolean = true,
        heardInSurah: Int? = 2,
        surahId: Int = 2,
        repeatsWholeChapter: Boolean = true,
        onLastItem: Boolean = true,
    ) = dropsRepeatedBasmalah(enabled, hasLeadIn, heardInSurah, surahId, repeatsWholeChapter, onLastItem)

    @Test
    fun aHeardBasmalahLeavesAsTheRepeatWraps() = assertTrue(drops())

    @Test
    fun switchingTheSettingOffKeepsTheBasmalah() = assertFalse(drops(enabled = false))

    @Test
    fun aBasmalahNotYetHeardPlaysOnce() = assertFalse(drops(heardInSurah = null))

    @Test
    fun anotherChapterHeardItsOwnBasmalahNotThisOnes() = assertFalse(drops(heardInSurah = 3))

    @Test
    fun onlyAWholeChapterRepeatWrapsToTheOpening() = assertFalse(drops(repeatsWholeChapter = false))

    @Test
    fun nothingLeavesBeforeTheLastAyah() = assertFalse(drops(onLastItem = false))

    @Test
    fun aQueueWithoutALeadInHasNothingToDrop() = assertFalse(drops(hasLeadIn = false))
}
