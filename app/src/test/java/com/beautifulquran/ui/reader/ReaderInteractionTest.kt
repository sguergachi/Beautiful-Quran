package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Precedence table for reader follow / jump / search / annotation.
 * Screen effects must not invent competing rules.
 */
class ReaderInteractionTest {

    private val idle = ReaderInteractionState()

    @Test
    fun `hand scroll disables follow`() {
        val next = ReaderInteraction.reduce(idle, ReaderInteractionEvent.UserMovedPage)
        assertFalse(next.followEnabled)
        assertEquals(0, next.pendingJumpAyah)
    }

    @Test
    fun `jump while playing resumes follow and parks pending ayah`() {
        val next = ReaderInteraction.reduce(
            idle.copy(followEnabled = false),
            ReaderInteractionEvent.JumpRequested(ayah = 12, resumeFollowIfPlaying = true),
        )
        assertEquals(12, next.pendingJumpAyah)
        assertTrue(next.followEnabled)
    }

    @Test
    fun `jump while idle keeps follow off`() {
        val next = ReaderInteraction.reduce(
            idle.copy(followEnabled = true),
            ReaderInteractionEvent.JumpRequested(ayah = 3, resumeFollowIfPlaying = false),
        )
        assertEquals(3, next.pendingJumpAyah)
        assertFalse(next.followEnabled)
    }

    @Test
    fun `jump settled clears only matching pending`() {
        val mid = idle.copy(pendingJumpAyah = 7)
        val settled = ReaderInteraction.reduce(mid, ReaderInteractionEvent.JumpSettled(7))
        assertEquals(0, settled.pendingJumpAyah)

        val superseded = idle.copy(pendingJumpAyah = 9)
        val stale = ReaderInteraction.reduce(superseded, ReaderInteractionEvent.JumpSettled(7))
        assertEquals(9, stale.pendingJumpAyah)
    }

    @Test
    fun `search and chapter advance disable follow`() {
        assertFalse(
            ReaderInteraction.reduce(idle, ReaderInteractionEvent.SearchNavigated).followEnabled,
        )
        assertFalse(
            ReaderInteraction.reduce(idle, ReaderInteractionEvent.ChapterAdvanceStarted)
                .followEnabled,
        )
    }

    @Test
    fun `enable follow restores tracking`() {
        val off = idle.copy(followEnabled = false)
        assertTrue(
            ReaderInteraction.reduce(off, ReaderInteractionEvent.EnableFollow).followEnabled,
        )
    }

    @Test
    fun `annotating blocks playback follow even when follow enabled`() {
        val annotating = idle.copy(followEnabled = true, annotating = true)
        assertFalse(ReaderInteraction.shouldFollowPlayback(annotating))

        val open = ReaderInteraction.reduce(idle, ReaderInteractionEvent.SetAnnotating(true))
        assertTrue(open.annotating)
        assertFalse(ReaderInteraction.shouldFollowPlayback(open))

        val closed = ReaderInteraction.reduce(open, ReaderInteractionEvent.SetAnnotating(false))
        assertTrue(ReaderInteraction.shouldFollowPlayback(closed))
    }

    @Test
    fun `pending jump blocks playback follow until settled`() {
        val jumping = idle.copy(followEnabled = true, pendingJumpAyah = 4)
        assertFalse(ReaderInteraction.shouldFollowPlayback(jumping))
        val settled = ReaderInteraction.reduce(jumping, ReaderInteractionEvent.JumpSettled(4))
        assertTrue(ReaderInteraction.shouldFollowPlayback(settled))
    }

    @Test
    fun `selectedPlaybackAyah prefers pending jump`() {
        val state = idle.copy(pendingJumpAyah = 8, followEnabled = true)
        assertEquals(
            8,
            ReaderInteraction.selectedPlaybackAyah(
                state = state,
                isThisSurahPlaying = true,
                activeAyah = 2,
                scrolledAyah = 5,
                fallbackAyah = 1,
                ayahCount = 20,
            ),
        )
    }

    @Test
    fun `selectedPlaybackAyah uses active ayah only when following and playing`() {
        val following = idle.copy(followEnabled = true, pendingJumpAyah = 0)
        assertEquals(
            3,
            ReaderInteraction.selectedPlaybackAyah(
                state = following,
                isThisSurahPlaying = true,
                activeAyah = 3,
                scrolledAyah = 9,
                fallbackAyah = 1,
                ayahCount = 20,
            ),
        )
        val notFollowing = following.copy(followEnabled = false)
        assertEquals(
            9,
            ReaderInteraction.selectedPlaybackAyah(
                state = notFollowing,
                isThisSurahPlaying = true,
                activeAyah = 3,
                scrolledAyah = 9,
                fallbackAyah = 1,
                ayahCount = 20,
            ),
        )
    }

    @Test
    fun `hand scroll during jump clears pending so jump effect cancels`() {
        val jumping = idle.copy(followEnabled = true, pendingJumpAyah = 11)
        val next = ReaderInteraction.reduce(jumping, ReaderInteractionEvent.UserMovedPage)
        assertEquals(0, next.pendingJumpAyah)
        assertFalse(next.followEnabled)
        assertFalse(ReaderInteraction.shouldFollowPlayback(next))
    }

    @Test
    fun `search during jump clears pending jump`() {
        val jumping = idle.copy(followEnabled = true, pendingJumpAyah = 6)
        val next = ReaderInteraction.reduce(jumping, ReaderInteractionEvent.SearchNavigated)
        assertEquals(0, next.pendingJumpAyah)
        assertFalse(next.followEnabled)
    }

    @Test
    fun `chapter advance during jump clears pending jump`() {
        val jumping = idle.copy(followEnabled = true, pendingJumpAyah = 2)
        val next = ReaderInteraction.reduce(jumping, ReaderInteractionEvent.ChapterAdvanceStarted)
        assertEquals(0, next.pendingJumpAyah)
        assertFalse(next.followEnabled)
    }

    @Test
    fun `stale JumpSettled after newer jump leaves new pending intact`() {
        var state = idle
        state = ReaderInteraction.reduce(
            state,
            ReaderInteractionEvent.JumpRequested(5, resumeFollowIfPlaying = true),
        )
        state = ReaderInteraction.reduce(
            state,
            ReaderInteractionEvent.JumpRequested(9, resumeFollowIfPlaying = true),
        )
        state = ReaderInteraction.reduce(state, ReaderInteractionEvent.JumpSettled(5))
        assertEquals(9, state.pendingJumpAyah)
    }

    @Test
    fun `annotation close preserves prior follow choice`() {
        val following = idle.copy(followEnabled = true)
        val open = ReaderInteraction.reduce(following, ReaderInteractionEvent.SetAnnotating(true))
        assertFalse(ReaderInteraction.shouldFollowPlayback(open))
        val closed = ReaderInteraction.reduce(open, ReaderInteractionEvent.SetAnnotating(false))
        assertTrue(closed.followEnabled)
        assertTrue(ReaderInteraction.shouldFollowPlayback(closed))

        val notFollowing = idle.copy(followEnabled = false)
        val openOff = ReaderInteraction.reduce(notFollowing, ReaderInteractionEvent.SetAnnotating(true))
        val closedOff = ReaderInteraction.reduce(openOff, ReaderInteractionEvent.SetAnnotating(false))
        assertFalse(closedOff.followEnabled)
        assertFalse(ReaderInteraction.shouldFollowPlayback(closedOff))
    }

    @Test
    fun `shouldFollowPlayback false while annotating even if follow on`() {
        assertFalse(
            ReaderInteraction.shouldFollowPlayback(
                idle.copy(followEnabled = true, annotating = true, pendingJumpAyah = 0),
            ),
        )
    }

    @Test
    fun `shouldHomeOntoPlaybackTarget skips same target while follow stays on`() {
        // Pause/play and seek flicker must not re-home a tall mid-verse.
        assertFalse(
            ReaderInteraction.shouldHomeOntoPlaybackTarget(
                target = 12,
                justEnabledFollow = false,
                lastHomedTarget = 12,
            ),
        )
        assertTrue(
            ReaderInteraction.shouldHomeOntoPlaybackTarget(
                target = 13,
                justEnabledFollow = false,
                lastHomedTarget = 12,
            ),
        )
        assertTrue(
            ReaderInteraction.shouldHomeOntoPlaybackTarget(
                target = 12,
                justEnabledFollow = true,
                lastHomedTarget = 12,
            ),
        )
        assertTrue(
            ReaderInteraction.shouldHomeOntoPlaybackTarget(
                target = 12,
                justEnabledFollow = false,
                lastHomedTarget = null,
            ),
        )
    }

    @Test
    fun `playback recovery restores word directly when playing ayah is visibly tall`() {
        assertTrue(
            ReaderInteraction.shouldRestoreWordBeforeVerseHome(
                verseHomeRequested = true,
                playingAyahHasLiveTallGeometry = true,
            ),
        )
        assertFalse(
            ReaderInteraction.shouldRestoreWordBeforeVerseHome(
                verseHomeRequested = false,
                playingAyahHasLiveTallGeometry = true,
            ),
        )
        // Wholly offscreen and normal-height playing ayahs still need verse focus.
        assertFalse(
            ReaderInteraction.shouldRestoreWordBeforeVerseHome(
                verseHomeRequested = true,
                playingAyahHasLiveTallGeometry = false,
            ),
        )
    }

    @Test
    fun `word-play seed skips verse home so tall-ayah bottom taps stay put`() {
        // Screen sets lastHomed = tapped ayah and followWasEnabled = true before
        // EnableFollow so justEnabled is false and the same target does not re-home.
        assertFalse(
            ReaderInteraction.shouldHomeOntoPlaybackTarget(
                target = 141,
                justEnabledFollow = false,
                lastHomedTarget = 141,
            ),
        )
        // Normal/offscreen return still homes once visible-tall restore does not apply.
        assertTrue(
            ReaderInteraction.shouldHomeOntoPlaybackTarget(
                target = 141,
                justEnabledFollow = true,
                lastHomedTarget = null,
            ),
        )
    }

    @Test
    fun `word follow requires actual play but resume may restore held word once`() {
        // End of last ayah: isPlaying false while chrome may still be recessed.
        assertFalse(
            ReaderInteraction.shouldKeepWordInView(
                followPlayback = true,
                isPlaying = false,
                hasActiveWord = true,
            ),
        )
        assertTrue(
            ReaderInteraction.shouldKeepWordInView(
                followPlayback = true,
                isPlaying = true,
                hasActiveWord = true,
            ),
        )
        assertTrue(
            ReaderInteraction.shouldKeepWordInView(
                followPlayback = true,
                isPlaying = false,
                hasActiveWord = true,
                restoreRequested = true,
            ),
        )
        // A pending jump, note, hand scroll, or Ink Lab freeze makes the
        // arbiter's followPlayback false; resume restoration must still yield.
        assertFalse(
            ReaderInteraction.shouldKeepWordInView(
                followPlayback = false,
                isPlaying = true,
                hasActiveWord = true,
                restoreRequested = true,
            ),
        )
        assertFalse(
            ReaderInteraction.shouldKeepWordInView(
                followPlayback = true,
                isPlaying = true,
                hasActiveWord = false,
                restoreRequested = true,
            ),
        )
    }
}
