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

/** Pure recovery chosen after a display reflow has finished measuring. */
internal data class LayoutReflowRecovery(
    val focusAyah: Int,
    val restoreWordDirectly: Boolean,
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

    /**
     * Initial focus ownership for a freshly opened reader. An explicit verse
     * in the same paused playlist is a manual selection: when it differs from
     * the held media item, park it as a jump so playback cannot reclaim focus
     * before the playlist seeks there. An autoplay request already owns focus
     * through playback and must not be mistaken for a paused selection.
     */
    fun initialState(
        requestedAyah: Int?,
        isThisSurahPlaying: Boolean,
        isPlaying: Boolean,
        playbackAyah: Int?,
        playbackRequested: Boolean = false,
    ): ReaderInteractionState {
        val target = requestedAyah?.takeIf {
            it > 0 && isThisSurahPlaying && !isPlaying &&
                !playbackRequested && it != playbackAyah
        } ?: return ReaderInteractionState()
        return reduce(
            ReaderInteractionState(),
            ReaderInteractionEvent.JumpRequested(target, resumeFollowIfPlaying = false),
        )
    }

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
     * A word tap seeks after this frame. Until Media3 names [pendingWordTapAyah],
     * the playback target is still the previous item — homing onto it jumps
     * the page away from the tap, with no wash where the finger was.
     */
    fun wordTapAwaitingSeek(target: Int, pendingWordTapAyah: Int?): Boolean =
        pendingWordTapAyah != null && target != pendingWordTapAyah

    /**
     * A playback-owned recovery inside the visible, tall **playing** ayah
     * should restore its active word directly. Homing the fade-led verse target
     * first can move in the opposite direction, pin line one, and queue the
     * real word correction behind that glide.
     */
    fun shouldRestoreWordBeforeVerseHome(
        verseHomeRequested: Boolean,
        playingAyahHasLiveTallGeometry: Boolean,
    ): Boolean = verseHomeRequested && playingAyahHasLiveTallGeometry

    /** Keep the media ayah sticky only while playback owns this reader. */
    internal fun layoutStickyAyah(
        playbackOwnsFocus: Boolean,
        playingAyah: Int?,
        scrolledAyah: Int,
    ): Int = playingAyah?.takeIf { playbackOwnsFocus && it > 0 } ?: scrolledAyah

    /**
     * Resolve display-reflow recovery without leaking Compose timing into the
     * policy. Playback pins the actual media ayah, never the fade-led visual
     * target; a live tall ayah restores its current word without homing first.
     */
    internal fun layoutReflowRecovery(
        layoutChanged: Boolean,
        playbackOwnsFocus: Boolean,
        playingAyah: Int?,
        stickyAyah: Int,
        playingAyahHasLiveTallGeometry: Boolean,
    ): LayoutReflowRecovery? {
        if (!layoutChanged) return null
        val playbackAyah = playingAyah?.takeIf { playbackOwnsFocus }
        return LayoutReflowRecovery(
            focusAyah = playbackAyah ?: stickyAyah,
            restoreWordDirectly =
                playbackAyah != null && playingAyahHasLiveTallGeometry,
        )
    }

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

    /** Where pressing play on a leaf starts. */
    data class MushafPlayTarget(val surahId: Int, val ayah: Int, val word: Int?)

    /**
     * Which words the transport should start on when play is pressed on a leaf.
     *
     * A leaf is a place in the book, not a place in a chapter, so the answer
     * cannot come from the loaded chapter's scroll position the way it does on
     * a scrolling page. The reader turned or scrubbed to *this* paper and asked
     * for it to be recited: the target is the first word standing on it, which
     * is often mid-verse, and often belongs to a chapter that is not loaded.
     *
     * The one thing that outranks the leaf is the playhead itself. If what is
     * paused is a verse this leaf carries, play is a resume and not a seek —
     * pressing pause and pressing play again must not throw the reader back to
     * the top of the page they were already listening to. [heldAyah] is that
     * verse (of [loadedSurahId]); null means the paused position is elsewhere,
     * or nothing is loaded to be paused.
     *
     * Returns null when the transport should simply resume.
     */
    fun mushafPlayTarget(
        pendingJumpAyah: Int,
        loadedSurahId: Int,
        heldAyah: Int?,
        leafFirstWord: MushafPlayTarget?,
        leafAyahs: Set<Pair<Int, Int>>,
    ): MushafPlayTarget? {
        // A chapter opened from the index has already named its verse; the leaf
        // it lands on is that request's consequence, not a competing one.
        if (pendingJumpAyah > 0) {
            return MushafPlayTarget(loadedSurahId, pendingJumpAyah, null)
        }
        val leaf = leafFirstWord ?: return null
        if (heldAyah != null && (loadedSurahId to heldAyah) in leafAyahs) return null
        return leaf
    }
}
