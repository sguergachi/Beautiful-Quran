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

/** The timing table's owner travels with its revision, including empty withdrawals. */
internal data class ReaderTimingPublication(val revision: Long = 0L, val reciterId: Int? = null)

/** One coherent read shared by word, follow, and basmalah consumers. */
internal data class ReaderPlaybackPoll(
    val nowPlaying: NowPlaying,
    val playback: HighlightPlaybackSample,
    val timingPublication: ReaderTimingPublication,
)

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
    timingPublication: Flow<ReaderTimingPublication> = flowOf(ReaderTimingPublication()),
): Flow<ReaderPlaybackPoll?> = combine(states, loadedSurah, timingPublication) { state, surah, publication ->
    pollingIdentity(state, surah) { playing ->
        playing.takeIf { publication.reciterId == null || it.reciterId == publication.reciterId }
    }?.let { it to publication }
}.distinctUntilChanged().flatMapLatest { request ->
    if (request == null) flowOf(null) else flow {
        val identity = request.first
        while (true) {
            val nowPlaying = identity.sampleKey
            read(nowPlaying)?.let { emit(ReaderPlaybackPoll(nowPlaying, it, request.second)) }
            delay(if (identity.isPlaying && !identity.isBuffering) tickMs else pausedTickMs)
        }
    }
}
