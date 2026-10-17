package com.beautifulquran.data.account

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class QfBookmarkApiTest {
    private fun row(id: Int, group: String = "verses_6236") = buildJsonObject {
        put("id", id.toString()); put("type", "ayah"); put("group", group)
        put("key", 2); put("verseNumber", id)
    }
    private fun page(rows: List<JsonObject>, total: Int, more: Boolean, cursor: String) =
        buildJsonObject {
            put("success", true)
            put("data", buildJsonObject {
                put("collection", buildJsonObject { put("id", "owned"); put("bookmarksCount", total) })
                put("bookmarks", JsonArray(rows))
            })
            put("pagination", buildJsonObject { put("hasNextPage", more); put("endCursor", cursor) })
        }
    private fun api(request: suspend (String, String, JsonObject?) -> JsonObject) =
        QfBookmarkApi(request) { it.surahId == 2 && it.ayah in 1..286 }

    @Test fun completePaginationSelectsOnlyTheCorrectMushaf() = runBlocking {
        val paths = mutableListOf<String>()
        val api = api { _, path, _ ->
            paths += path
            if (paths.size == 1) page(listOf(row(1)), 3, true, "cursor +")
            else page(listOf(row(2), row(3, "verses_other")), 3, false, "end")
        }
        assertEquals(listOf(SyncedVerse(2, 1), SyncedVerse(2, 2)), api.bookmarks("owned"))
        assertTrue(paths[1].endsWith("&after=cursor+%2B"))
    }

    @Test fun cyclingCursorsAndIncompleteCountsFailClosed() = runBlocking<Unit> {
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            api { _, _, _ -> page(listOf(row(1)), 3, true, "same") }.bookmarks("owned")
        } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            api { _, _, _ -> page(listOf(row(1)), 2, false, "end") }.bookmarks("owned")
        } }
    }

    @Test fun changingCollectionCountCannotPublishPartialSnapshot() = runBlocking<Unit> {
        var call = 0
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            api { _, _, _ -> if (call++ == 0) page(listOf(row(1)), 2, true, "a")
                else page(listOf(row(2)), 3, false, "b") }.bookmarks("owned")
        } }
    }

    @Test fun removalUsesCollectionMembershipAndExactVerseDetails() = runBlocking {
        var captured: JsonObject? = null
        var method = ""
        var path = ""
        api { verb, route, body ->
            method = verb; path = route; captured = body
            buildJsonObject { put("success", true) }
        }.write("owned", BookmarkEdit(SyncedVerse(2, 255), false, 1))
        assertEquals("DELETE", method)
        assertEquals("/v1/collections/owned/bookmarks", path)
        assertEquals(Json.parseToJsonElement(
            """{"key":2,"type":"ayah","verseNumber":255,"mushafId":4}"""), captured)
    }

    @Test fun rejectedEnvelopeAndInvalidVerseFailClosed() = runBlocking<Unit> {
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            api { _, _, _ -> buildJsonObject { put("success", false) } }
                .write("owned", BookmarkEdit(SyncedVerse(2, 255), true, 1))
        } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            api { _, _, _ -> page(listOf(row(287)), 1, false, "end") }.bookmarks("owned")
        } }
    }
}
