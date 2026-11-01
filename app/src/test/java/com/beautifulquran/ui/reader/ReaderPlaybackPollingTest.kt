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
        val publication = MutableStateFlow(ReaderTimingPublication(1L, item.reciterId))
        val samples = mutableListOf<ReaderPlaybackPoll?>()
        var coherent = true
        backgroundScope.launch {
            readerPlaybackPolls(state, MutableStateFlow(24), {
                sample(state.value, testScheduler.currentTime).takeIf { coherent }
            }, timingPublication = publication).collect { samples += it }
        }
        runCurrent()
        coherent = false
        advanceTimeBy(250); runCurrent()
        assertEquals(1, samples.size)
        coherent = true
        publication.value = publication.value.copy(revision = 2L); runCurrent()
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

    @Test
    fun `new reciter timings cannot sample the previous voice before its playlist switches`() = runTest {
        val state = MutableStateFlow(PlayerUiState(nowPlaying = item, isPlaying = true))
        val publication = MutableStateFlow(ReaderTimingPublication(1L, item.reciterId))
        val samples = mutableListOf<ReaderPlaybackPoll?>()
        var reads = 0
        backgroundScope.launch {
            readerPlaybackPolls(state, MutableStateFlow(item.surahId), { expected ->
                reads++
                sample(state.value, testScheduler.currentTime).copy(
                    item = HighlightItem(expected.surahId, expected.ayah, expected.reciterId),
                )
            }, timingPublication = publication).collect { samples += it }
        }
        runCurrent()
        assertNotNull(samples.last())
        publication.value = ReaderTimingPublication(2L, 1)
        runCurrent()
        assertNull(samples.last())
        val readsBeforeSwitch = reads
        advanceTimeBy(300); runCurrent()
        assertEquals(readsBeforeSwitch, reads)
        state.value = state.value.copy(nowPlaying = item.copy(reciterId = 1))
        runCurrent()
        assertEquals(1, samples.last()!!.playback.item.reciter)
        assertEquals(publication.value, samples.last()!!.timingPublication)
    }

    @Test
    fun `paused reciter changes reject superseded audio and resume on the selected timings`() = runTest {
        val state = MutableStateFlow(PlayerUiState(nowPlaying = item))
        val publication = MutableStateFlow(ReaderTimingPublication(1L, item.reciterId))
        val samples = mutableListOf<ReaderPlaybackPoll?>()
        backgroundScope.launch {
            readerPlaybackPolls(state, MutableStateFlow(item.surahId), { expected ->
                sample(state.value, testScheduler.currentTime).copy(
                    item = HighlightItem(expected.surahId, expected.ayah, expected.reciterId),
                )
            }, timingPublication = publication).collect { samples += it }
        }
        runCurrent()
        val oldPacket = samples.last()!!
        advanceTimeBy(20)
        publication.value = ReaderTimingPublication(2L, 1); runCurrent()
        assertNull(samples.last())
        publication.value = ReaderTimingPublication(3L, 5); runCurrent()
        state.value = state.value.copy(nowPlaying = item.copy(reciterId = 1)); runCurrent()
        assertNull(samples.last())
        state.value = state.value.copy(nowPlaying = item.copy(reciterId = 5)); runCurrent()
        assertEquals(20L, samples.last()!!.playback.positionMs)
        assertEquals(publication.value, samples.last()!!.timingPublication)
        assertNotEquals(publication.value, oldPacket.timingPublication)

        // A refreshed (or withdrawn) table for the same voice must still wake a paused poll.
        advanceTimeBy(20)
        publication.value = publication.value.copy(revision = 4L); runCurrent()
        assertEquals(40L, samples.last()!!.playback.positionMs)
        assertEquals(4L, samples.last()!!.timingPublication.revision)
    }
}
