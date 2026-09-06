package com.beautifulquran.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QfContentSyncHttpApiTest {
    @Test
    fun `decodes the production sync shape and all reader resources`() = runBlocking {
        val paths = mutableListOf<String>()
        val progress = mutableListOf<QfSyncProgress>()
        var calls = 0
        val api = QfContentSyncHttpApi("https://content.example", transport = { path ->
            paths += path
            when {
                path.contains("/resources/sync?") -> json(SYNC)
                path.contains("/snapshots/mushafs/1") -> snapshot("mushafs", 1, MUSHAF_ROW)
                path.contains("/snapshots/word_by_word_translations/59") ->
                    snapshot("word_by_word_translations", 59, WORD_ROW)
                path.contains("/snapshots/word_by_word_transliterations/60") ->
                    snapshot("word_by_word_transliterations", 60, WORD_ROW)
                path.contains("/verses/by_key/") -> {
                    val key = path.substringAfter("/by_key/").substringBefore('?')
                    val id = 10_000L + paths.size
                    json("""{"verse":{"verse_key":"$key","words":[{"id":$id,"char_type_name":"word","transliteration":{"text":"live-$key"}},{"id":999999,"char_type_name":"end"}]}}""")
                }
                else -> error("Unexpected path $path")
            }
        })
        api.setNetworkCallReporter { calls++ }
        api.setSyncProgressReporter(progress::add)

        val page = api.sync(QfSyncRequest.Bootstrap(QF_READER_FILTER))
        val snapshots = page.changes.filterIsInstance<QfContentChange.Snapshot>()
            .map { api.snapshot(it.relativePath) }

        assertEquals("checkpoint", page.nextSyncToken)
        assertEquals(3, snapshots.size)
        assertEquals(5, page.changes.filterIsInstance<QfContentChange.Upsert>().size)
        assertEquals("mushaf", snapshots.first().rows.single().recordType)
        assertEquals("word_translation", snapshots[1].rows.single().recordType)
        assertEquals("word_transliteration", snapshots[2].rows.single().recordType)
        assertEquals(9, calls)
        assertEquals(QfSyncProgress(9, 9), progress.last())
        assertTrue(paths.first().contains("bootstrap=true"))
    }

    @Test
    fun `rejects a mutation outside the fixed reader resource set`() = runBlocking {
        val api = QfContentSyncHttpApi("https://content.example", transport = {
            json("""{"sync":{"has_more":false,"next_page_url":null,"next_sync_token":"x","mutations":[{"type":"RESOURCE_UPDATE","resource_group":"tafsirs","resource_id":1}]}}""")
        })

        val failure = runCatching { api.sync(QfSyncRequest.Bootstrap(QF_READER_FILTER)) }
            .exceptionOrNull()

        assertTrue(failure?.message?.contains("Unexpected QF resource") == true)
    }

    private fun snapshot(group: String, id: Int, row: String) =
        json("""{"schema_version":1,"resource_group":"$group","resource_id":$id,"records":[$row]}""")

    private fun json(value: String) = Json.parseToJsonElement(value)

    private companion object {
        const val MUSHAF_ROW = """{"id":1,"record_type":"mushaf"}"""
        const val WORD_ROW = """{"id":2,"word_id":1,"text":"word"}"""
        const val SYNC = """{"sync":{"has_more":false,"next_page_url":null,"next_sync_token":"checkpoint","mutations":[{"type":"RESOURCE_CREATE","resource_group":"mushafs","resource_id":1,"snapshot_url":"/api/v4/resources/snapshots/mushafs/1"},{"type":"RESOURCE_CREATE","resource_group":"word_by_word_translations","resource_id":59,"snapshot_url":"/api/v4/resources/snapshots/word_by_word_translations/59"},{"type":"RESOURCE_CREATE","resource_group":"word_by_word_transliterations","resource_id":60,"snapshot_url":"/api/v4/resources/snapshots/word_by_word_transliterations/60"}]}}"""
    }
}
