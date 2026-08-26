package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.HighlightEngine
import com.beautifulquran.playback.NowPlaying
import com.beautifulquran.playback.PlaybackPositionEvents
import com.beautifulquran.playback.PlayerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ReaderPollingTest {

    @Test
    fun `resuming the same ayah restarts its polling flow`() {
        val nowPlaying = NowPlaying(surahId = 2, ayah = 5, reciterId = 7)
        val paused = PlayerUiState(nowPlaying = nowPlaying)
        val playing = paused.copy(isPlaying = true)

        val pausedIdentity = pollingIdentity(paused, loadedSurahId = 2) { it }
        val playingIdentity = pollingIdentity(playing, loadedSurahId = 2) { it }

        assertEquals(nowPlaying, pausedIdentity?.sampleKey)
        assertNotEquals(pausedIdentity, playingIdentity)
    }

    @Test
    fun `unrelated player state does not restart the polling flow`() {
        val playing = PlayerUiState(
            isPlaying = true,
            nowPlaying = NowPlaying(surahId = 2, ayah = 5, reciterId = 7),
        )

        assertEquals(
            pollingIdentity(playing, loadedSurahId = 2) { it },
            pollingIdentity(
                playing.copy(isBuffering = true, speed = 1.5f, error = "ignored"),
                loadedSurahId = 2,
            ) { it },
        )
    }

    @Test
    fun `confirmed position event restarts the polling flow`() {
        val playing = PlayerUiState(
            isPlaying = true,
            nowPlaying = NowPlaying(surahId = 2, ayah = 5, reciterId = 7),
            positionEvents = PlaybackPositionEvents(clockId = 4L),
        )

        assertNotEquals(
            pollingIdentity(playing, loadedSurahId = 2) { it },
            pollingIdentity(
                playing.copy(positionEvents = PlaybackPositionEvents(clockId = 5L)),
                loadedSurahId = 2,
            ) { it },
        )
    }

    @Test
    fun `polling ignores playback from another loaded surah`() {
        val state = PlayerUiState(
            isPlaying = true,
            nowPlaying = NowPlaying(surahId = 18, ayah = 1, reciterId = 7),
        )

        assertNull(pollingIdentity(state, loadedSurahId = 2) { it })
    }

    @Test
    fun `active word poll reuses its snapshot until a real ink boundary`() {
        val timings = HighlightEngine.PreparedTimings.prepare(
            listOf(
                Segment(position = 1, startMs = 0, endMs = 1_000),
                Segment(position = 2, startMs = 1_000, endMs = 2_000),
            ),
        )
        val cache = ActiveWordPollCache()

        val first = cache.activeWord(ayah = 5, timings.activeInfo(100), activation = 7)
        assertSame(first, cache.activeWord(ayah = 5, timings.activeInfo(900), activation = 7))
        assertNotSame(first, cache.activeWord(ayah = 5, timings.activeInfo(1_100), activation = 7))
        assertNotSame(first, cache.activeWord(ayah = 5, timings.activeInfo(100), activation = 8))
    }
}
