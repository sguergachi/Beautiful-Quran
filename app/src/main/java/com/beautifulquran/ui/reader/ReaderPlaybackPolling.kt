package com.beautifulquran.ui.reader

import com.beautifulquran.domain.HighlightPlaybackSample
import com.beautifulquran.playback.NowPlaying
import com.beautifulquran.playback.PlayerUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/** One coherent read shared by word, follow, and basmalah consumers. */
internal data class ReaderPlaybackPoll(val nowPlaying: NowPlaying, val playback: HighlightPlaybackSample)

/**
 * The reader's sole polling schedule. Share the result before deriving consumer
 * state; each transport/revision change cancels a paused sleep immediately.
 * Superseded jumps retain their cumulative clock/ink counters in PlayerUiState.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun readerPlaybackPolls(
    states: Flow<PlayerUiState>,
    loadedSurah: Flow<Int>,
    read: (NowPlaying) -> HighlightPlaybackSample?,
    tickMs: Long = 33L,
    pausedTickMs: Long = 250L,
    timingRevision: Flow<Long> = flowOf(0L),
): Flow<ReaderPlaybackPoll?> = combine(states, loadedSurah, timingRevision) { state, surah, revision ->
    pollingIdentity(state, surah) { it }?.let { it to revision }
}.distinctUntilChanged().flatMapLatest { request ->
    if (request == null) flowOf(null) else flow {
        val identity = request.first
        while (true) {
            val nowPlaying = identity.sampleKey
            read(nowPlaying)?.let { emit(ReaderPlaybackPoll(nowPlaying, it)) }
            delay(if (identity.isPlaying && !identity.isBuffering) tickMs else pausedTickMs)
        }
    }
}
