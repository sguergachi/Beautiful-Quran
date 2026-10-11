package com.beautifulquran.data.account

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class QfHttpError(val status: Int) : Exception("QF request failed")

/** Public-client revocation has no secret. A refused or interrupted request is reported as unconfirmed. */
internal fun qfRevokeToken(issuer: String, clientId: String, token: String, hint: String): Boolean {
    val connection = URL(issuer + "/oauth2/revoke").openConnection() as HttpURLConnection
    return try {
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.doOutput = true
        val form = listOf("client_id" to clientId, "token" to token, "token_type_hint" to hint)
            .joinToString("&") { (key, value) -> key + "=" + URLEncoder.encode(value, "UTF-8") }
        connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        connection.responseCode in 200..299
    } catch (_: Exception) {
        false
    } finally {
        connection.disconnect()
    }
}

/** Fixed HTTPS endpoints, no redirects, cookies, request logging or response caching. */
internal fun qfJsonRequest(
    url: String,
    method: String,
    headers: Map<String, String>,
    body: JsonObject? = null,
): JsonObject {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        if (connection.responseCode !in 200..299) throw QfHttpError(connection.responseCode)
        val bytes = connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 2_000_000) { "QF response is too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.also {
            require(it["success"]?.jsonPrimitive?.booleanOrNull != false) { "QF rejected the request" }
        }
    } finally {
        connection.disconnect()
    }
}
