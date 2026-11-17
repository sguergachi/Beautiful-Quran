package com.beautifulquran.playback

import android.content.Context
import android.os.Handler
import android.os.PowerManager
import android.util.Log
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.beautifulquran.domain.BASMALAH_PLAYLIST_AYAH
import com.google.common.util.concurrent.ListenableFuture

/**
 * The pause between verses (Settings → Playback): a moment of silence after
 * each verse before the next begins.
 *
 * The player stops exactly at the end of each verse file
 * ([ExoPlayer.setPauseAtEndOfMediaItems]) and [VerseGapPlayer] starts it again
 * after the chosen silence. Nothing is spliced into the audio, so the files,
 * their clocks and the word timings stay exactly as they are.
 *
 * To everything that watches the session (the app's controller, the ink, the
 * notification and lock screen) the silence is still recitation: the player
 * reports itself playing throughout, the way a reciter's own pause is.
 */

/**
 * Whether a verse that has just ended is followed by the silence. Only a
 * chapter's verses take it: not the basmalah that opens a chapter, not the
 * labs (they listen with silence skipping off, on the file's own clock),
 * and not the very end of the queue unless the next chapter plays on.
 */
internal fun verseGapFollows(
    mediaId: String?,
    hasNextItem: Boolean,
    repeating: Boolean,
    playsOnToNextChapter: Boolean,
    listening: Boolean,
): Boolean {
    if (!listening) return false
    val ended = mediaId?.let(PlayerController::parseMediaId) ?: return false
    if (ended.ayah == BASMALAH_PLAYLIST_AYAH) return false
    return hasNextItem || repeating || playsOnToNextChapter
}

/** A player paused at an item's end by [ExoPlayer.setPauseAtEndOfMediaItems]
 * is in the verse gap, and reads as still playing. */
internal fun heldBetweenVerses(playWhenReady: Boolean, reason: Int): Boolean =
    !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM

/**
 * The session's view of [exo] with the verse gap folded in. Every pause at a
 * verse's end is answered here: after [gapMs] when the gap applies, at once
 * when it does not. Anything else the listener does (pause, seek, a new
 * chapter, a phone call taking the audio) ends the gap first.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class VerseGapPlayer(
    private val exo: ExoPlayer,
    context: Context,
    private val gapMs: () -> Long,
    private val gapFollows: (Player) -> Boolean,
) : ForwardingSimpleBasePlayer(exo) {

    private val handler = Handler(exo.applicationLooper)
    // The screen may be off: without a lock the CPU can sleep through the
    // silence, since the player lets its own go while paused.
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BeautifulQuran:verseGap")
        .apply { setReferenceCounted(false) }
    private val resume = Runnable { endGap(play = true) }
    /** How the play that the gap continues began, so holding it changes nothing. */
    private var playingReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
    /** Why [exo] last changed play intent; Player does not expose it. */
    private var exoReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                exoReason = reason
                when {
                    heldBetweenVerses(playWhenReady, reason) -> beginGap()
                    // Played or paused by anyone else: the gap is over.
                    else -> cancelTimer()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_IDLE && inGap()) endGap(play = false)
            }
        })
    }

    private fun inGap(): Boolean = heldBetweenVerses(exo.playWhenReady, exoReason)

    private fun beginGap() {
        val ms = if (gapFollows(exo)) gapMs() else 0L
        Log.d(TAG, "gap ${ms}ms after ${exo.currentMediaItem?.mediaId}")
        if (ms <= 0L) {
            // Never left paused at an item's end: carry straight on, as if
            // the pause had not been asked for.
            handler.post(resume)
            return
        }
        wakeLock.acquire(ms + WAKE_SLACK_MS)
        handler.removeCallbacks(resume)
        handler.postDelayed(resume, ms)
    }

    private fun cancelTimer() {
        handler.removeCallbacks(resume)
        if (wakeLock.isHeld) wakeLock.release()
    }

    /** Leaves the gap: on into the next verse, or (a pause, a stop) settled
     * as an ordinary pause so the session stops reporting play. */
    private fun endGap(play: Boolean) {
        cancelTimer()
        if (!inGap()) return
        if (play) exo.play() else exo.pause()
    }

    override fun getState(): State {
        val state = super.getState()
        if (state.playWhenReady) playingReason = state.playWhenReadyChangeReason
        return if (heldBetweenVerses(state.playWhenReady, state.playWhenReadyChangeReason)) {
            state.buildUpon().setPlayWhenReady(true, playingReason).build()
        } else {
            state
        }
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        // Pause in the silence stays paused; play goes on at once.
        endGap(play = playWhenReady)
        return super.handleSetPlayWhenReady(playWhenReady)
    }

    // A listener who moves while the silence holds is still listening: the
    // move plays on from where it lands.
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        endGap(play = true)
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        endGap(play = true)
        return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun handleStop(): ListenableFuture<*> {
        endGap(play = false)
        return super.handleStop()
    }

    override fun handleRelease(): ListenableFuture<*> {
        cancelTimer()
        return super.handleRelease()
    }

    private companion object {
        const val TAG = "VerseGap"
        /** The lock outlives the silence by this much, then lets go by itself. */
        const val WAKE_SLACK_MS = 2_000L
    }
}
