package com.beautifulquran.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscontinuityPolicyTest {
    @Test
    fun `only seeks repeats and playlist replacements restart ink`() {
        assertTrue(discontinuityRestartsInk(Player.DISCONTINUITY_REASON_SEEK))
        assertTrue(discontinuityRestartsInk(Player.DISCONTINUITY_REASON_AUTO_TRANSITION))
        assertTrue(discontinuityRestartsInk(Player.DISCONTINUITY_REASON_REMOVE))
        assertFalse(discontinuityRestartsInk(Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT))
        assertFalse(discontinuityRestartsInk(Player.DISCONTINUITY_REASON_INTERNAL))
        assertFalse(discontinuityRestartsInk(Player.DISCONTINUITY_REASON_SILENCE_SKIP))
        assertFalse(discontinuityRestartsInk(Player.DISCONTINUITY_REASON_SKIP))
    }

    @Test
    fun `seek adjustment advances clock without restarting ink`() {
        val seek = PlaybackPositionEvents().afterDiscontinuity(
            reason = Player.DISCONTINUITY_REASON_SEEK,
        )
        val adjusted = seek.afterDiscontinuity(
            reason = Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT,
        )

        assertEquals(2L, adjusted.clockId)
        assertEquals(1L, adjusted.inkId)
        assertEquals(Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT, adjusted.reason)
        assertFalse(adjusted.itemChanged)
    }

    @Test
    fun `an automatic advance records that the playlist item changed`() {
        val advanced = PlaybackPositionEvents().afterDiscontinuity(
            reason = Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
            itemChanged = true,
        )

        assertEquals(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, advanced.reason)
        assertTrue(advanced.itemChanged)
        assertEquals(1L, advanced.clockId)
        assertEquals(1L, advanced.inkId)
    }

    @Test
    fun `a same verse silence landing keeps the ink`() {
        val seek = PlaybackPositionEvents().afterDiscontinuity(
            reason = Player.DISCONTINUITY_REASON_SEEK,
            positionMs = 4_283L,
            keepInk = true,
        )

        assertEquals(1L, seek.clockId)
        assertEquals(0L, seek.inkId)
        assertEquals(4_283L, seek.positionMs)
    }

    @Test
    fun `silence ink matches only its own landing`() {
        val ink = SilenceInk()
        ink.arm(4_283L)
        assertFalse(ink.claims(0L, itemChanged = false))
        assertFalse(ink.claims(4_283L, itemChanged = true))
        assertTrue(ink.claims(4_283L, itemChanged = false))
        assertFalse(ink.claims(4_283L, itemChanged = false))
    }

    @Test
    fun `a jump records where on the file it landed`() {
        val landed = PlaybackPositionEvents().afterDiscontinuity(
            reason = Player.DISCONTINUITY_REASON_SEEK,
            positionMs = 1_951L,
        )

        assertEquals(1_951L, landed.positionMs)
    }

    @Test
    fun `only filling an empty playlist needs its own ink event`() {
        val changed = Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED
        assertTrue(filledEmptyPlaylist(wasEmpty = true, isEmpty = false, reason = changed))
        // A replacement already arrives as a REMOVE discontinuity; counting it again is a second performance.
        assertFalse(filledEmptyPlaylist(wasEmpty = false, isEmpty = false, reason = changed))
        assertFalse(filledEmptyPlaylist(wasEmpty = false, isEmpty = true, reason = changed))
        assertFalse(filledEmptyPlaylist(wasEmpty = true, isEmpty = true, reason = changed))
        // A duration resolving on the same items is not a new playlist.
        assertFalse(filledEmptyPlaylist(wasEmpty = true, isEmpty = false,
            reason = Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE))
    }

    @Test
    fun `the first playlist after launch starts a new ink performance`() {
        // A cold word tap seeds inkId + 1 and waits for exactly that performance.
        val loaded = PlaybackPositionEvents().afterFirstPlaylist(positionMs = 640L)

        assertEquals(1L, loaded.inkId)
        assertEquals(1L, loaded.clockId)
        assertTrue(loaded.itemChanged)
        assertEquals(640L, loaded.positionMs)
    }

    @Test
    fun `manual seek clears a silence mark even at the same landing`() {
        val ink = SilenceInk()
        ink.arm(4283)
        ink.clear()
        assertFalse(ink.claims(4283, itemChanged = false))
        val event = PlaybackPositionEvents().afterDiscontinuity(
            Player.DISCONTINUITY_REASON_SEEK, positionMs = 4283, keepInk = false)
        assertEquals(1L, event.inkId)
    }

}
