package com.beautifulquran.data

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The credential boundary serves only offline-normalized, reviewed timing generations. */
internal class RuntimeTimingHttpApi(baseUrl: String) : RuntimeTimingApi {
    private val baseUrl = baseUrl.removeSuffix("/").also {
        require(URI(it).scheme == "https") { "Timing provider must use HTTPS" }
    }

    override suspend fun fetch(reciterId: Int): String = withContext(Dispatchers.IO) {
        require(reciterId in RUNTIME_TIMING_PROFILES)
        val connection = URL("$baseUrl/api/timings/$reciterId").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 180_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.setRequestProperty("User-Agent", "Beautiful-Quran/0.10")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                decodeContent(input, connection.contentEncoding).use { decoded ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = decoded.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= 8 * 1024 * 1024) { "Timing response is too large" }
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8)
                }
            }.orEmpty()
            if (status !in 200..299) {
                val code = runCatching {
                    Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
                        ?.get("code")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                throwRuntimeTimingHttpError(status, code)
            }
            body
        } finally {
            connection.disconnect()
        }
    }
}

/** Withdrawal and access rejection are distinct; an unreviewed source leaves accepted rows intact. */
internal fun throwRuntimeTimingHttpError(status: Int, code: String?): Nothing {
    if (status == 403 && code == "qf_access_revoked") throw QfAccessRevokedException()
    if (status == 410 && code == "qf_timing_resource_deleted") throw QfTimingResourceDeletedException()
    error("QF timing provider returned $status")
}
