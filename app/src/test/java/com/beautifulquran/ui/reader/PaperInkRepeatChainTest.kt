package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperInkRepeatChainTest {
    @Test fun earlierWordsKeepTheirOrangeAfterTheirWetFilmDries() {
        val first = PaperInkClock(0, 500, 1000)
        val next = PaperInkClock(500, 4000, 1000)
        val chain = PaperInkRepeatChain(900)
        for (time in 0L..2500L step 100) {
            first.update(time, true, time)
            next.update(time, true, time)
            chain.update(true, time)
        }
        assertEquals(1f, first.progress)
        assertEquals(0f, first.wet)
        assertTrue(next.progress in 0f..1f)
        assertEquals(1f, next.wet)
        assertEquals(1f, chain.alpha)
    }

    @Test fun pausingWithinARepeatKeepsItsColorWhileTheFilmDries() {
        val word = PaperInkClock(0, 1000, 1000)
        val chain = PaperInkRepeatChain(900)
        word.update(500, true, 0)
        chain.update(true, 0)
        word.update(500, false, 10)
        word.update(500, false, 1010)
        chain.update(true, 1010)
        assertEquals(.5f, word.progress)
        assertEquals(0f, word.wet)
        assertEquals(1f, chain.alpha)
    }

    @Test fun theChainReleasesOnlyAfterMovingToTheNextVerse() {
        val chain = PaperInkRepeatChain(900)
        chain.update(false, 0)
        assertEquals(0f, chain.alpha)
        chain.update(true, 100)
        chain.update(true, 10000)
        assertEquals(1f, chain.alpha)
        chain.update(false, 10010)
        assertEquals(1f, chain.alpha)
        chain.update(false, 10460)
        assertTrue(chain.alpha in .1f.. .9f)
        chain.update(false, 10910)
        assertEquals(0f, chain.alpha)
    }

    @Test fun restartingClearsThePreviousChainAndItsRelease() {
        val chain = PaperInkRepeatChain(900)
        chain.update(true, 0)
        chain.update(false, 100)
        chain.reset()
        chain.update(false, 200)
        assertEquals(0f, chain.alpha)
        chain.update(true, 300)
        assertEquals(1f, chain.alpha)
    }
}
