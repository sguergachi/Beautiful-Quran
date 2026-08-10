package com.beautifulquran.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class TarjiSyncClockTest {

    @Test
    fun `44100 Hz hop clock keeps its exact content duration`() {
        assertEquals(
            19.954648526,
            analysisHopContentMs(sourceSampleRate = 44_100, decimation = 5, hopSamples = 176),
            0.000000001,
        )
        assertEquals(
            20.0,
            analysisHopContentMs(sourceSampleRate = 48_000, decimation = 6, hopSamples = 160),
            0.0,
        )
    }

    @Test
    fun `backlog anchor starts from filled content and follows only clock drift`() {
        assertEquals(false, TarjiBacklogAnchor.isReady(251.9, sinkLatencyMs = 252, speed = 1f))
        assertEquals(true, TarjiBacklogAnchor.isReady(252.0, sinkLatencyMs = 252, speed = 1f))
        assertEquals(false, TarjiBacklogAnchor.isReady(0.0, sinkLatencyMs = 0, speed = 1f))

        val filling = TarjiBacklogAnchor.capture(
            tapContentMs = 40.0,
            playbackContentMs = 0,
            sinkLatencyMs = 252,
            speed = 1f,
        )
        assertEquals(40.0, filling.backlogContentMs, 0.0)
        assertEquals(120.0, filling.estimate(tapContentMs = 120.0, playbackContentMs = 0), 0.0)

        val filled = TarjiBacklogAnchor.capture(
            tapContentMs = 500.0,
            playbackContentMs = 248,
            sinkLatencyMs = 252,
            speed = 1f,
        )
        assertEquals(252.0, filled.backlogContentMs, 0.0)
        assertEquals(252.0, filled.estimate(tapContentMs = 700.0, playbackContentMs = 448), 0.0)
    }

    @Test
    fun `sink baseline converts wall time to content time once`() {
        val anchor = TarjiBacklogAnchor.capture(
            tapContentMs = 300.0,
            playbackContentMs = 100,
            sinkLatencyMs = 100,
            speed = 1.5f,
        )

        assertEquals(150.0, anchor.backlogContentMs, 0.0)
        assertEquals(150.0, anchor.estimate(tapContentMs = 450.0, playbackContentMs = 250), 0.0)
    }

    @Test
    fun `tap event start maps back to the media item clock`() {
        assertEquals(
            58_220L,
            mapTapContentToMediaMs(
                playbackPositionMs = 57_970L,
                tapContentMs = 58_220.0,
                eventStartContentMs = 58_220.0,
                backlogContentMs = 250.0,
            ),
        )
        assertEquals(
            58_300L,
            mapTapContentToMediaMs(
                playbackPositionMs = 57_970L,
                tapContentMs = 58_220.0,
                eventStartContentMs = 58_300.0,
                backlogContentMs = 250.0,
            ),
        )
    }

    @Test
    fun `sonic content latency is only added away from unity speed`() {
        assertEquals(0f, sonicContentLatencyMs(1f), 0f)
        assertEquals(Tarji.SONIC_LATENCY_MS, sonicContentLatencyMs(0.75f), 0f)
        assertEquals(Tarji.SONIC_LATENCY_MS, sonicContentLatencyMs(1.25f), 0f)
    }
}
