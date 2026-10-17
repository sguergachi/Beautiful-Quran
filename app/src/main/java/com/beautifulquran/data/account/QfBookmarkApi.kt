package com.beautifulquran.data.account

import java.net.URLEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Only the explicitly selected collection is read or changed; Favorites and reading remain separate. */
class QfBookmarkApi(
    private val request: suspend (String, String, JsonObject?) -> JsonObject,
    private val valid: (SyncedVerse) -> Boolean,
) : QfBookmarkRemote {
    override suspend fun collection(): String {
        val matches = pages("/v1/collections?first=20&sortBy=alphabetical") {
            it.getValue("data").jsonArray.map { row -> row.jsonObject }
        }.distinctBy { it.text("id") }.filter { it.text("name") == QfAccountConfig.COLLECTION }
        require(matches.size <= 1) {
            "More than one Beautiful Quran collection exists. Rename the extra collection in QF, then retry."
        }
        if (matches.isNotEmpty()) return matches.single().text("id")
        val created = request("POST", "/v1/collections", JsonObject(
            mapOf("name" to JsonPrimitive(QfAccountConfig.COLLECTION)),
        ))
        require(created["success"]?.jsonPrimitive?.booleanOrNull == true)
        return created.getValue("data").jsonObject.text("id")
    }

    /** Pagination must finish and agree with the collection's count before any local replacement. */
    override suspend fun bookmarks(collectionId: String): List<SyncedVerse> {
        var count: Int? = null
        val rows = pages(path(collectionId) + "?first=20&sortBy=verseKey") {
            val data = it.getValue("data").jsonObject
            val collection = data.getValue("collection").jsonObject
            require(collection.text("id") == collectionId) { "QF returned another collection" }
            val total = collection.getValue("bookmarksCount").jsonPrimitive.intOrNull
            require(total != null && total >= 0 && (count == null || count == total)) {
                "The QF collection changed while loading. Retry sync."
            }
            count = total
            data.getValue("bookmarks").jsonArray.map { row -> row.jsonObject }
        }.distinctBy { it.text("id") }
        require(rows.size == count) { "QF returned an incomplete collection. Retry sync." }
        return rows.mapNotNull { row ->
            if (row.text("type") != "ayah" || row.text("group") != "verses_6236") null
            else SyncedVerse(
                row.getValue("key").jsonPrimitive.intOrNull ?: error("Invalid chapter"),
                row.getValue("verseNumber").jsonPrimitive.intOrNull ?: error("Invalid verse"),
            ).also { require(valid(it)) { "QF returned an invalid verse" } }
        }.distinct()
    }

    /** Desired-state writes, never toggle or standalone bookmark DELETE. Safe to retry after a lost reply. */
    override suspend fun write(collectionId: String, edit: BookmarkEdit) {
        require(valid(edit.verse))
        val response = request(if (edit.present) "POST" else "DELETE", path(collectionId) + "/bookmarks",
            JsonObject(mapOf(
                "key" to JsonPrimitive(edit.verse.surahId),
                "type" to JsonPrimitive("ayah"),
                "verseNumber" to JsonPrimitive(edit.verse.ayah),
                "mushafId" to JsonPrimitive(4),
            )),
        )
        require(response["success"]?.jsonPrimitive?.booleanOrNull == true)
    }

    private suspend fun pages(
        path: String,
        rows: (JsonObject) -> List<JsonObject>,
    ): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(512) {
            val response = request("GET", path + (cursor?.let { "&after=" + encode(it) } ?: ""), null)
            require(response["success"]?.jsonPrimitive?.booleanOrNull == true)
            result += rows(response)
            val pagination = response.getValue("pagination").jsonObject
            val more = pagination.getValue("hasNextPage").jsonPrimitive.booleanOrNull
                ?: error("Missing QF pagination")
            if (!more) return result
            cursor = pagination.text("endCursor")
            require(cursors.add(cursor)) { "QF repeated a pagination cursor" }
        }
        error("QF pagination did not finish")
    }

    private fun path(id: String) = "/v1/collections/" + encode(id)
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun JsonObject.text(key: String): String =
        getValue(key).jsonPrimitive.content.also { require(it.isNotBlank()) }
}
