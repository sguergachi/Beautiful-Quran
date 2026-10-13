package com.beautifulquran.data

import com.beautifulquran.data.model.Segment
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class RuntimeTimingProfile(val qfReciterId: Int, val audioSlug: String)

/** These six recordings use QF repeat topology; the other voices stay entirely offline. */
internal val RUNTIME_TIMING_PROFILES = mapOf(
    1 to RuntimeTimingProfile(7, "Alafasy_128kbps"),
    2 to RuntimeTimingProfile(6, "Husary_64kbps"),
    3 to RuntimeTimingProfile(2, "Abdul_Basit_Murattal_64kbps"),
    4 to RuntimeTimingProfile(9, "Minshawy_Murattal_128kbps"),
    5 to RuntimeTimingProfile(3, "Abdurrahmaan_As-Sudais_192kbps"),
    7 to RuntimeTimingProfile(5, "Hani_Rifai_192kbps"),
)

internal data class RuntimeTimingRow(
    val surahId: Int,
    val ayahNumber: Int,
    val segments: String,
    val audioOnsetMs: Long,
)

internal data class RuntimeTimingSnapshot(
    val reciterId: Int,
    val revision: String,
    val metadata: String,
    val rows: List<RuntimeTimingRow>,
)

internal data class RuntimeTimingRead(val segments: Map<Int, List<Segment>>, val generation: Long)

/** A refresh overtaking chapter/basmalah reads must never return a mixed timing generation. */
internal suspend fun readStableTimingGeneration(
    generation: () -> Long,
    read: suspend () -> Map<Int, List<Segment>>,
): RuntimeTimingRead {
    while (true) {
        val started = generation()
        val rows = read()
        if (started == generation()) return RuntimeTimingRead(rows, started)
    }
}

/** A reciter choice overtaking a suspended chapter read must replace both the voice and its marks. */
internal suspend fun readStableTimingSelection(
    reciterId: () -> Int,
    read: suspend (Int) -> RuntimeTimingRead,
): Pair<Int, RuntimeTimingRead> {
    while (true) {
        val selected = reciterId()
        val rows = read(selected)
        if (selected == reciterId()) return selected to rows
    }
}

/** Validate canonical output only: source cleaning and acoustic repair stay on the backend. */
internal fun parseRuntimeTimingSnapshot(
    raw: String,
    reciterId: Int,
    wordCounts: Map<String, Int>,
): RuntimeTimingSnapshot {
    val root = Json.parseToJsonElement(raw).jsonObject
    val profile = RUNTIME_TIMING_PROFILES.getValue(reciterId)
    check(root.int("schemaVersion") == 1 && root.int("reciterId") == reciterId)
    check(root.int("qfReciterId") == profile.qfReciterId)
    check(root.string("audioSlug") == profile.audioSlug && root.string("clock") == "everyayah-ms")
    check(root.long("sourceSyncSequence") >= 0 && root.long("sourceSyncedAtMs") > 0)
    listOf("sourceSha256", "normalizerSha256", "revision").forEach {
        check(root.string(it).matches(Regex("[0-9a-f]{64}"))) { "Invalid timing fingerprint: $it" }
    }
    val rows = root.getValue("rows").jsonArray
    val withheld = root.getValue("withheldVerseKeys").jsonArray
    val digest = MessageDigest.getInstance("SHA-256").digest(buildJsonObject {
        put("rows", rows)
        put("withheldVerseKeys", withheld)
    }.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    check(digest == root.string("revision")) { "Timing revision does not match its rows" }
    check(rows.isNotEmpty()) { "Timing resource contains no accepted rows" }
    val seen = HashSet<String>()
    var previousVerse = 0
    val accepted = rows.map { value ->
        val row = value.jsonArray
        check(row.size == 4) { "Invalid canonical timing row" }
        val surah = row.int(0)
        val ayah = row.int(1)
        val key = "$surah:$ayah"
        val count = wordCounts[key] ?: error("Unknown timing verse: $key")
        check(surah * 1000 + ayah > previousVerse && seen.add(key)) { "Unordered timing verse: $key" }
        previousVerse = surah * 1000 + ayah
        val onset = row.long(3)
        check(onset >= 0)
        val positions = BooleanArray(count + 1)
        var previousEnd = onset
        val segments = row[2].jsonArray
        segments.forEach { segment ->
            val tuple = segment.jsonArray
            check(tuple.size == 3) { "Invalid canonical timing segment: $key" }
            val position = tuple.int(0)
            val start = tuple.long(1)
            val end = tuple.long(2)
            check(position in 1..count && start >= previousEnd && end > start) {
                "Invalid timing position/clock: $key"
            }
            positions[position] = true
            previousEnd = end
        }
        check((1..count).all { positions[it] }) { "Incomplete timing verse: $key" }
        RuntimeTimingRow(surah, ayah, segments.toString(), onset)
    }
    previousVerse = 0
    withheld.forEach {
        val key = (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: error("Invalid withheld timing verse")
        val parts = key.split(':').map(String::toInt)
        check(parts.size == 2 && key == "${parts[0]}:${parts[1]}" && key in wordCounts)
        val ordinal = parts[0] * 1000 + parts[1]
        check(ordinal > previousVerse && seen.add(key)) { "Duplicate/unordered withheld timing verse" }
        previousVerse = ordinal
    }
    check(seen == wordCounts.keys) { "Timing corpus coverage mismatch" }
    return RuntimeTimingSnapshot(
        reciterId, digest, JsonObject(root.filterKeys { it != "rows" }).toString(), accepted,
    )
}

private fun JsonObject.string(key: String): String =
    getValue(key).jsonPrimitive.takeIf(JsonPrimitive::isString)?.content
        ?: error("Invalid timing field: $key")

private fun JsonObject.long(key: String): Long =
    getValue(key).jsonPrimitive.takeUnless(JsonPrimitive::isString)?.longOrNull
        ?: error("Invalid timing integer: $key")

private fun JsonObject.int(key: String): Int =
    getValue(key).jsonPrimitive.takeUnless(JsonPrimitive::isString)?.intOrNull
        ?: error("Invalid timing integer: $key")

private fun JsonArray.long(index: Int): Long =
    get(index).jsonPrimitive.takeUnless(JsonPrimitive::isString)?.longOrNull
        ?: error("Invalid timing integer")

private fun JsonArray.int(index: Int): Int =
    get(index).jsonPrimitive.takeUnless(JsonPrimitive::isString)?.intOrNull
        ?: error("Invalid timing integer")
