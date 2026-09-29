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
    fun `a jump records where on the file it landed`() {
        val landed = PlaybackPositionEvents().afterDiscontinuity(
            reason = Player.DISCONTINUITY_REASON_SEEK,
            positionMs = 1_951L,
        )

        assertEquals(1_951L, landed.positionMs)
    }
}
