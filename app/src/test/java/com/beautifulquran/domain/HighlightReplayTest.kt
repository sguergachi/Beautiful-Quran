package com.beautifulquran.domain

import com.beautifulquran.data.model.Segment
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HighlightReplayTest {
    private val item = HighlightItem(24, 33, 7)
    private val timing = HighlightTimingContext(1,
        HighlightEngine.PreparedTimings.prepare(listOf(Segment(1, 0, 1000), Segment(2, 1100, 10_000))), 0)
    private val calibration = HighlightCalibration()
    private val header = HighlightTraceHeader("b94f4c88", false, "test")

    private fun sample(position: Long, atMs: Long) = HighlightPlaybackSample(item, position, atMs * 1_000_000, true)

    @Test
    fun `pinned traces compare the production baseline driver and candidate without broad exceptions`() {
        val root = Json.parseToJsonElement(javaClass.getResource("/highlight-sync-v2-traces.json")!!.readText()).jsonObject
        assertEquals(1, root.getValue("schemaVersion").jsonPrimitive.int)
        for (case in root.getValue("cases").jsonArray.map { it.jsonObject }) {
            val session = HighlightSession()
            val speed = case.getValue("speed").jsonPrimitive.float
            val name = case.getValue("name").jsonPrimitive.content
            val difference = case.getValue("difference").jsonPrimitive.content
            var different = false
            for (event in case.getValue("samples").jsonArray.map { it.jsonObject }) {
                val at = event.getValue("atMs").jsonPrimitive.long
                val raw = event.getValue("rawMs").jsonPrimitive.long
                val result = session.observe(sample(raw, at).copy(speed = speed), timing, calibration, 1)
                assertEquals("$name V1 at $at", event.getValue("v1Ms").jsonPrimitive.long, result.baselinePositionMs)
                assertEquals("$name V2 at $at", event.getValue("v2Ms").jsonPrimitive.long, result.candidate!!.inkMs)
                assertEquals(1, result.visibleEngineVersion)
                different = different || result.baselinePositionMs != result.candidate.inkMs
            }
            assertEquals("$name must justify its exact expected difference", difference != "none", different)
        }
    }

    @Test
    fun `mid performance capture restores V1 settle and provisional tap state exactly`() {
        val session = HighlightSession()
        // Capture starts during a held correction, not from an empty filter.
        session.observe(sample(0, 0), timing, calibration)
        session.observe(sample(90, 33), timing, calibration)
        session.observe(sample(180, 66), timing, calibration)
        val recorder = HighlightTraceRecorder(header)
        for ((i, raw) in listOf(270L, 40L, 100L, 133L).withIndex()) {
            if (i == 2) session.acceptNextSample()
            val input = sample(raw, (i + 3) * 33L)
            val result = session.observe(input, timing, calibration, 9)
            recorder.record(input, timing, calibration, result)
        }
        val decoded = Json.decodeFromString<HighlightTrace>(Json.encodeToString(recorder.snapshot()))
        assertNotNull(decoded.samples.first().baselineCheckpoint)
        replay(decoded)
    }

    @Test
    fun `shared Android web transport events have identical visible frames`() {
        val root = Json.parseToJsonElement(javaClass.getResource("/highlight-sync-v2-events.json")!!.readText()).jsonObject
        for (case in root.getValue("cases").jsonArray.map { it.jsonObject }) {
            val prepared = HighlightEngine.PreparedTimings.prepare(case.getValue("segments").jsonArray.map { row ->
                val values = row.jsonArray.map { it.jsonPrimitive.long }
                Segment(values[0].toInt(), values[1], values[2])
            })
            val knobs = HighlightCalibration(case["lag"]?.jsonPrimitive?.long ?: 0, case["lead"]?.jsonPrimitive?.long ?: 0)
            var state: HighlightSyncState? = null
            for (e in case.getValue("events").jsonArray.map { it.jsonObject }) {
                fun long(key: String, default: Long = 0) = e[key]?.jsonPrimitive?.long ?: default
                val at = long("at")
                val input = sample(long("raw"), at).copy(
                    isPlaying = e["playing"]?.jsonPrimitive?.boolean ?: true,
                    isBuffering = e["buffering"]?.jsonPrimitive?.boolean ?: false,
                    speed = e["speed"]?.jsonPrimitive?.float ?: 1f,
                    clockRevision = long("clock"), inkGeneration = long("generation"),
                )
                val context = HighlightTimingContext(long("revision", 1),
                    if (e["empty"]?.jsonPrimitive?.boolean == true) HighlightEngine.PreparedTimings.prepare(emptyList()) else prepared,
                    case["voice"]?.jsonPrimitive?.long ?: 0)
                state = HighlightSync.sample(state, input, context, knobs)
                val frame = HighlightSync.frame(state, long("frameAt", at) * 1_000_000)
                val label = "${case["name"]}/$at"
                assertEquals(label, long("ink"), frame.inkMs)
                assertEquals(label, e["word"]?.jsonPrimitive?.intOrNull, frame.active?.position)
                assertEquals(label, e["occurrence"]?.jsonPrimitive?.intOrNull, frame.occurrence)
                assertEquals(label, e.getValue("phase").jsonPrimitive.content, frame.phase.name)
            }
        }
    }

    @Test
    fun `timing replacement withdrawal and calibration changes replay without provider access`() {
        val session = HighlightSession()
        val recorder = HighlightTraceRecorder(header)
        val empty = HighlightTimingContext(2, HighlightEngine.PreparedTimings.prepare(emptyList()), 0)
        listOf(timing, empty, timing.copy(revision = 3)).forEachIndexed { i, table ->
            val input = sample(i * 33L, i * 33L)
            val knobs = HighlightCalibration(30, i * 10L)
            recorder.record(input, table, knobs, session.observe(input, table, knobs, 1))
        }
        val trace = recorder.snapshot()
        assertEquals(2, trace.timings.size)
        replay(Json.decodeFromString(Json.encodeToString(trace)))
    }

    @Test
    fun `capture storage is bounded and retains a replayable prefix with explicit truncation`() {
        val session = HighlightSession()
        val recorder = HighlightTraceRecorder(header, maxSamples = 2)
        repeat(5) {
            val input = sample(it * 33L, it * 33L)
            recorder.record(input, timing, calibration, session.observe(input, timing, calibration, 1))
        }
        val trace = recorder.snapshot()
        assertEquals(2, trace.samples.size)
        assertEquals(3, trace.droppedSamples)
        assertEquals(0L, trace.samples.first().rawMs)
        replay(trace)
    }

    @Test
    fun `log export keeps sample and frame evidence beyond the old short capture limits`() {
        val session = HighlightSession()
        val recorder = HighlightTraceRecorder(header, maxSamples = 6000, maxFrames = 24000)
        repeat(1250) { index ->
            val input = sample(index * 33L, index * 33L)
            val observation = session.observe(input, timing, calibration, 1)
            recorder.record(input, timing, calibration, observation)
            repeat(3) { frame -> recorder.recordFrame(observation.syncState!!, input.sampledAtNanos + frame * 10_000_000L) }
        }
        val trace = recorder.snapshot()
        assertEquals(1250, trace.samples.size)
        assertEquals(3750, trace.frames.size)
        assertEquals(0, trace.droppedSamples)
        assertEquals(0, trace.droppedFrames)
        val report = DiagnosticLog(listOf("word-tap", "reciter-selection"), trace).report(mapOf("device" to "test"))
        val decoded = Json.decodeFromString<HighlightTrace>(
            report.substringAfter("highlight-sync-trace-begin").substringBefore("highlight-sync-trace-end").trim(),
        )
        assertEquals(trace, decoded)
        replay(decoded)
    }

    @Test
    fun `frame storage limit remains explicit when a long log reaches its budget`() {
        val session = HighlightSession()
        val recorder = HighlightTraceRecorder(header, maxFrames = 2)
        val input = sample(0, 0)
        val observation = session.observe(input, timing, calibration, 1)
        recorder.record(input, timing, calibration, observation)
        repeat(5) { recorder.recordFrame(observation.syncState!!, it * 1_000_000L) }
        val trace = recorder.snapshot()
        assertEquals(2, trace.frames.size)
        assertEquals(3, trace.droppedFrames)
        replay(trace)
    }

    @Test
    fun `production capture explicitly serializes schema scope and active engine versions`() {
        val trace = HighlightTraceRecorder(header).snapshot()
        val json = Json.parseToJsonElement(trace.encodeForReplay()).jsonObject.getValue("header").jsonObject
        assertEquals(1, json.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(1, json.getValue("visibleEngineVersion").jsonPrimitive.int)
        assertEquals(2, json.getValue("candidateEngineVersion").jsonPrimitive.int)
        assertEquals("sample-clock", json.getValue("scope").jsonPrimitive.content)
    }

    @Test
    fun `shadow capture and its restart do not change visible V1 results`() {
        val visible = HighlightSession()
        val shadow = HighlightSession()
        repeat(90) {
            val raw = if (it < 60) it * 33L else 300L + (it - 60) * 33L
            val input = sample(raw, it * 33L)
            val left = visible.observe(input, timing, calibration)
            val right = shadow.observe(input, timing, calibration, if (it < 70) 1 else 2)
            assertEquals(left.baselinePositionMs, right.baselinePositionMs)
            assertSame(left.baselineActive, right.baselineActive)
            assertNull(left.candidate)
        }
    }

    @Test
    fun `a provisional tap armed before capture is part of the initial checkpoint`() {
        val session = HighlightSession()
        session.observe(sample(1500, 0), timing, calibration)
        session.acceptNextSample()
        val input = sample(900, 33)
        val result = session.observe(input, timing, calibration, 1)
        val recorder = HighlightTraceRecorder(header)
        recorder.record(input, timing, calibration, result)
        assertTrue(result.baselineCheckpoint!!.acceptNext)
        replay(recorder.snapshot())
    }

    @Test
    fun `V1 calibration changes rearm exactly while ordinary samples retain their jitter hold`() {
        val session = HighlightSession()
        assertEquals(1000L, session.observe(sample(1000, 0), timing, calibration).baselinePositionMs)
        assertEquals(1000L, session.observe(sample(900, 33), timing, calibration).baselinePositionMs)
        assertEquals(700L, session.observe(sample(900, 66), timing,
            HighlightCalibration(outputLagWallMs = 200)).baselinePositionMs)
        assertEquals(1100L, session.observe(sample(900, 99), timing,
            HighlightCalibration(wordLeadMs = 200)).baselinePositionMs)
    }

    @Test
    fun `V1 speed changes rearm only when their effective manual lag changes`() {
        val automatic = HighlightSession()
        automatic.observe(sample(1000, 0), timing, calibration)
        assertEquals(1000L, automatic.observe(sample(900, 33).copy(speed = 2f), timing, calibration).baselinePositionMs)
        val manual = HighlightSession()
        val knobs = HighlightCalibration(outputLagWallMs = 80)
        assertEquals(920L, manual.observe(sample(1000, 0), timing, knobs).baselinePositionMs)
        assertEquals(740L, manual.observe(sample(900, 33).copy(speed = 2f), timing, knobs).baselinePositionMs)
    }

    @Test
    fun `visible V2 keeps its live state when recording starts mid performance`() {
        val session = HighlightSession(visibleEngineVersion = 2)
        session.observe(sample(1000, 0), timing, calibration)
        session.observe(sample(900, 100), timing, calibration)
        val before = session.observe(sample(933, 133), timing, calibration)
        val recorder = HighlightTraceRecorder(header.copy(visibleEngineVersion = 2))
        for (i in 1..4) {
            val input = sample(933 + i * 33L, 133 + i * 33L)
            val result = session.observe(input, timing, calibration, 1)
            assertTrue(result.visiblePositionMs >= before.visiblePositionMs)
            recorder.record(input, timing, calibration, result)
        }
        replay(Json.decodeFromString(recorder.snapshot().encodeForReplay()))
    }

    @Test
    fun `frame capture references its actual sample and replays beyond the next poll`() {
        val session = HighlightSession(2)
        val recorder = HighlightTraceRecorder(header.copy(visibleEngineVersion = 2,
            schemaVersion = 2, scope = "sample-and-frame-clock"))
        val input = sample(0, 0)
        val observation = session.observe(input, timing, calibration, 1)
        recorder.record(input, timing, calibration, observation)
        for (hz in listOf(60, 90, 120)) repeat(hz) {
            recorder.recordFrame(observation.syncState!!, it * 1_000_000_000L / hz)
        }
        val trace = recorder.snapshot()
        assertEquals(270, trace.frames.size)
        assertTrue(trace.frames.any { it.inkMs > 500 })
        replay(Json.decodeFromString(trace.encodeForReplay()))
    }

    @Test
    fun `live session rebases after its loaded chapter stops being observed`() {
        val session = HighlightSession(2)
        session.observe(sample(500, 500), timing, calibration)
        session.suspendObservation(600_000_000)
        val resumed = session.observe(sample(900, 20000).copy(isPlaying = false), timing, calibration)
        assertEquals(900L, resumed.visiblePositionMs)
        assertEquals(0L, resumed.syncState!!.sample.inkGeneration)
    }

    @Test
    fun `explicit local developer capture replays through production filters`() {
        val path = System.getProperty("bq.highlightTrace") ?: return
        val text = File(path).readText()
        val json = if ("highlight-sync-trace-begin" in text) {
            text.substringAfter("highlight-sync-trace-begin").substringBefore("highlight-sync-trace-end").trim()
        } else text
        replay(Json.decodeFromString<HighlightTrace>(json))
    }

    private fun replay(trace: HighlightTrace) {
        assertTrue(trace.header.schemaVersion in 1..2)
        assertTrue(trace.header.scope in setOf("sample-clock", "sample-and-frame-clock"))
        assertTrue(trace.header.visibleEngineVersion in 1..2)
        assertEquals(2, trace.header.candidateEngineVersion)
        val tables = trace.timings.associate { table ->
            val bytes = ("${table.voiceStartMs}:" + table.triples.joinToString(",")).toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals("timing input fingerprint", table.digest, digest)
            assertEquals("timing triples must be complete", 0, table.triples.size % 3)
            table.digest to HighlightEngine.PreparedTimings.prepare(table.triples.chunked(3).map {
                Segment(it[0].toInt(), it[1], it[2])
            })
        }
        val baseline = HighlightClock()
        trace.samples.firstOrNull()?.let { first ->
            baseline.restore(first.baselineCheckpointItem, requireNotNull(first.baselineCheckpoint))
        }
        var candidate: HighlightSyncState? = null
        for ((sampleIndex, event) in trace.samples.withIndex()) {
            val input = HighlightPlaybackSample(
                HighlightItem(event.surah, event.ayah, event.reciter), event.rawMs, event.atNanos,
                event.playing, event.buffering, event.speed, event.clockRevision, event.inkGeneration,
            )
            val table = trace.timings.single { it.digest == event.timingDigest }
            val context = HighlightTimingContext(event.timingRevision, tables.getValue(table.digest), table.voiceStartMs)
            val knobs = HighlightCalibration(event.lagWallMs, event.wordLeadMs)
            if (event.baselineRearm) baseline.acceptNextSample()
            val v1 = baseline.sample(input.item, knobs.inkMs(input.positionMs, input.speed, context.voiceStartMs))
            assertEquals(event.baselineMs, v1)
            assertEquals(event.baselineWord, context.timings.activeInfo(v1)?.position)
            assertEquals(event.baselineOccurrenceStartMs, context.timings.activeInfo(v1)?.startMs)
            candidate = if (candidate == null && event.syncCheckpoint != null) {
                HighlightSync.restore(input, context, knobs, event.syncCheckpoint)
            } else HighlightSync.sample(candidate, input, context, knobs)
            val frame = HighlightSync.frame(candidate, event.atNanos)
            assertEquals(event.candidateMs, frame.inkMs)
            assertEquals(event.candidateOccurrence, frame.occurrence)
            assertEquals(event.candidateWord, frame.active?.position)
            assertEquals(event.candidatePhase, frame.phase.name)
            assertEquals(event.candidateProgress!!, frame.linearProgress, 0.000001f)
            for (recorded in trace.frames.filter { it.sampleIndex == sampleIndex }) {
                val rendered = HighlightSync.frame(candidate, recorded.atNanos)
                assertEquals(recorded.mediaMs, rendered.mediaMs)
                assertEquals(recorded.inkMs, rendered.inkMs)
                assertEquals(recorded.occurrence, rendered.occurrence)
                assertEquals(recorded.progress, rendered.linearProgress, 0.000001f)
            }
        }
    }
}
