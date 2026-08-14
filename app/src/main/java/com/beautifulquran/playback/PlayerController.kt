package com.beautifulquran.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.beautifulquran.data.model.Reciter
import com.beautifulquran.data.model.Surah
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Which ayah the player is on, parsed from the current MediaItem.
 * [ayah] is 0 while the chapter-opening basmalah lead-in is playing. */
data class NowPlaying(
    val surahId: Int,
    val ayah: Int,
    val reciterId: Int,
)

/** Cumulative Media3 position events. Every jump advances [clockId], while
 * only a new ink performance advances [inkId]. */
data class PlaybackPositionEvents(
    val clockId: Long = 0L,
    val inkId: Long = 0L,
)

data class PlayerUiState(
    val isConnected: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    /** Ayah numbers (inclusive) playback is confined to, or null. */
    val repeatRange: IntRange? = null,
    val speed: Float = 1f,
    val error: String? = null,
    val positionEvents: PlaybackPositionEvents = PlaybackPositionEvents(),
)

/** Only a deliberate seek, automatic repeat, or playlist replacement starts
 * a new ink performance. Source corrections and skipped silence never do. */
internal fun discontinuityRestartsInk(reason: Int): Boolean =
    reason == Player.DISCONTINUITY_REASON_SEEK ||
        reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION ||
        reason == Player.DISCONTINUITY_REASON_REMOVE

internal fun PlaybackPositionEvents.afterDiscontinuity(
    reason: Int,
): PlaybackPositionEvents = copy(
    clockId = clockId + 1L,
    inkId = inkId + if (discontinuityRestartsInk(reason)) 1L else 0L,
)

/**
 * UI-process handle on the playback session. Wraps a [MediaController]
 * connected to [PlaybackService] and mirrors its state into [state].
 *
 * All controller mutations run through [PlayerCommandGate]: they are
 * serialised, and [stop] (plus a new [playSurah]) bumps an epoch so a
 * pending connect/play abandoned by a later intent never mutates the player.
 */
class PlayerController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val connectMutex = Mutex()
    private val commands = PlayerCommandGate()
    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    /**
     * Live voice analysis of the reciter (PCM tapped in the player's audio
     * sink). Read from the glint draw path during holds — no composition.
     */
    val voiceEnergy = VoiceEnergy().also { VoiceEnergy.active = it }

    /** Set synchronously (not in the launch bodies) so callers can sequence
     * playSurah + setRepeatRange without the coroutines racing the field. */
    private var repeatRange: IntRange? = null
    private var repeatEndPositionMs: Long? = null
    private var endedLoopHandledIndex: Int? = null
    private var repeatBoundaryJob: Job? = null

    /**
     * When the loaded playlist opens with a basmalah lead-in item, ayah N sits
     * at index N (basmalah at 0). Otherwise ayah N sits at index N - 1.
     */
    private var basmalahLeadIn: Boolean = false

    val positionMs: Long
        get() = controller?.currentPosition ?: 0L

    /**
     * [NowPlaying] parsed straight from the controller's current media item.
     *
     * [state] is fed by listener callbacks, so for a beat after an item
     * transition it can disagree with [positionMs], which already reads the
     * new item. Reading this together with [positionMs] gives the highlight
     * poll a coherent (item, position) pair.
     */
    val liveNowPlaying: NowPlaying?
        get() = controller?.currentMediaItem?.mediaId?.let(::parseMediaId)

    /** Duration of the ayah currently loaded, or 0 while it is still unknown
     * (buffering / not prepared). Media3 reports an unset duration as a large
     * sentinel, so anything non-positive is treated as "not yet known". */
    val durationMs: Long
        get() = controller?.duration?.takeIf { it > 0L } ?: 0L

    private suspend fun ensureController(): MediaController = connectMutex.withLock {
        controller?.let { return it }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val c = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    // Service went away (e.g. task removed while paused).
                    // Drop the stale handle so the next command reconnects.
                    this@PlayerController.controller = null
                    basmalahLeadIn = false
                    voiceEnergy.release()
                    _state.value = PlayerUiState(
                        positionEvents = _state.value.positionEvents,
                    )
                }
            })
            .buildAsync()
            .await()
        c.addListener(listener)
        controller = c
        _state.value = _state.value.copy(isConnected = true)
        syncFromController(c)
        c
    }

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            _state.value = _state.value.copy(
                positionEvents = _state.value.positionEvents.afterDiscontinuity(reason),
            )
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (player.playbackState != Player.STATE_ENDED) {
                endedLoopHandledIndex = null
            }
            val loopRestarted = loopSingleAyahIfNeeded(player) || loopRangeIfNeeded(player)
            syncFromController(player, forcePlaying = loopRestarted)
            if (repeatRange != null && (player.isPlaying || loopRestarted)) {
                startRepeatBoundaryMonitor()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(
                error = if (error.errorCode in networkErrorCodes) {
                    "No connection — check your network and try again"
                } else {
                    "Playback failed (${error.errorCodeName})"
                },
            )
        }
    }

    private val networkErrorCodes = setOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    )

    private fun syncFromController(player: Player, forcePlaying: Boolean = false) {
        basmalahLeadIn = player.mediaItemCount > 0 &&
            parseMediaId(player.getMediaItemAt(0).mediaId)?.ayah == 0
        val ended = player.playbackState == Player.STATE_ENDED
        val playing = player.isPlaying || forcePlaying
        _state.value = _state.value.copy(
            isPlaying = playing,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            nowPlaying = if (ended && !forcePlaying) {
                null
            } else {
                player.currentMediaItem?.mediaId?.let(::parseMediaId)
            },
            repeatMode = player.repeatMode,
            repeatRange = repeatRange,
            speed = player.playbackParameters.speed,
            // Playing again means we recovered; retire any stale error line.
            error = if (player.isPlaying) null else _state.value.error,
        )
        voiceEnergy.playbackSpeed = player.playbackParameters.speed
        // The PCM tap needs no lifecycle here — [VoiceEnergy.isLive] goes
        // quiet on its own within ~350 ms of the audio stopping.
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * Media3 normally handles REPEAT_MODE_ONE itself, but remote per-ayah
     * streams can still surface STATE_ENDED through the session. In that case,
     * restart the current item explicitly so the UI does not sit at the ayah's
     * final timestamp.
     */
    private fun loopSingleAyahIfNeeded(player: Player): Boolean {
        if (repeatRange != null) return false
        if (player.repeatMode != Player.REPEAT_MODE_ONE) return false
        if (player.playbackState != Player.STATE_ENDED) return false
        if (player.mediaItemCount == 0) return false

        val idx = player.currentMediaItemIndex.coerceIn(0, player.mediaItemCount - 1)
        if (endedLoopHandledIndex == idx) return false
        endedLoopHandledIndex = idx
        player.seekTo(idx, 0L)
        player.play()
        return true
    }

    /**
     * Keeps playback inside [repeatRange]: when the player crosses past the
     * range's last ayah (auto-advance, "next", or reaching the end of the
     * playlist) it wraps back to the range's first ayah.
     */
    private fun loopRangeIfNeeded(player: Player): Boolean {
        val range = repeatRange ?: return false
        if (player.mediaItemCount == 0) return false
        val lastIdx = playlistIndex(range.last).coerceAtMost(player.mediaItemCount - 1)
        val firstIdx = playlistIndex(range.first).coerceIn(0, player.mediaItemCount - 1)
        val idx = player.currentMediaItemIndex
        val endedAtRangeEnd = player.playbackState == Player.STATE_ENDED && idx >= lastIdx
        if (idx > lastIdx || endedAtRangeEnd) {
            if (endedAtRangeEnd) {
                if (endedLoopHandledIndex == idx) return false
                endedLoopHandledIndex = idx
            }
            player.seekTo(firstIdx, 0L)
            player.play()
            return true
        }
        return false
    }

    /** Repeats ayahs [startAyah]..[endAyah] (inclusive) of the loaded surah. */
    fun setRepeatRange(startAyah: Int, endAyah: Int, endPositionMs: Long? = null) {
        repeatRange = startAyah..endAyah
        repeatEndPositionMs = endPositionMs
        _state.value = _state.value.copy(repeatRange = repeatRange, repeatMode = Player.REPEAT_MODE_OFF)
        withController { c ->
            c.repeatMode = Player.REPEAT_MODE_OFF
            val idx = c.currentMediaItemIndex
            val firstIdx = playlistIndex(startAyah)
            val lastIdx = playlistIndex(endAyah)
            if (c.mediaItemCount > 0 && (idx < firstIdx || idx > lastIdx)) {
                c.seekTo(firstIdx.coerceIn(0, c.mediaItemCount - 1), 0L)
            }
            if (c.isPlaying) startRepeatBoundaryMonitor()
        }
    }

    fun clearRepeatRange() = resetRepeatState()

    /** Clears the range, its custom end position, and the boundary monitor. */
    private fun resetRepeatState() {
        repeatRange = null
        repeatEndPositionMs = null
        repeatBoundaryJob?.cancel()
        repeatBoundaryJob = null
        _state.value = _state.value.copy(repeatRange = null)
    }

    /**
     * Loads the whole surah as a playlist and starts from [startAyah].
     *
     * When [includeBasmalahLeadIn] is true (the default) and the surah opens
     * with a basmalah preface, a dedicated everyayah basmalah clip is prepended.
     * Pass [startWithBasmalah] = true to begin on that clip (chapter opening);
     * word taps and mid-ayah seeks leave it false so ayah 1 starts at the
     * requested position without re-hearing the basmalah.
     */
    fun playSurah(
        surahId: Int,
        ayahCount: Int,
        startAyah: Int,
        reciter: Reciter,
        surahName: String,
        startPositionMs: Long = 0L,
        preserveRepeatRange: Boolean = true,
        includeBasmalahLeadIn: Boolean = true,
        startWithBasmalah: Boolean = false,
        autoplay: Boolean = true,
    ) {
        val boundedRange = repeatRange
            ?.takeIf { preserveRepeatRange }
            ?.let { range ->
                val first = range.first.coerceIn(1, ayahCount)
                val last = range.last.coerceIn(first, ayahCount)
                first..last
            }
        repeatRange = boundedRange
        if (boundedRange == null) repeatEndPositionMs = null
        _state.value = _state.value.copy(repeatRange = boundedRange)

        val queue = recitationQueue(
            surah = Surah(surahId, "", surahName, "", "", ayahCount),
            reciter = reciter,
            startAyah = boundedRange?.let { startAyah.coerceIn(it.first, it.last) } ?: startAyah,
            includeBasmalahLeadIn = includeBasmalahLeadIn,
            startWithBasmalah = startWithBasmalah,
        )
        basmalahLeadIn = queue.hasBasmalahLeadIn

        // Latest load wins: abandon any in-flight connect/play from an earlier
        // chapter so rapid navigation cannot start the superseded surah first.
        val epoch = commands.invalidate()
        withController(epoch) { c ->
            val startPos = if (queue.startIndex == 0 && queue.hasBasmalahLeadIn) 0L
            else startPositionMs
            c.setMediaItems(queue.items, queue.startIndex, startPos)
            c.prepare()
            if (autoplay) c.play()
            if (boundedRange != null) startRepeatBoundaryMonitor()
        }
    }

    /**
     * Runs [block] on the main scope with a connected controller, serialised
     * with every other command. [epoch] is the gate snapshot at issue time —
     * if [PlayerCommandGate.invalidate] ran since then, the body is skipped.
     */
    private fun withController(
        epoch: Long = commands.epoch,
        block: suspend (MediaController) -> Unit,
    ) {
        scope.launch {
            commands.runIfCurrent(epoch) {
                val c = ensureController()
                // Re-check after connect: stop/playSurah may have invalidated
                // while we were awaiting the MediaController.
                if (!commands.isCurrent(epoch)) return@runIfCurrent
                block(c)
            }
        }
    }

    fun togglePlayPause() = withController { c ->
        if (c.isPlaying) c.pause() else c.play()
    }

    /** Pause if playing — used by one-shot word clips that must not resume. */
    fun pause() = withController { c ->
        if (c.isPlaying) c.pause()
    }

    /** Resume the loaded queue without changing its current position. */
    fun resume() = withController { it.play() }

    /**
     * Seeks to [ayah] at [positionMs] within it (when the ayah is in the
     * loaded playlist) and optionally starts playback. The four public
     * variants below are the combinations the UI actually uses.
     */
    private fun seekTo(ayah: Int, positionMs: Long, play: Boolean, playIfOutOfRange: Boolean = play) =
        withController { c ->
            val index = playlistIndex(ayah)
            val inRange = index in 0 until c.mediaItemCount
            if (inRange) c.seekTo(index, positionMs)
            if ((inRange && play) || (!inRange && playIfOutOfRange)) c.play()
        }

    fun seekToAyah(ayah: Int) = seekTo(ayah, 0L, play = false)

    /** Restart the chapter-opening basmalah clip (no-op when the playlist has none). */
    fun seekToBasmalah() = withController { c ->
        if (!basmalahLeadIn || c.mediaItemCount == 0) return@withController
        c.seekTo(0, 0L)
    }

    /** Seek to [ayah] in the already-loaded playlist and play — also resumes
     * when the index is out of range (e.g. a stale jump request). When seeking
     * to ayah 1 of a basmalah-preface surah, restarts from the basmalah clip. */
    fun playLoadedFromAyah(ayah: Int) = withController { c ->
        val startAtBasmalah = basmalahLeadIn && ayah == 1
        val index = if (startAtBasmalah) 0 else playlistIndex(ayah)
        if (index in 0 until c.mediaItemCount) c.seekTo(index, 0L)
        c.play()
    }

    fun seekToWord(ayah: Int, positionMs: Long) = seekTo(ayah, positionMs, play = false)

    fun seekToWordAndPlay(ayah: Int, positionMs: Long) = seekTo(ayah, positionMs, play = true, playIfOutOfRange = false)

    fun next() = withController { it.seekToNextMediaItem() }

    fun previous() = withController { it.seekToPreviousMediaItem() }

    fun setRepeatMode(mode: Int) = withController { it.repeatMode = mode }

    fun setSpeed(speed: Float) = withController { it.setPlaybackSpeed(speed) }

    /** Player-local gain; unlike device media volume this can mute a dev-lab
     * capture without changing the listener's system setting. */
    fun setVolume(volume: Float) = withController { it.volume = volume.coerceIn(0f, 1f) }

    /**
     * Stops playback and clears the queue. Invalidates every in-flight command
     * first so a [playSurah] still awaiting [MediaController] connection cannot
     * start audio after this returns.
     */
    fun stop() {
        resetRepeatState()
        basmalahLeadIn = false
        voiceEnergy.release()
        val epoch = commands.invalidate()
        scope.launch {
            commands.runIfCurrent(epoch) {
                // Do not connect solely to stop — only clear an existing session.
                controller?.let {
                    it.stop()
                    it.clearMediaItems()
                }
            }
        }
    }

    private fun playlistIndex(ayah: Int): Int =
        if (basmalahLeadIn) ayah else ayah - 1

    private fun startRepeatBoundaryMonitor() {
        if (repeatBoundaryJob?.isActive == true) return
        repeatBoundaryJob = scope.launch {
            while (true) {
                val c = controller ?: break
                val range = repeatRange ?: break
                if (c.mediaItemCount == 0) {
                    delay(REPEAT_BOUNDARY_POLL_MS)
                    continue
                }

                val firstIdx = playlistIndex(range.first).coerceIn(0, c.mediaItemCount - 1)
                val lastIdx = playlistIndex(range.last).coerceAtMost(c.mediaItemCount - 1)
                val idx = c.currentMediaItemIndex
                if (!c.isPlaying) {
                    delay(REPEAT_BOUNDARY_PAUSED_POLL_MS)
                    continue
                }

                if (idx > lastIdx) {
                    c.seekTo(firstIdx, 0L)
                    c.play()
                    syncFromController(c, forcePlaying = true)
                    delay(REPEAT_SEEK_SETTLE_MS)
                    continue
                }

                if (idx == lastIdx && c.currentPosition >= repeatBoundary(c)) {
                    endedLoopHandledIndex = idx
                    c.seekTo(firstIdx, 0L)
                    c.play()
                    syncFromController(c, forcePlaying = true)
                    delay(REPEAT_SEEK_SETTLE_MS)
                    continue
                }

                delay(REPEAT_BOUNDARY_POLL_MS)
            }
            repeatBoundaryJob = null
        }
    }

    private fun repeatBoundary(player: Player): Long {
        repeatEndPositionMs?.let { return it }
        val duration = player.duration.takeIf { it > 0L } ?: return Long.MAX_VALUE
        return (duration - REPEAT_DURATION_GUARD_MS).coerceAtLeast(0L)
    }

    companion object {
        private const val REPEAT_BOUNDARY_POLL_MS = 16L
        private const val REPEAT_BOUNDARY_PAUSED_POLL_MS = 250L
        private const val REPEAT_SEEK_SETTLE_MS = 120L
        private const val REPEAT_DURATION_GUARD_MS = 80L

        fun mediaId(surah: Int, ayah: Int, reciterId: Int) = "$surah:$ayah:$reciterId"

        fun parseMediaId(id: String): NowPlaying? {
            val parts = id.split(":")
            if (parts.size != 3) return null
            return NowPlaying(
                surahId = parts[0].toIntOrNull() ?: return null,
                ayah = parts[1].toIntOrNull() ?: return null,
                reciterId = parts[2].toIntOrNull() ?: return null,
            )
        }
    }
}

/**
 * Serialises player commands and lets a later [invalidate] abandon work that
 * was issued under an older epoch (play→stop during connect, rapid chapter
 * switches). Pure of Media3 so JVM tests can cover the contract without a
 * session service.
 */
internal class PlayerCommandGate {
    private val epochCounter = AtomicLong(0L)
    private val mutex = Mutex()

    /** Live epoch; safe to read from any thread. */
    val epoch: Long get() = epochCounter.get()

    /**
     * Bumps the epoch so every earlier snapshot becomes stale.
     * Atomic so concurrent UI + Assistant callers never share a generation.
     */
    fun invalidate(): Long = epochCounter.incrementAndGet()

    fun isCurrent(snapshot: Long): Boolean = snapshot == epochCounter.get()

    /**
     * Runs [block] only if [snapshot] is still the live epoch, holding the
     * mutex for the whole call so commands never interleave on the controller.
     * Returns whether the body ran.
     */
    suspend fun runIfCurrent(snapshot: Long, block: suspend () -> Unit): Boolean =
        mutex.withLock {
            if (snapshot != epochCounter.get()) return@withLock false
            block()
            true
        }
}
