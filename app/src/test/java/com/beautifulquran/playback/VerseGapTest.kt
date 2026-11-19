package com.beautifulquran.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerseGapTest {
    private fun follows(
        mediaId: String? = "2:5:7",
        hasNextItem: Boolean = true,
        repeating: Boolean = false,
        playsOn: Boolean = false,
        listening: Boolean = true,
    ) = verseGapFollows(mediaId, hasNextItem, repeating, playsOn, listening)

    @Test
    fun aVerseWithAnotherAfterItTakesThePause() = assertTrue(follows())

    @Test
    fun theBasmalahLeadsStraightIntoTheFirstVerse() = assertFalse(follows(mediaId = "2:0:7"))

    @Test
    fun theChaptersLastVerseEndsAtOnceUnlessTheNextChapterPlaysOn() {
        assertFalse(follows(hasNextItem = false))
        assertTrue(follows(hasNextItem = false, playsOn = true))
    }

    @Test
    fun aRepeatedVersePausesBeforeEachRepeat() = assertTrue(follows(hasNextItem = false, repeating = true))

    @Test
    fun theLabsAndForeignItemsNeverPause() {
        assertFalse(follows(listening = false))
        assertFalse(follows(mediaId = "word-audition"))
        assertFalse(follows(mediaId = null))
    }

    @Test
    fun onlyAPauseAtAnItemsEndIsTheGap() {
        assertTrue(heldBetweenVerses(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM))
        assertFalse(heldBetweenVerses(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST))
        assertFalse(heldBetweenVerses(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS))
        assertFalse(heldBetweenVerses(true, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM))
    }
}
