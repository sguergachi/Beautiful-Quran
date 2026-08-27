package com.beautifulquran.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyQuranComMushafApiTest {
    @Test
    fun `aligns legacy words and folds the ayah marker onto the last glyph`() {
        val rows = normalizeLegacyMushaf(
            mapOf(5 to mapOf(1 to listOf("يَـٰٓأَيُّهَا", "ٱلَّذِينَ"))),
            mapOf(5 to listOf(verse(
                "5:1",
                106,
                word("يَـٰٓأَيُّهَا", "\uFC41", 106, 8, "O", "yāayyuhā"),
                word("ٱلَّذِينَ", "\uFC42", 106, 8, "you who", "alladhīna"),
                end("\uFC43"),
            ))),
        )

        assertEquals(listOf("5:1:1", "5:1:2"), rows.map { it.recordKey })
        val last = parseMushafWord(Json.parseToJsonElement(rows.last().payload).jsonObject)
        assertEquals("\uFC42 \uFC43", last.qcfV2)
        assertEquals(106, last.qcfPage)
        assertEquals(106, last.ayahPage)
        assertEquals("you who", last.translation)
    }

    @Test
    fun `fuses one source glyph across two canonical positions`() {
        val rows = normalizeLegacyMushaf(
            mapOf(1 to mapOf(1 to listOf("بِسۡمِ", "ٱللَّهِ"))),
            mapOf(1 to listOf(verse(
                "1:1",
                1,
                word("بِسۡمِ ٱللَّهِ", "\uFC41", 1, 2, "In the name of Allah", "bis'mi l-lahi"),
            ))),
        )

        val first = parseMushafWord(Json.parseToJsonElement(rows[0].payload).jsonObject)
        val second = parseMushafWord(Json.parseToJsonElement(rows[1].payload).jsonObject)
        assertEquals("\uFC41", first.qcfV2)
        assertEquals(2, first.qcfSpanEnd)
        assertEquals("", second.qcfV2)
        assertEquals(0, second.qcfPage)
        assertEquals(1, second.ayahPage)
    }

    @Test
    fun `rejects a glyph outside the page font run`() {
        val rows = normalizeLegacyMushaf(
            mapOf(1 to mapOf(1 to listOf("بِسۡمِ"))),
            mapOf(1 to listOf(verse(
                "1:1", 1, word("بِسۡمِ", "\uFC42", 1, 1, "In", "bismi"),
            ))),
        )

        val failure = runCatching { assertQcfV2Runs(rows, 1..1) }.exceptionOrNull()
        assertTrue(failure?.message?.contains("expected U+FC41") == true)
    }

    @Test
    fun `sync is local and snapshot counts each chapter page as an HTTP call`() = runBlocking {
        val paths = mutableListOf<String>()
        val api = LegacyQuranComMushafApi(
            canonicalWords = { mapOf(5 to mapOf(1 to listOf("يَـٰٓأَيُّهَا", "ٱلَّذِينَ"))) },
            chapters = 5..5,
            expectedQcfPages = 106..106,
            minimumWords = 2,
            transport = { path ->
                paths += path
                if (path.contains("page=1")) {
                    chapter(verse(
                        "5:1",
                        106,
                        word("يَـٰٓأَيُّهَا", "\uFC41", 106, 8, "O", "yāayyuhā"),
                    ), nextPage = 2)
                } else {
                    chapter(verse(
                        "5:1",
                        106,
                        word("ٱلَّذِينَ", "\uFC42", 106, 8, "you who", "alladhīna"),
                        end("\uFC43"),
                    ), nextPage = null)
                }
            },
        )
        var httpCalls = 0
        api.setNetworkCallReporter { httpCalls++ }

        api.sync(QfSyncRequest.Bootstrap(QfResourceFilter("mushafs:1")))
        assertEquals(0, httpCalls)

        val snapshot = api.snapshot("/api/v4/resources/snapshots/mushafs/1")
        assertEquals(2, httpCalls)
        assertEquals(2, paths.size)
        assertEquals(2, snapshot.rows.size)
        assertEquals(
            "\uFC42 \uFC43",
            parseMushafWord(Json.parseToJsonElement(snapshot.rows.last().payload).jsonObject).qcfV2,
        )
    }

    @Test
    fun `wrong resource and omitted ayah fail closed`() = runBlocking {
        val api = LegacyQuranComMushafApi(
            canonicalWords = { mapOf(5 to mapOf(1 to listOf("يَـٰٓأَيُّهَا"))) },
            chapters = 5..5,
            expectedQcfPages = 106..106,
            minimumWords = 1,
            transport = { chapter() },
        )
        val wrong = runCatching { api.sync(QfSyncRequest.Bootstrap(QfResourceFilter("recitations:1"))) }
        assertTrue(wrong.exceptionOrNull() is IllegalStateException)

        val omitted = runCatching { api.snapshot("/api/v4/resources/snapshots/mushafs/1") }
        assertTrue(omitted.exceptionOrNull()!!.message!!.contains("omitted"))
    }

    private fun verse(key: String, page: Int, vararg words: JsonObject) = buildJsonObject {
        put("verse_key", key)
        put("page_number", page)
        putJsonArray("words") { words.forEach { add(it) } }
    }

    private fun word(
        text: String,
        glyph: String,
        page: Int,
        line: Int,
        translation: String,
        transliteration: String,
    ) = buildJsonObject {
        put("char_type_name", "word")
        put("text_uthmani", text)
        put("code_v2", glyph)
        put("page_number", page)
        put("line_number", line)
        putJsonObject("translation") { put("text", translation) }
        putJsonObject("transliteration") { put("text", transliteration) }
    }

    private fun end(glyph: String) = buildJsonObject {
        put("char_type_name", "end")
        put("code_v2", glyph)
    }

    private fun chapter(vararg verses: JsonObject, nextPage: Int? = null) = buildJsonObject {
        putJsonArray("verses") { verses.forEach { add(it) } }
        putJsonObject("pagination") {
            if (nextPage == null) put("next_page", JsonNull) else put("next_page", nextPage)
        }
    }

}
