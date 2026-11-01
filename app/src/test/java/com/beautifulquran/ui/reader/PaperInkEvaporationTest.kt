package com.beautifulquran.ui.reader

import org.junit.Assert.*
import org.junit.Test

class PaperInkEvaporationTest {
    private val unread = PaperInkDeposit(-1f, 0f, 0f, 0f, 0f)
    private val dry = PaperInkDeposit(1f, 0f, 0f, 0f, 0f)
    private val partial = PaperInkDeposit(.43f, .8f, 0f, 0f, 0f)

    @Test fun unreadMountNeverFlashesInk() {
        val ink = PaperInkEvaporation(true)
        assertEquals(unread, ink.sample(unread, 0f))
        ink.transition(true)
        assertFalse(ink.isEvaporating(1f))
    }

    @Test fun anUnreadFrameBeforeTheTransitionDoesNotBecomeTheDepartingInk() {
        // Verse handoff: the word's state reads unread (and is sampled) one
        // frame before the transition arrives. The ink it leaves is still the
        // settled deposit, so it dissolves from that, not from unread.
        val ink = PaperInkEvaporation(false)
        ink.sample(dry, 1f)
        ink.sample(unread, 1f)
        ink.transition(true)
        assertTrue(ink.isEvaporating(1f))
        assertEquals(dry, ink.sample(unread, 1f))
        assertEquals(dry, ink.sample(unread, .5f))
        assertFalse(ink.isEvaporating(0f))
    }

    @Test fun startingPlaybackDoesNotReleaseUnrecitedPlainText() {
        val ink = PaperInkEvaporation(upcoming = false, read = false)
        ink.sample(dry, 1f)
        ink.transition(upcoming = true, read = false)
        assertFalse(ink.isEvaporating(1f))
        assertEquals(unread, ink.sample(unread, 1f))
    }

    @Test fun rewindReleasesSettledInkWithoutReplayingOldWetness() {
        val ink = PaperInkEvaporation(false)
        ink.sample(partial, 1f)
        ink.sample(dry, 1f)
        ink.transition(true)
        for (remaining in listOf(1f, .75f, .25f)) {
            assertTrue(ink.isEvaporating(remaining))
            assertEquals(dry, ink.sample(unread, remaining))
        }
        assertFalse(ink.isEvaporating(0f))
        assertEquals(unread, ink.sample(unread, 0f))
    }

    @Test fun partialRepeatRetainsItsExactDepositThroughoutEvaporation() {
        val ink = PaperInkEvaporation(false)
        val orange = partial.copy(repeatPass = 1f, repeatTint = .9f, retained = 1f)
        ink.sample(orange, 1f)
        ink.transition(true)
        assertEquals(orange, ink.sample(unread, .9f))
        // Poll updates and subsequent seeks over still-unread words do not restart it.
        ink.transition(true)
        assertEquals(orange, ink.sample(unread, .2f))
    }

    @Test fun resumedVoiceCancelsDepartureImmediatelyAndCanDepartAgain() {
        val ink = PaperInkEvaporation(false)
        ink.sample(dry, 1f)
        ink.transition(true)
        ink.sample(unread, .5f)
        ink.transition(false)
        assertFalse(ink.isEvaporating(.5f))
        assertEquals(partial, ink.sample(partial, .5f))
        ink.transition(true)
        assertEquals(partial, ink.sample(unread, .4f))
    }

    @Test fun reciterRepeatsDoNotEvaporateTheRetainedForwardPrefix() {
        val ink = PaperInkEvaporation(false)
        ink.sample(dry, 1f)
        ink.transition(false)
        assertFalse(ink.isEvaporating(1f))
        assertEquals(partial, ink.sample(partial, 1f))
    }
}
