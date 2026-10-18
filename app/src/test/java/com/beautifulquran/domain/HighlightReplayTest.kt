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
    fun `explicit local developer capture replays through production filters`() {
        val path = System.getProperty("bq.highlightTrace") ?: return
        val text = File(path).readText()
        val json = if ("highlight-sync-trace-begin" in text) {
            text.substringAfter("highlight-sync-trace-begin").substringBefore("highlight-sync-trace-end").trim()
        } else text
        replay(Json.decodeFromString<HighlightTrace>(json))
    }

    private fun replay(trace: HighlightTrace) {
        assertEquals(1, trace.header.schemaVersion)
        assertEquals("sample-clock", trace.header.scope)
        assertEquals(1, trace.header.visibleEngineVersion)
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
        for (event in trace.samples) {
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
            candidate = HighlightSync.sample(candidate, input, context, knobs)
            val frame = HighlightSync.frame(candidate, event.atNanos)
            assertEquals(event.candidateMs, frame.inkMs)
            assertEquals(event.candidateOccurrence, frame.occurrence)
            assertEquals(event.candidateWord, frame.active?.position)
            assertEquals(event.candidatePhase, frame.phase.name)
            assertEquals(event.candidateProgress!!, frame.linearProgress, 0.000001f)
        }
    }
}
