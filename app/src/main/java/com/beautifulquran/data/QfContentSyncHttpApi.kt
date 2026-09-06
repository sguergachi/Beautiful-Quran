package com.beautifulquran.data

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Authenticated QF Content Sync, reached only through our secret-holding Worker. */
class QfContentSyncHttpApi internal constructor(
    baseUrl: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
    transport: ((String) -> JsonElement)? = null,
) : QfContentSyncApi, QfNetworkCallReporter, QfSyncProgressReporter {
    private val baseUrl = baseUrl.removeSuffix("/").also {
        require(URI(it).scheme == "https") { "QF Content API must use HTTPS" }
    }
    private val transport = transport ?: { path -> httpGet(path) }
    @Volatile private var reportNetworkCall: () -> Unit = {}
    @Volatile private var reportProgress: (QfSyncProgress) -> Unit = {}
    private var completedCalls = 0
    private var expectedCalls = 1

    override fun setNetworkCallReporter(reporter: () -> Unit) {
        reportNetworkCall = reporter
    }

    override fun setSyncProgressReporter(reporter: (QfSyncProgress) -> Unit) {
        reportProgress = reporter
    }

    override suspend fun sync(request: QfSyncRequest): QfSyncPage = withContext(Dispatchers.IO) {
        completedCalls = 0
        expectedCalls = 1
        reportProgress(QfSyncProgress(0, expectedCalls))
        val page = parseSyncPage(get(syncPath(request)))
        val finalPage = page.nextPagePath == null
        expectedCalls = 1 + page.changes.count { it is QfContentChange.Snapshot } +
            if (finalPage) SUPPLEMENT_VERSES.size else 0
        completedCalls = 1
        reportProgress(QfSyncProgress(completedCalls, expectedCalls))
        if (!finalPage) return@withContext page

        val supplements = SUPPLEMENT_VERSES.flatMap { verseKey ->
            parseSupplement(verseKey, get(supplementPath(verseKey))).also {
                completedCalls++
                reportProgress(QfSyncProgress(completedCalls, expectedCalls))
            }
        }
        check(supplements.map { it.recordKey }.distinct().size == supplements.size) {
            "Duplicate QF word supplement"
        }
        page.copy(changes = page.changes + supplements.map(QfContentChange::Upsert))
    }

    override suspend fun snapshot(relativePath: String): QfSnapshot = withContext(Dispatchers.IO) {
        requireRelativeApiPath(relativePath)
        parseSnapshot(get(relativePath)).also {
            completedCalls++
            reportProgress(QfSyncProgress(completedCalls.coerceAtMost(expectedCalls), expectedCalls))
        }
    }

    private fun get(path: String): JsonElement {
        requireRelativeApiPath(path)
        reportNetworkCall()
        return transport(path)
    }

    private fun httpGet(path: String): JsonElement {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 180_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Beautiful-Quran/0.7")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= MAX_RESPONSE_BYTES) {
                        "QF Content API response is too large"
                    }
                    output.write(buffer, 0, count)
                }
                output.toString(StandardCharsets.UTF_8)
            }.orEmpty()
            val errorCode = runCatching {
                    json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
                        ?.get("code")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
            if (status == 410 && errorCode == "resync_required") {
                throw QfResyncRequiredException()
            }
            if (status == 403 && errorCode == "qf_access_revoked") throw QfAccessRevokedException()
            check(status in 200..299) { "QF Content API returned $status" }
            return json.parseToJsonElement(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun syncPath(request: QfSyncRequest): String = when (request) {
        is QfSyncRequest.Bootstrap ->
            "/api/v4/resources/sync?bootstrap=true&resources=${encode(request.filter.value)}"
        is QfSyncRequest.Incremental ->
            "/api/v4/resources/sync?sync_token=${encode(request.token)}&resources=${encode(request.filter.value)}"
        is QfSyncRequest.NextPage -> request.relativePath.also(::requireRelativeApiPath)
    }

    private fun parseSyncPage(root: JsonElement): QfSyncPage {
        val sync = root.jsonObject["sync"]?.jsonObject ?: error("QF sync response has no sync object")
        val changes = sync["mutations"]?.jsonArray?.map { parseMutation(it.jsonObject) }
            ?: error("QF sync response has no mutations")
        val hasMore = sync["has_more"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: error("QF sync response has no has_more")
        val nextPage = sync.nullableString("next_page_url")
        val nextToken = sync.nullableString("next_sync_token")
        check(hasMore == (nextPage != null)) { "QF sync pagination mismatch" }
        check(hasMore || !nextToken.isNullOrBlank()) { "Final QF sync page has no token" }
        return QfSyncPage(changes, nextPage, nextToken)
    }

    private fun parseMutation(value: JsonObject): QfContentChange {
        val resource = QfResource(value.requiredString("resource_group"), value.requiredLong("resource_id"))
        check(resource in READER_RESOURCES) { "Unexpected QF resource mutation" }
        return when (value.requiredString("type")) {
            "RESOURCE_CREATE", "RESOURCE_INVALIDATE" -> QfContentChange.Snapshot(
                resource,
                value.requiredString("snapshot_url").also(::requireRelativeApiPath),
            )
            "RESOURCE_DELETE" -> QfContentChange.DeleteResource(resource)
            "RESOURCE_UPDATE" -> QfContentChange.FreshnessMarker
            "ROW_CREATE", "ROW_UPDATE" -> {
                val recordType = value.requiredString("record_type")
                val recordKey = value.requiredString("record_key")
                val payload = value["data"]?.jsonObject ?: error("QF row mutation has no data")
                QfContentChange.Upsert(
                    QfCacheRow(
                        resource,
                        recordType,
                        recordKey,
                        payload.toString(),
                        value.requiredString("changed_at"),
                    ),
                )
            }
            "ROW_DELETE" -> QfContentChange.DeleteRow(
                resource,
                value.requiredString("record_type"),
                value.requiredString("record_key"),
            )
            else -> error("Unsupported QF Content Sync mutation")
        }
    }

    private fun parseSnapshot(root: JsonElement): QfSnapshot {
        val value = root.jsonObject
        check(value["schema_version"]?.jsonPrimitive?.intOrNull == 1) { "Unsupported QF snapshot schema" }
        val resource = QfResource(value.requiredString("resource_group"), value.requiredLong("resource_id"))
        check(resource in READER_RESOURCES) { "Unexpected QF snapshot resource" }
        val inferredType = when (resource) {
            WORD_TRANSLATION_RESOURCE -> "word_translation"
            WORD_TRANSLITERATION_RESOURCE -> "word_transliteration"
            else -> null
        }
        val rows = value["records"]?.jsonArray?.map { element ->
            val row = element.jsonObject
            val type = inferredType ?: row.requiredString("record_type")
            val key = row.requiredLong("id").toString()
            QfCacheRow(resource, type, key, row.toString(), row.string("updated_at").orEmpty())
        } ?: error("QF snapshot has no records")
        return QfSnapshot(resource, rows)
    }

    private fun parseSupplement(verseKey: String, root: JsonElement): List<QfCacheRow> {
        val verse = root.jsonObject["verse"]?.jsonObject ?: error("QF verse supplement is missing")
        check(verse.requiredString("verse_key") == verseKey) { "QF verse supplement mismatch" }
        return verse["words"]?.jsonArray?.mapNotNull { element ->
            val word = element.jsonObject
            if (word.string("char_type_name") != "word") return@mapNotNull null
            val wordId = word.requiredLong("id")
            val transliteration = word["transliteration"]?.jsonObject?.string("text")?.trim().orEmpty()
            check(transliteration.isNotEmpty()) { "QF supplement omitted transliteration $wordId" }
            val payload = buildJsonObject {
                put("word_id", wordId)
                put("text", transliteration)
            }
            QfCacheRow(WORD_SUPPLEMENT_RESOURCE, "word_transliteration", wordId.toString(), payload.toString(), "")
        } ?: error("QF verse supplement has no words")
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 40 * 1024 * 1024
        val SUPPLEMENT_VERSES = listOf("1:1", "2:181", "8:6", "9:1", "36:52")
        val READER_RESOURCES = setOf(
            QF_MUSHAF_RESOURCE,
            WORD_TRANSLATION_RESOURCE,
            WORD_TRANSLITERATION_RESOURCE,
        )
        fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
        fun supplementPath(verseKey: String) =
            "/api/v4/verses/by_key/$verseKey?words=true&language=en"
    }
}

internal val QF_MUSHAF_RESOURCE = QfResource("mushafs", 1)
internal val WORD_TRANSLATION_RESOURCE = QfResource("word_by_word_translations", 59)
internal val WORD_TRANSLITERATION_RESOURCE = QfResource("word_by_word_transliterations", 60)
internal val WORD_SUPPLEMENT_RESOURCE = QfResource("word_supplements", 1)
internal val QF_READER_FILTER = QfResourceFilter(
    "mushafs:1;word_by_word_translations:59;word_by_word_transliterations:60",
)

private fun JsonObject.requiredString(name: String) =
    string(name) ?: error("QF field $name is missing")

private fun JsonObject.requiredLong(name: String) =
    get(name)?.jsonPrimitive?.longOrNull ?: error("QF field $name is missing")

private fun JsonObject.string(name: String) =
    get(name)?.jsonPrimitive?.contentOrNull

private fun JsonObject.nullableString(name: String): String? =
    get(name)?.takeUnless { it.toString() == "null" }?.jsonPrimitive?.contentOrNull
