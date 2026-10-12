package com.beautifulquran.data

import java.security.MessageDigest
import com.beautifulquran.data.model.Segment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeTimingSnapshotTest {
    private val counts = mapOf("1:1" to 2)

    @Test
    fun `repeated span and same-word occurrences retain their exact order and clock`() {
        val rows = Json.parseToJsonElement("[[1,1,[[1,100,200],[1,220,280],[2,300,400],[1,500,600],[2,700,800]],80]]").jsonArray
        val snapshot = parseRuntimeTimingSnapshot(timingTestPayload(rows = rows), 1, counts)
        val segments = QuranRepository.parseSegments(snapshot.rows.single().segments)
        assertEquals(listOf(1, 1, 2, 1, 2), segments.map { it.position })
        assertEquals(listOf(100L, 220L, 300L, 500L, 700L), segments.map { it.startMs })
        assertEquals(80L, snapshot.rows.single().audioOnsetMs)
    }

    @Test
    fun `withheld verses complete coverage without inventing a fallback`() {
        val withheld = JsonArray(listOf(JsonPrimitive("37:152")))
        val snapshot = parseRuntimeTimingSnapshot(
            timingTestPayload(withheld = withheld), 1, counts + ("37:152" to 4),
        )
        assertEquals(listOf(1 to 1), snapshot.rows.map { it.surahId to it.ayahNumber })
    }

    @Test
    fun `unfamiliar reviewed source and normalizer fingerprints are accepted as provenance`() {
        val root = Json.parseToJsonElement(timingTestPayload()).jsonObject
        val changed = JsonObject(root + mapOf(
            "sourceSha256" to JsonPrimitive("b".repeat(64)),
            "normalizerSha256" to JsonPrimitive("c".repeat(64)),
        ))
        assertEquals(1, parseRuntimeTimingSnapshot(changed.toString(), 1, counts).rows.size)
    }

    @Test
    fun `fingerprint tampering is rejected even with complete word coverage`() {
        val root = Json.parseToJsonElement(timingTestPayload()).jsonObject
        reject(JsonObject(root + ("revision" to JsonPrimitive("0".repeat(64)))).toString())
    }

    @Test
    fun `wrong provider recording clock and schema cannot replace a valid cache`() {
        val root = Json.parseToJsonElement(timingTestPayload()).jsonObject
        mapOf(
            "schemaVersion" to JsonPrimitive(2),
            "reciterId" to JsonPrimitive(2),
            "qfReciterId" to JsonPrimitive(6),
            "audioSlug" to JsonPrimitive("another-recording"),
            "clock" to JsonPrimitive("chapter-ms"),
            "sourceSha256" to JsonPrimitive("not-a-digest"),
            "sourceSyncSequence" to JsonPrimitive(-1),
            "sourceSyncedAtMs" to JsonPrimitive(0),
        ).forEach { (field, value) -> reject(JsonObject(root + (field to value)).toString()) }
    }

    @Test
    fun `missing reordered duplicate and overlapping verse owners fail closed`() {
        val row = Json.parseToJsonElement(timingTestPayload()).jsonObject.getValue("rows").jsonArray.single()
        reject(timingTestPayload(rows = JsonArray(listOf(row, row))))
        reject(timingTestPayload(), counts + ("1:2" to 1))
        reject(timingTestPayload(withheld = JsonArray(listOf(JsonPrimitive("1:1")))))
        reject(timingTestPayload(withheld = JsonArray(listOf(JsonPrimitive("01:2")))), counts + ("1:2" to 1))
        val reverse = Json.parseToJsonElement("[[1,2,[[1,0,100]],0],[1,1,[[1,0,100],[2,100,200]],0]]").jsonArray
        reject(timingTestPayload(rows = reverse), counts + ("1:2" to 1))
    }

    @Test
    fun `incomplete positions fractional integers inverted spans and onset violations are rejected`() {
        listOf(
            "[[1,1,[[1,100,200]],0]]",
            "[[1,1,[[1,100,200],[3,200,300]],0]]",
            "[[1,1,[[1,100,200],[2,150,300]],0]]",
            "[[1,1,[[1,100,200],[2,200,200]],0]]",
            "[[1,1,[[1,100,200],[2,200,300]],101]]",
            "[[1,1,[[1.0,100,200],[2,200,300]],0]]",
            "[[1,1,[[1,\"100\",200],[2,200,300]],0]]",
            "[[1,1,[[1,100,200],[2,200,300]],-1]]",
        ).forEach { reject(timingTestPayload(rows = Json.parseToJsonElement(it).jsonArray)) }
    }

    @Test
    fun `a first-fill or revocation overtaking an awaited timing read cannot return the old chapter`() = runTest {
        var generation = 0L
        var reads = 0
        val blocked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = mapOf(1 to listOf(Segment(1, 100, 200)))
        val current = emptyMap<Int, List<Segment>>()
        val pending = async {
            readStableTimingGeneration({ generation }) {
                reads++
                if (reads == 1) {
                    blocked.complete(Unit)
                    release.await()
                    old
                } else current
            }
        }
        runCurrent()
        assertTrue(blocked.isCompleted)
        generation++
        release.complete(Unit)
        val accepted = pending.await()
        assertEquals(2, reads)
        assertEquals(1L, accepted.generation)
        assertEquals(current, accepted.segments)
    }

    @Test
    fun `chapter and basmalah cannot be returned from different accepted generations`() = runTest {
        var generation = 0L
        var reads = 0
        val accepted = readStableTimingGeneration({ generation }) {
            reads++
            val chapter = Segment(1, if (generation == 0L) 100 else 150, 250)
            if (reads == 1) generation++
            mapOf(1 to listOf(chapter), 0 to listOf(Segment(1, 150, 250)))
        }
        assertEquals(2, reads)
        assertEquals(accepted.segments[0], accepted.segments[1])
    }

    @Test
    fun `changing voice while a chapter read is suspended cannot install the previous voice marks`() = runTest {
        var selected = 1
        val release = CompletableDeferred<Unit>()
        val reads = mutableListOf<Int>()
        val pending = async {
            readStableTimingSelection({ selected }) { reciter ->
                reads += reciter
                if (reciter == 1) release.await()
                RuntimeTimingRead(mapOf(1 to listOf(Segment(1, reciter * 100L, reciter * 100L + 50))), 0)
            }
        }
        runCurrent()
        selected = 2
        release.complete(Unit)
        val (reciter, accepted) = pending.await()
        assertEquals(listOf(1, 2), reads)
        assertEquals(2, reciter)
        assertEquals(200L, accepted.segments.getValue(1).single().startMs)
    }

    private fun reject(payload: String, expected: Map<String, Int> = counts) {
        assertTrue(payload, runCatching { parseRuntimeTimingSnapshot(payload, 1, expected) }.isFailure)
    }
}

internal fun timingTestPayload(
    reciterId: Int = 1,
    rows: JsonArray = Json.parseToJsonElement("[[1,1,[[1,100,250],[2,300,450],[1,600,730],[2,800,1000]],80]]").jsonArray,
    withheld: JsonArray = JsonArray(emptyList()),
): String {
    val profile = RUNTIME_TIMING_PROFILES.getValue(reciterId)
    val revision = MessageDigest.getInstance("SHA-256").digest(buildJsonObject {
        put("rows", rows)
        put("withheldVerseKeys", withheld)
    }.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    return buildJsonObject {
        put("schemaVersion", 1)
        put("reciterId", reciterId)
        put("qfReciterId", profile.qfReciterId)
        put("audioSlug", profile.audioSlug)
        put("clock", "everyayah-ms")
        put("sourceSha256", "a".repeat(64))
        put("sourceSyncSequence", 541039)
        put("sourceSyncedAtMs", 1)
        put("normalizerSha256", "f".repeat(64))
        put("revision", revision)
        put("rows", rows)
        put("withheldVerseKeys", withheld)
    }.toString()
}
