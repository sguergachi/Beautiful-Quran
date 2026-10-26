package com.beautifulquran.ui.reader

import com.beautifulquran.domain.HighlightItem
import com.beautifulquran.domain.HighlightPlaybackSample
import com.beautifulquran.playback.NowPlaying
import com.beautifulquran.playback.PlaybackPositionEvents
import com.beautifulquran.playback.PlayerUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderPlaybackPollingTest {
    private val item = NowPlaying(24, 33, 7)
    private fun sample(state: PlayerUiState, at: Long) = HighlightPlaybackSample(
        HighlightItem(item.surahId, item.ayah, item.reciterId), at, at * 1_000_000,
        state.isPlaying, state.isBuffering, state.speed, state.positionEvents.clockId, state.positionEvents.inkId,
    )

    @Test
    fun `multiple consumers share one read and the exact same packet`() = runTest {
        val state = MutableStateFlow(PlayerUiState(nowPlaying = item, isPlaying = true))
        var reads = 0
        val shared = readerPlaybackPolls(state, MutableStateFlow(24), {
            reads++; sample(state.value, testScheduler.currentTime)
        }).stateIn(backgroundScope, SharingStarted.WhileSubscribed(), null)
        val first = mutableListOf<ReaderPlaybackPoll?>()
        val second = mutableListOf<ReaderPlaybackPoll?>()
        backgroundScope.launch { shared.collect { first += it } }
        backgroundScope.launch { shared.collect { second += it } }
        runCurrent()
        advanceTimeBy(100); runCurrent()
        assertEquals(4, reads)
        assertEquals(first.size, second.size)
        first.zip(second).forEach { (a, b) -> assertSame(a, b) }
    }

    @Test
    fun `pause resume buffer speed and discontinuity interrupt a sleeping poll`() = runTest {
        val state = MutableStateFlow(PlayerUiState(nowPlaying = item))
        val samples = mutableListOf<ReaderPlaybackPoll?>()
        backgroundScope.launch {
            readerPlaybackPolls(state, MutableStateFlow(24), { sample(state.value, testScheduler.currentTime) })
                .collect { samples += it }
        }
        runCurrent()
        advanceTimeBy(50)
        state.value = state.value.copy(isPlaying = true); runCurrent()
        assertEquals(50L, samples.last()!!.playback.positionMs)
        state.value = state.value.copy(isBuffering = true); runCurrent()
        advanceTimeBy(20)
        state.value = state.value.copy(speed = 1.5f); runCurrent()
        assertEquals(70L, samples.last()!!.playback.positionMs)
        assertEquals(1.5f, samples.last()!!.playback.speed, 0f)
        advanceTimeBy(20)
        state.value = state.value.copy(positionEvents = PlaybackPositionEvents(clockId = 2, inkId = 1)); runCurrent()
        assertEquals(90L, samples.last()!!.playback.positionMs)
        assertEquals(2L, samples.last()!!.playback.clockRevision)
        state.value = state.value.copy(isBuffering = false); runCurrent()
        assertTrue(samples.last()!!.playback.advances)
    }

    @Test
    fun `incoherent read is skipped and timing publication samples immediately while paused`() = runTest {
        val state = MutableStateFlow(PlayerUiState(nowPlaying = item))
        val revision = MutableStateFlow(1L)
        val samples = mutableListOf<ReaderPlaybackPoll?>()
        var coherent = true
        backgroundScope.launch {
            readerPlaybackPolls(state, MutableStateFlow(24), {
                sample(state.value, testScheduler.currentTime).takeIf { coherent }
            }, timingRevision = revision).collect { samples += it }
        }
        runCurrent()
        coherent = false
        advanceTimeBy(250); runCurrent()
        assertEquals(1, samples.size)
        coherent = true
        revision.value++; runCurrent()
        assertEquals(2, samples.size)
        assertEquals(250L, samples.last()!!.playback.positionMs)
    }

    @Test
    fun `loaded chapter changes immediately clear a mismatched playback item`() = runTest {
        val state = MutableStateFlow(PlayerUiState(nowPlaying = item, isPlaying = true))
        val loaded = MutableStateFlow(24)
        val samples = mutableListOf<ReaderPlaybackPoll?>()
        backgroundScope.launch {
            readerPlaybackPolls(state, loaded, { sample(state.value, testScheduler.currentTime) })
                .collect { samples += it }
        }
        runCurrent()
        loaded.value = 25; runCurrent()
        assertNull(samples.last())
    }
}
