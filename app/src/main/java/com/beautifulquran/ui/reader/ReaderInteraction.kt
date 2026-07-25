package com.beautifulquran.ui.reader

/**
 * Pure reader interaction arbiter: which on-page intent owns follow and focus
 * when jump, search, hand scroll, annotation edit, and playback compete.
 *
 * Compose effects submit [ReaderInteractionEvent]s; [reduce] returns the next
 * state. [ReaderFocusController] remains the sole scroll writer — this module
 * only decides *whether* follow may drive focus and *which* jump is pending.
 */
data class ReaderInteractionState(
    /** Lyric-style auto-scroll with the reciter. */
    val followEnabled: Boolean = true,
    /** 1-based ayah rail/search jump in flight; 0 = none. */
    val pendingJumpAyah: Int = 0,
    /** True while a verse note field is open — follow must not yank the page. */
    val annotating: Boolean = false,
)

sealed class ReaderInteractionEvent {
    /** Vertical hand drag on the page — reader is navigating by eye. */
    data object UserMovedPage : ReaderInteractionEvent()

    /**
     * Rail / programmatic jump to [ayah]. [resumeFollowIfPlaying] restores
     * follow when this surah is already the playing one (jump within recitation).
     */
    data class JumpRequested(
        val ayah: Int,
        val resumeFollowIfPlaying: Boolean,
    ) : ReaderInteractionEvent()

    /** Focus approach for [ayah] finished (or was superseded). */
    data class JumpSettled(val ayah: Int) : ReaderInteractionEvent()

    /** In-surah search moved to a match — follow yields to the match glide. */
    data object SearchNavigated : ReaderInteractionEvent()

    /** Continuous chapter advance starts — do not chase the old playlist. */
    data object ChapterAdvanceStarted : ReaderInteractionEvent()

    /** Return-to-ayah, play, or basmalah tap — re-enable lyric follow. */
    data object EnableFollow : ReaderInteractionEvent()

    /** Verse note editor opened or closed. */
    data class SetAnnotating(val active: Boolean) : ReaderInteractionEvent()
}

object ReaderInteraction {

    fun reduce(
        state: ReaderInteractionState,
        event: ReaderInteractionEvent,
    ): ReaderInteractionState = when (event) {
        // Direct manipulation / search / chapter change supersede an in-flight
        // rail jump: clearing pendingJump cancels the jump LaunchedEffect so
        // focusController is not still pulled to the old target.
        ReaderInteractionEvent.UserMovedPage -> state.copy(
            followEnabled = false,
            pendingJumpAyah = 0,
        )

        is ReaderInteractionEvent.JumpRequested -> state.copy(
            pendingJumpAyah = event.ayah.coerceAtLeast(1),
            followEnabled = event.resumeFollowIfPlaying,
        )

        is ReaderInteractionEvent.JumpSettled ->
            if (state.pendingJumpAyah == event.ayah) {
                state.copy(pendingJumpAyah = 0)
            } else {
                state
            }

        ReaderInteractionEvent.SearchNavigated -> state.copy(
            followEnabled = false,
            pendingJumpAyah = 0,
        )

        ReaderInteractionEvent.ChapterAdvanceStarted -> state.copy(
            followEnabled = false,
            pendingJumpAyah = 0,
        )

        ReaderInteractionEvent.EnableFollow -> state.copy(followEnabled = true)

        is ReaderInteractionEvent.SetAnnotating -> state.copy(annotating = event.active)
    }

    /**
     * Playback (and full-ayah-repeat re-home) may call focus only when follow is
     * on, no note is open, and no rail jump is still landing.
     */
    fun shouldFollowPlayback(state: ReaderInteractionState): Boolean =
        state.followEnabled && !state.annotating && state.pendingJumpAyah == 0

    /**
     * Whether lyric-follow should call [ReaderFocusController.focus] for
     * [target], after the direct visible-tall-word policy below has been ruled
     * out. A normal return-to-verse homes when follow just re-enabled. While
     * follow stays on, only target changes home; re-homing the same tall verse
     * on pause/play/seek fights word-band follow and stutters up then down.
     */
    fun shouldHomeOntoPlaybackTarget(
        target: Int,
        justEnabledFollow: Boolean,
        lastHomedTarget: Int?,
    ): Boolean = justEnabledFollow || target != lastHomedTarget

    /**
     * Re-enabling follow inside a visible tall ayah should restore its active
     * word directly. Homing the ayah first moves in the opposite direction,
     * pins line one, and queues the real word correction behind that glide.
     */
    fun shouldRestoreWordOnFollowEnable(
        justEnabledFollow: Boolean,
        targetHasLiveTallGeometry: Boolean,
    ): Boolean = justEnabledFollow && targetHasLiveTallGeometry

    /**
     * Word-band keep-in-view continuously tracks **actual** play, not the
     * debounced "reciting chrome" flag. [restoreRequested] is the narrow
     * exception: opening / foreground resume may reveal the held word once
     * while paused. Keeping that request one-shot prevents Media3's end-state
     * position reset from pulling the final ayah back to word one.
     */
    fun shouldKeepWordInView(
        followPlayback: Boolean,
        isPlaying: Boolean,
        hasActiveWord: Boolean,
        restoreRequested: Boolean = false,
    ): Boolean =
        followPlayback && hasActiveWord && (isPlaying || restoreRequested)

    /**
     * Which ayah the transport "play from here" control should use: a pending
     * jump wins, else follow uses the reciting ayah when playing, else scroll.
     */
    fun selectedPlaybackAyah(
        state: ReaderInteractionState,
        isThisSurahPlaying: Boolean,
        activeAyah: Int?,
        scrolledAyah: Int,
        fallbackAyah: Int,
        ayahCount: Int,
    ): Int {
        val relyOnScroll =
            state.pendingJumpAyah > 0 || !isThisSurahPlaying || !state.followEnabled
        val position = if (relyOnScroll) scrolledAyah else (activeAyah ?: scrolledAyah)
        val chosen = state.pendingJumpAyah.takeIf { it > 0 } ?: position.takeIf { it > 0 } ?: fallbackAyah
        return chosen.coerceIn(1, ayahCount.coerceAtLeast(1))
    }
}
