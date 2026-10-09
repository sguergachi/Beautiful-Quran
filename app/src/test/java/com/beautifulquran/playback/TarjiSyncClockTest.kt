package com.beautifulquran.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToLong

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
    fun `sink timestamps carry in content time and hold during pause`() {
        val sink = TarjiSinkPosition(rendererMs = 10_000.0, wallNanos = 1_000_000_000L)
        for (speed in listOf(0.75f, 1f, 1.25f, 1.5f)) {
            assertEquals(10_000.0 + 33 * speed, sink.at(1_033_000_000L, speed, true), 1e-6)
            assertEquals(10_000.0, sink.at(2_000_000_000L, speed, false), 0.0)
            assertEquals(10_000.0 + 132 * speed, sink.at(1_132_000_000L, speed, true), 1e-6)
            assertEquals(10_000.0 + 1000 * speed, sink.at(2_000_000_000L, speed, true), 1e-6)
        }
    }

    @Test
    fun `an optimistic resume cannot advance a paused sink snapshot through the pause`() {
        val paused = TarjiSinkPosition(500.0, 1_000_000_000L, playing = false)
        assertEquals(500.0, paused.at(11_000_000_000L, 1f, playing = true), 0.0)
        val resumed = TarjiSinkPosition(500.0, 11_000_000_000L, playing = true)
        assertEquals(533.0, resumed.at(11_033_000_000L, 1f, playing = true), 1e-6)
    }

    // ── Frame-time read-out ───────────────────────────────────────────────

    @Test
    fun `queued audio survives the stale feed guard but pause and drain close it`() {
        assertEquals(true, hasAudiblePcm(800L, queuedContentMs = 200.0, playing = true))
        assertEquals(false, hasAudiblePcm(800L, queuedContentMs = 0.0, playing = true))
        assertEquals(false, hasAudiblePcm(0L, queuedContentMs = 1000.0, playing = false))
        assertEquals(false, hasAudiblePcm(800L, queuedContentMs = -1.0, playing = true))
        assertEquals(true, hasAudiblePcm(349L, queuedContentMs = 0.0, playing = true))
        assertEquals(false, hasAudiblePcm(350L, queuedContentMs = 0.0, playing = true))
    }

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
                    contentAtHeadMs = wallMs,
                    speed = 1f,
                    playing = true,
                )
            }
            assertEquals(true, clock.isAnchored)
            val earMs = clock.contentAtEarMs((wallMs * 1e6).toLong())
            track.read(earMs, hopMs, out)
            if (wallMs < 800) continue
            // A 400 ms burst against a 250 ms buffer must NOT place the
            // voice 150 ms early. The source PTS and presentation clock win.
            assertEquals(wallMs, earMs, 1e-6)
            worst = maxOf(worst, kotlin.math.abs(out.tremolo - ripple(wallMs)))
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
    fun `ear clock maps source timestamps through pause seek speed and gapless handoff`() {
        val clock = TarjiEarClock()
        assertEquals(true, clock.positionAt(0).isNaN())
        fun tick(media: Long, wall: Long, content: Double, speed: Float = 1f, playing: Boolean = true) =
            clock.onPosition(media, wall * 1_000_000L, content, speed, playing)

        // The first decoded PCM frame was at media 12 270, despite a late
        // first UI tick and 400 ms of PCM being decoded already.
        tick(12_320, 50, 50.0)
        assertEquals(70.0, clock.contentAtEarMs(70_000_000L), 1e-9)
        assertEquals(12_570.0, clock.mediaMsOfContent(300.0), 1e-9)
        tick(12_670, 400, 400.0, playing = false)
        assertEquals(400.0, clock.contentAtEarMs(5_000_000_000L), 1e-9)

        // The sink timestamp supplies the exact next-item boundary; no
        // extrapolated previous-item time invents the source offset.
        tick(3, 6_133, 6_003.0)
        assertEquals(6_003.0, clock.contentAtEarMs(6_133_000_000L), 1e-9)
        assertEquals(100.0, clock.mediaMsOfContent(6_100.0), 1e-9)
        tick(1_000, 7_000, 7_000.0, speed = 1.5f)
        assertEquals(7_030.0, clock.contentAtEarMs(7_020_000_000L), 1e-9)
        // A seek starts a new source session on its actual decoded PTS.
        clock.reset()
        tick(17_340, 8_000, 10.0)
        assertEquals(17_330.0, clock.mediaMsOfContent(0.0), 1e-9)
    }

    @Test
    fun `manual delay shifts graph and pulse together without moving event ownership`() {
        val track = TarjiEarTrack()
        for (hop in 0..199) track.publishVoice(hop, gain = hop / 200f, eventStartHop = 10)
        val clock = TarjiEarClock()
        val out = TarjiEarSample()
        val expected = TarjiEarSample()
        for (speed in listOf(0.75f, 1f, 1.25f, 1.5f)) {
            clock.onPosition(12_500, 0L, 500.0, speed, true)
            for (lag in listOf(0.0, 80.0, 180.0, 250.0)) {
                val content = 500.0 - lag * speed
                assertEquals(true, clock.sampleAtEar(0L, track, hopMs, out, lag, 0f, 0f, true))
                track.read(content, hopMs, expected)
                assertEquals((12_500 - lag * speed).roundToLong(), out.mediaMs)
                assertEquals(expected.tremolo, out.tremolo, 1e-6f)
                assertEquals(expected.gain, out.gain, 1e-6f)
                assertEquals(12_200L, out.eventStartMediaMs)
            }
            // Reading ahead for paint never moves the graph or event's timestamp.
            clock.sampleAtEar(0L, track, hopMs, out, 180.0, 37f, 16f, true)
            assertEquals((12_500 - 180 * speed + 16 * speed).roundToLong(), out.mediaMs)
            assertEquals(12_200L, out.eventStartMediaMs)
        }
    }

    @Test
    fun `graph follows the paused presentation clock when history is unavailable`() {
        val clock = TarjiEarClock()
        val out = TarjiEarSample()
        clock.onPosition(1_000, 0L, Double.NaN, 1f, false)
        assertEquals(false, clock.sampleAtEar(2_000_000_000L, TarjiEarTrack(), hopMs, out, 180.0, 0f, 16f, false))
        assertEquals(820L, out.mediaMs)
        assertEquals(0f, out.gain, 0f)
        assertEquals(Long.MIN_VALUE, out.eventStartMediaMs)
    }

    @Test
    fun `mode generations filter both interpolation endpoints without clearing the voice`() {
        val track = TarjiEarTrack()
        for (hop in 0 until 80) track.publish(hop, voiceRms(hop), 130f, 0.8f, 5f, true,
            gain = 0.8f, eventStartHop = 20, generation = 3)
        val unfiltered = TarjiEarSample()
        val filtered = TarjiEarSample()
        track.read(1_000.0, hopMs, unfiltered)
        track.read(1_000.0, hopMs, filtered, generation = 4)
        assertEquals(0f, filtered.gain, 0f)
        assertEquals(false, filtered.reverberating)
        assertEquals(-1, filtered.eventStartHop)
        assertEquals(unfiltered.tremolo, filtered.tremolo, 0f)

        track.publish(80, voiceRms(80), 130f, 0.8f, 5f, true,
            gain = 1f, eventStartHop = 81, generation = 4)
        track.read(1_605.0, hopMs, filtered, generation = 4)
        assertEquals(0.25f, filtered.gain, 0f)
        assertEquals(false, filtered.reverberating)
        track.read(1_615.0, hopMs, filtered, generation = 4)
        assertEquals(0.75f, filtered.gain, 0f)
        assertEquals(81, filtered.eventStartHop)

        track.publish(81, voiceRms(81), 130f, 0.8f, 5f, true,
            gain = 0.8f, eventStartHop = 20, generation = 3)
        track.read(1_625.0, hopMs, filtered, generation = 4)
        assertEquals(0.75f, filtered.gain, 0f)
        assertEquals(81, filtered.eventStartHop)
        track.read(1_635.0, hopMs, filtered, generation = 4)
        assertEquals(0.25f, filtered.gain, 0f)
        assertEquals(false, filtered.reverberating)
    }

    @Test
    fun `raw phase and media cursor use the same ear trims through mode switch and seek`() {
        val track = TarjiEarTrack()
        for (hop in 0 until 200) track.publish(hop, voiceRms(hop), 130f, 0.8f, 5f, true,
            gain = 1f, eventStartHop = 10, generation = 2)
        val clock = TarjiEarClock()
        val selected = TarjiEarSample()
        val raw = TarjiEarSample()
        val expected = TarjiEarSample()
        for (speed in listOf(0.75f, 1f, 1.5f)) {
            clock.onPosition(12_500, 0L, 500.0, speed, true)
            clock.sampleAtEar(0L, track, hopMs, selected, 80.0, 37f, 8f, true, generation = 3)
            assertEquals(0f, selected.gain, 0f)
            assertEquals(true, clock.sampleAtEar(0L, track, hopMs, raw, 80.0, 37f, 8f, true,
                rawPulseRateHz = 5f, rawUsesAmplitude = true))
            track.readPulse(500.0 - 80 * speed + 8 * speed + 37, hopMs, expected, 5f, true)
            assertEquals(selected.mediaMs, raw.mediaMs)
            assertEquals(expected.tremolo, raw.tremolo, 0f)
            assertEquals(Long.MIN_VALUE, raw.eventStartMediaMs)
        }
        track.clear()
        clock.reset()
        clock.onPosition(17_340, 0L, 10.0, 1f, true)
        assertEquals(false, clock.sampleAtEar(0L, track, hopMs, raw, 0.0, 0f, 0f, true,
            rawPulseRateHz = 5f))
        assertEquals(17_340L, raw.mediaMs)
        assertEquals(0f, raw.tremolo, 0f)
    }
}
