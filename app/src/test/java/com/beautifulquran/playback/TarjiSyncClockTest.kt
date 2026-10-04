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

    // ── Frame-time read-out ───────────────────────────────────────────────

    private val hopMs = 20.0

    /** A held note at level 0.1 whose loudness ripples [depth] deep at 5 Hz,
     * riding a slow swell — the 20 ms RMS of hop [hop]. */
    private fun voiceRms(hop: Int, depth: Float = 0.1f, swell: Float = 0.3f): Float {
        val seconds = (hop + 0.5) * hopMs / 1000.0
        return (0.1 * (1 + swell * kotlin.math.sin(2 * Math.PI * 0.7 * seconds)) *
            (1 + depth * kotlin.math.sin(2 * Math.PI * 5.0 * seconds))).toFloat()
    }

    private fun ripple(contentMs: Double): Float =
        kotlin.math.sin(2 * Math.PI * 5.0 * contentMs / 1000.0).toFloat()

    private fun TarjiEarTrack.publishVoice(
        hop: Int,
        rms: Float = voiceRms(hop),
        gain: Float = 1f,
        eventStartHop: Int = -1,
    ) = publish(hop, rms, pitchHz = 130f, pitchLeadHops = 0.8f, rateHz = 5f,
        usesAmplitude = true, gain = gain, eventStartHop = eventStartHop)

    @Test
    fun `ear pulse is the voice's ripple, in phase, whatever its depth`() {
        val period = TarjiEarPulse.periodHops(5f, hopMs)
        assertEquals(10, period)
        for (depth in listOf(0.04f, 0.1f, 0.3f)) {
            val series = FloatArray(200) { voiceRms(it, depth) }
            val pulse = FloatArray(200) {
                if (it !in 40..150) 0f
                else TarjiEarPulse.at(series, series.size, it, 0, 199, period, TarjiEarPulse.AMPLITUDE_FLOOR)
            }
            /** Correlation with the ripple as heard [lagHops] later. */
            fun match(lagHops: Int): Float {
                var dot = 0f; var a = 0f; var b = 0f
                for (hop in 40..150) {
                    val heard = ripple((hop + lagHops + 0.5) * hopMs)
                    dot += pulse[hop] * heard; a += pulse[hop] * pulse[hop]; b += heard * heard
                }
                return dot / kotlin.math.sqrt(a * b)
            }
            // Zero phase: the pulse of a hop is the ripple of that same hop —
            // a hop either way already matches worse.
            assertEquals("depth $depth at the ear", true, match(0) > 0.93f)
            assertEquals("depth $depth is neither early nor late", true,
                match(0) > match(1) && match(0) > match(-1))
            // And it swings fully whatever the ripple's depth.
            val reach = (40..150).maxOf { kotlin.math.abs(pulse[it]) }
            assertEquals("depth $depth reach $reach", true, reach in 0.85f..1.5f)
        }
        // On a deep ripple the swell barely colours it.
        val deep = FloatArray(200) { voiceRms(it, 0.3f) }
        for (hop in 40..150) {
            assertEquals(ripple((hop + 0.5) * hopMs),
                TarjiEarPulse.at(deep, 200, hop, 0, 199, period, TarjiEarPulse.AMPLITUDE_FLOOR), 0.25f)
        }
    }

    @Test
    fun `ear pulse leaves a steady note and a bare swell alone`() {
        val period = TarjiEarPulse.periodHops(5f, hopMs)
        val steady = FloatArray(200) { 0.1f }
        val swell = FloatArray(200) { voiceRms(it, depth = 0f) }
        val breath = FloatArray(200) { 0.1f * (1f + 0.004f * if (it % 2 == 0) 1f else -1f) }
        for (hop in 40..150) {
            val floor = TarjiEarPulse.AMPLITUDE_FLOOR
            assertEquals(0f, TarjiEarPulse.at(steady, 200, hop, 0, 199, period, floor), 1e-4f)
            // A slow, deep swell is the level; what is left of it stays
            // under half a swing instead of a second-long flare.
            assertEquals(0f, TarjiEarPulse.at(swell, 200, hop, 0, 199, period, floor), 0.5f)
            // Flutter under the floor is not blown up into a flicker.
            assertEquals(0f, TarjiEarPulse.at(breath, 200, hop, 0, 199, period, floor), 0.2f)
        }
    }

    @Test
    fun `ear pulse period follows the detected rate within the tap's lead`() {
        assertEquals(6, TarjiEarPulse.periodHops(8f, hopMs))
        assertEquals(12, TarjiEarPulse.periodHops(4.17f, hopMs))
        assertEquals(16, TarjiEarPulse.periodHops(3.1f, hopMs))
        assertEquals(16, TarjiEarPulse.periodHops(1.6f, hopMs))
        assertEquals(6, TarjiEarPulse.periodHops(10f, hopMs))
        assertEquals(10, TarjiEarPulse.periodHops(0f, hopMs))
        // Half a period ahead, and one hop for the blur: under a sink buffer.
        assertEquals(9, TarjiEarPulse.lookaheadHops(16))
    }

    @Test
    fun `ear track interpolates the detector's decisions and clamps to what it holds`() {
        val track = TarjiEarTrack()
        val out = TarjiEarSample()
        assertEquals(false, track.read(100.0, hopMs, out))
        track.publishVoice(hop = 0, gain = 0f)
        track.publishVoice(hop = 1, gain = 0.5f, eventStartHop = 2)
        track.publishVoice(hop = 2, gain = 1f, eventStartHop = 2)
        // Hop 1 ends at 40 ms of content, hop 2 at 60 ms.
        assertEquals(true, track.read(40.0, hopMs, out))
        assertEquals(0.5f, out.gain, 1e-6f)
        assertEquals(2, out.eventStartHop)
        track.read(50.0, hopMs, out)
        assertEquals(0.75f, out.gain, 1e-6f)
        // The event opens where the read-out crosses the midpoint into it.
        track.read(29.0, hopMs, out)
        assertEquals(false, out.reverberating)
        assertEquals(-1, out.eventStartHop)
        track.read(31.0, hopMs, out)
        assertEquals(true, out.reverberating)
        // Past either end it holds the nearest hop rather than inventing one.
        track.read(500.0, hopMs, out)
        assertEquals(1f, out.gain, 1e-6f)
        track.read(-500.0, hopMs, out)
        assertEquals(0f, out.gain, 1e-6f)
        track.clear()
        assertEquals(false, track.read(40.0, hopMs, out))
    }

    @Test
    fun `ear track keeps reading across its ring wrap`() {
        val track = TarjiEarTrack()
        val out = TarjiEarSample()
        for (hop in 0 until 400) track.publishVoice(hop, gain = hop / 400f)
        track.read(390 * hopMs, hopMs, out)
        assertEquals(389 / 400f, out.gain, 1e-4f)
        assertEquals(ripple(390 * hopMs), out.tremolo, 0.25f)
    }

    @Test
    fun `the light reads the playback head, not the burst that fed the tap`() {
        // A phone: the sink holds 250 ms and the tap is fed 160 ms at a time.
        val clock = TarjiEarClock()
        val track = TarjiEarTrack()
        val out = TarjiEarSample()
        var hops = 0
        fun feedUpTo(contentMs: Double) {
            while ((hops + 1) * hopMs <= contentMs) {
                track.publishVoice(hops)
                hops++
            }
        }
        val backlogMs = 250.0
        var worst = 0f
        var moved = 0
        var frames = 0
        var last = Float.NaN
        // Wall ms t: playback sits at media 12 270 + t; the tap is a full
        // sink ahead, rounded up to its last 160 ms burst.
        for (frame in 0..360) { // 3 s at 120 Hz
            val wallMs = frame * 1000.0 / 120
            val burstMs = kotlin.math.floor(wallMs / 160.0) * 160.0
            feedUpTo(burstMs + backlogMs + 160.0)
            if (frame % 4 == 0) { // the reader's 33 ms position tick
                clock.onPosition(
                    positionMs = (12_270 + wallMs).toLong(),
                    wallNanos = (wallMs * 1e6).toLong(),
                    tapContentMs = hops * hopMs,
                    sinkLatencyMs = 250,
                    speed = 1f,
                )
            }
            assertEquals(true, clock.isAnchored)
            val earMs = clock.contentAtEarMs((wallMs * 1e6).toLong())
            track.read(earMs, hopMs, out)
            if (wallMs < 800) continue
            // The first tick found the tap 400 ms in with a full 250 ms sink
            // behind it, so content at the ear leads the wall by 150 ms.
            assertEquals(wallMs + 150.0, earMs, 1.0)
            worst = maxOf(worst, kotlin.math.abs(out.tremolo - ripple(earMs)))
            frames++
            if (out.tremolo != last) moved++
            last = out.tremolo
        }
        assertEquals("the light strays from the voice", 0f, worst, 0.3f)
        // And it moves with the frames (all but the flat tops between two
        // equal hops); the burst-bound mirror moved on one frame in nineteen.
        assertEquals(true, moved > frames * 4 / 5)
    }

    @Test
    fun `ear clock holds through a stall and carries its offset across a handoff`() {
        val clock = TarjiEarClock()
        fun tick(positionMs: Long, wallMs: Long, tapMs: Double) = clock.onPosition(
            positionMs, wallMs * 1_000_000L, tapMs, sinkLatencyMs = 250, speed = 1f)

        tick(positionMs = 0, wallMs = 0, tapMs = 100.0)
        assertEquals(false, clock.isAnchored)
        assertEquals(true, clock.contentAtEarMs(0L).isNaN())
        tick(positionMs = 50, wallMs = 50, tapMs = 300.0) // the sink has filled
        assertEquals(50.0, clock.contentAtEarMs(50_000_000L), 1e-9)
        // Between ticks it runs with the wall clock…
        assertEquals(70.0, clock.contentAtEarMs(70_000_000L), 1e-9)
        // …but a tick that never comes (pause) cannot run it on.
        assertEquals(150.0, clock.contentAtEarMs(5_000_000_000L), 1e-9)
        // A stall: the wall moves, the head does not, and neither does the ear.
        tick(positionMs = 400, wallMs = 400, tapMs = 650.0)
        tick(positionMs = 400, wallMs = 500, tapMs = 650.0)
        assertEquals(400.0, clock.contentAtEarMs(500_000_000L), 1e-9)
        // The event that began at content 300 ms is heard at media 300 ms.
        assertEquals(300.0, clock.mediaMsOfContent(300.0), 1e-9)

        // Gapless handoff: the next ayah's position restarts, the PCM runs on.
        tick(positionMs = 6_000, wallMs = 6_100, tapMs = 6_250.0)
        tick(positionMs = 3, wallMs = 6_133, tapMs = 6_410.0)
        assertEquals(6_033.0, clock.contentAtEarMs(6_133_000_000L), 1e-9)
        assertEquals(6_053.0, clock.contentAtEarMs(6_153_000_000L), 1e-9)
        // Content 6 100 ms now sits 67 ms into the new item.
        assertEquals(70.0, clock.mediaMsOfContent(6_100.0), 1e-9)

        // A speed change re-anchors against the newly scaled sink.
        clock.onPosition(1_000, 7_000_000_000L, 7_500.0, sinkLatencyMs = 250, speed = 1.5f)
        assertEquals(1_000.0 - (1_000.0 + 375.0 - 7_500.0), clock.contentAtEarMs(7_000_000_000L), 1e-9)
        clock.reset()
        assertEquals(false, clock.isAnchored)
    }
}
