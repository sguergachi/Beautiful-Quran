package com.beautifulquran.data

import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Backend-facing Content Sync decoder. QF credentials never enter the app. */
class TimingContentSyncApi(
    baseUrl: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : QfContentSyncApi {
    private val baseUrl = baseUrl.trimEnd('/')

    init {
        require(this.baseUrl.startsWith("https://")) { "Timing Content API must use HTTPS" }
    }

    override suspend fun sync(request: QfSyncRequest): QfSyncPage {
        val path = when (request) {
            is QfSyncRequest.Bootstrap ->
                "/api/v4/resources/sync?bootstrap=true&resources=${encode(request.filter.value)}"
            is QfSyncRequest.Incremental ->
                "/api/v4/resources/sync?sync_token=${encode(request.token)}" +
                    "&resources=${encode(request.filter.value)}"
            is QfSyncRequest.NextPage -> request.relativePath.also(::requireRelativeApiPath)
        }
        val root = get(path).jsonObject["sync"]?.jsonObject
            ?: error("Content Sync response has no sync object")
        val changes = (root["mutations"]?.jsonArray
            ?: error("Content Sync response has no mutations array")).map(::change)
        return QfSyncPage(
            changes = changes,
            nextPagePath = root.string("next_page_url"),
            nextSyncToken = root.string("next_sync_token"),
            contentAgeMs = root["content_age_ms"]?.jsonPrimitive?.longOrNull
                ?: error("Content Sync response has no content age"),
        )
    }

    override suspend fun snapshot(relativePath: String): QfSnapshot {
        requireRelativeApiPath(relativePath)
        val root = get(relativePath).jsonObject
        check(root["schema_version"]?.jsonPrimitive?.longOrNull == 1L) {
            "Unsupported timing snapshot schema"
        }
        val resource = QfResource(
            root.requiredString("resource_group"),
            root["resource_id"]?.jsonPrimitive?.longOrNull
                ?: error("Snapshot has no resource_id"),
        )
        val updatedAt = root["sync_sequence"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val rows = (root["records"]?.jsonArray
            ?: error("Timing snapshot has no records array")).map { element ->
            val record = element.jsonObject
            QfCacheRow(
                resource = resource,
                recordType = record.requiredString("record_type"),
                recordKey = record.requiredString("record_key"),
                payload = record.toString(),
                updatedAt = updatedAt,
            )
        }
        check(rows.size >= MIN_TIMING_ROWS) {
            "Timing snapshot is incomplete"
        }
        check(rows.all { it.recordType == "timing" }) { "Timing snapshot has an unexpected row type" }
        check(rows.map { it.recordKey }.distinct().size == rows.size) {
            "Timing snapshot has duplicate rows"
        }
        return QfSnapshot(resource, rows)
    }

    private fun change(element: JsonElement): QfContentChange {
        val mutation = element.jsonObject
        val resource = QfResource(
            mutation.requiredString("resource_group"),
            mutation["resource_id"]?.jsonPrimitive?.longOrNull
                ?: error("Mutation has no resource_id"),
        )
        return when (mutation.requiredString("type")) {
            "RESOURCE_CREATE", "RESOURCE_INVALIDATE" ->
                QfContentChange.Snapshot(
                    resource,
                    mutation.requiredString("snapshot_url").also(::requireRelativeApiPath),
                )
            "RESOURCE_DELETE" -> QfContentChange.DeleteResource(resource)
            "RESOURCE_UPDATE" -> QfContentChange.FreshnessMarker
            "ROW_DELETE" -> QfContentChange.DeleteRow(
                resource,
                mutation.requiredString("record_type"),
                mutation.requiredString("record_key"),
            )
            "ROW_CREATE", "ROW_UPDATE" -> QfContentChange.Upsert(
                QfCacheRow(
                    resource = resource,
                    recordType = mutation.requiredString("record_type"),
                    recordKey = mutation.requiredString("record_key"),
                    payload = mutation["data"]?.toString() ?: error("Row mutation has no data"),
                    updatedAt = mutation.string("changed_at").orEmpty(),
                ),
            )
            else -> error("Unsupported Content Sync mutation")
        }
    }

    private suspend fun get(relativePath: String): JsonElement = withContext(Dispatchers.IO) {
        require(relativePath.startsWith("/api/v4/")) { "Unexpected Content API path" }
        val connection = URL(baseUrl + relativePath).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            // A cold transitional provider assembles 114 chapters once. This
            // is background-only; authenticated QF snapshots should be fast.
            connection.readTimeout = 180_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Beautiful-Quran/0.7")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= MAX_RESPONSE_BYTES) {
                        "Content API response exceeded size limit"
                    }
                    output.write(buffer, 0, count)
                }
                String(output.toByteArray(), StandardCharsets.UTF_8)
            }.orEmpty()
            check(status in 200..299) { "Content API returned $status" }
            json.parseToJsonElement(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun JsonObject.string(key: String): String? =
        get(key)?.jsonPrimitive?.contentOrNull

    private fun JsonObject.requiredString(key: String): String =
        string(key) ?: error("Content API field $key is missing")

    private companion object {
        const val MAX_RESPONSE_BYTES = 20 * 1024 * 1024
        const val MIN_TIMING_ROWS = 6_000
    }
}
