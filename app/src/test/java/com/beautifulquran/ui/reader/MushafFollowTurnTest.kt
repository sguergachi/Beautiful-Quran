package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the leaf turns under a recitation that is still on it. */
class MushafFollowTurnTest {

    @Test
    fun `the turn begins inside the leaf's last word, not after it`() {
        // A second-long final word is left with half a second of voice still
        // on the page it is leaving — the paper moves while the reader is
        // still hearing the leaf, which is what turning a page for someone
        // looks like.
        assertEquals(500L, mushafTurnLeadDelayMs(1_000L, 1f))
        assertEquals(1_500L, mushafTurnLeadDelayMs(2_000L, 1f))
    }

    @Test
    fun `a word shorter than the lead turns at once`() {
        // There is no negative wait to take. A word the lead outruns is a word
        // the turn should already have started under, so it starts now.
        assertEquals(0L, mushafTurnLeadDelayMs(500L, 1f))
        assertEquals(0L, mushafTurnLeadDelayMs(120L, 1f))
    }

    @Test
    fun `the wait is in the reader's own time, not the reciter's`() {
        // Playback speed shortens the word on the clock, so the dwell has to
        // be measured after it: at double speed a two-second word is one
        // second of listening, and the lead comes out of that one.
        assertEquals(500L, mushafTurnLeadDelayMs(2_000L, 2f))
        assertEquals(3_500L, mushafTurnLeadDelayMs(2_000L, 0.5f))
        // Slower listening never turns sooner than faster listening.
        assertTrue(
            mushafTurnLeadDelayMs(3_000L, 0.75f) >
                mushafTurnLeadDelayMs(3_000L, 1.5f),
        )
    }

    @Test
    fun `a nonsense speed cannot stall the turn forever`() {
        // Zero would divide the dwell into an eternity and strand the reader
        // on a leaf the voice has left.
        assertTrue(mushafTurnLeadDelayMs(1_000L, 0f) < 60_000L)
    }

    @Test
    fun `fade-led ayah without a word leaves the mushaf fully inked`() {
        assertEquals(
            MushafInkPackKind.STATIC,
            mushafInkPackKind(
                playingHere = true,
                hasActiveWord = false,
                hasSearchFlash = false,
            ),
        )
    }

    @Test
    fun `an idle search hit owns a flash pack without becoming playback`() {
        assertEquals(
            MushafInkPackKind.SEARCH_FLASH,
            mushafInkPackKind(
                playingHere = false,
                hasActiveWord = false,
                hasSearchFlash = true,
            ),
        )
    }

    @Test
    fun `voice page keeps live clocks while the old page settles out`() {
        assertTrue(mushafUsesLiveInk(isSettled = false, isVoicePage = true))
        assertTrue(mushafUsesLiveInk(isSettled = true, isVoicePage = false))
        assertFalse(mushafUsesLiveInk(isSettled = false, isVoicePage = false))
    }

    @Test
    fun `a repeat at the page tail does not turn forward`() {
        assertFalse(
            mushafTailTurnAllowed(
                nextTimingPage = 58,
                followingPage = 59,
                isFinalAyah = false,
            ),
        )
        assertTrue(
            mushafTailTurnAllowed(
                nextTimingPage = 59,
                followingPage = 59,
                isFinalAyah = false,
            ),
        )
        assertFalse(
            mushafTailTurnAllowed(
                nextTimingPage = null,
                followingPage = 59,
                isFinalAyah = true,
            ),
        )
    }

    @Test
    fun `a delayed turn cannot be inherited by another word or seek`() {
        val expected = ActiveWord(
            ayah = 77,
            wordPosition = 27,
            startMs = 1_000,
            durationMs = 800,
            activation = 4,
        )
        assertTrue(mushafSameActivation(expected, expected.copy()))
        assertFalse(mushafSameActivation(expected, expected.copy(wordPosition = 25)))
        assertFalse(mushafSameActivation(expected, expected.copy(activation = 5)))
        assertFalse(mushafSameActivation(expected, null))
    }

    @Test
    fun `mushaf return direction compares leaves rather than hidden list rows`() {
        assertTrue(mushafReturnPointsUp(currentPage = 120, playbackPage = 118))
        assertFalse(mushafReturnPointsUp(currentPage = 120, playbackPage = 122))
    }
}
