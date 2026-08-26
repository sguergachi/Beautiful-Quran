package com.beautifulquran.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeMushafCacheTest {
    private val filter = QfResourceFilter("mushafs:1")
    private val resource = QfResource("mushafs", 1)
    private val row = QfCacheRow(
        resource, "mushaf_word", "5:2:19",
        """{"record_type":"mushaf_word","record_key":"5:2:19","surah_id":5,"ayah_number":2,"position":19,"translation_en":"seeking","transliteration":"yabtaghūna","qcf_v2":"x","qcf_page":106,"qcf_line":12,"qcf_span_end":19,"ayah_page":106}""",
        "1",
    )

    @Test
    fun `missing cache withholds fields then atomically publishes snapshot`() = runTest {
        val store = Store()
        val api = object : QfContentSyncApi {
            override suspend fun sync(request: QfSyncRequest) = QfSyncPage(
                listOf(QfContentChange.Snapshot(resource, "/api/v4/resources/snapshots/mushafs/1")),
                null,
                "token",
            )
            override suspend fun snapshot(relativePath: String) = QfSnapshot(resource, listOf(row))
        }
        val cache = RuntimeMushafCache(api, store, backgroundScope, { 100L }, minimumWords = 1)

        assertNull(cache.word(5, 2, 19))
        runCurrent()
        assertEquals("seeking", cache.word(5, 2, 19)?.translation)
        assertEquals(106, cache.word(5, 2, 19)?.qcfPage)
        assertEquals(2L, cache.status().apiCalls)
    }

    @Test
    fun `expired cache is withheld while an offline refresh fails`() = runTest {
        val store = Store(QfSyncState(filter, "old", 0L), listOf(row))
        val cache = RuntimeMushafCache(
            failingApi(), store, backgroundScope,
            nowMs = { QF_MAX_CACHE_AGE_MS + 1 }, minimumWords = 1,
        )

        assertNull(cache.word(5, 2, 19))
        runCurrent()
        assertEquals(RuntimeCachePhase.ERROR, cache.status().phase)
        assertNull(cache.word(5, 2, 19))
    }

    @Test
    fun `six day cache remains readable while an offline refresh fails`() = runTest {
        val store = Store(QfSyncState(filter, "old", 0L), listOf(row))
        val cache = RuntimeMushafCache(
            failingApi(), store, backgroundScope,
            nowMs = { QF_REVALIDATE_AFTER_MS + 1 }, minimumWords = 1,
        )

        assertEquals("seeking", cache.word(5, 2, 19)?.translation)
        runCurrent()
        assertEquals("seeking", cache.word(5, 2, 19)?.translation)
    }

    private fun failingApi() = object : QfContentSyncApi {
        override suspend fun sync(request: QfSyncRequest): QfSyncPage = error("offline")
        override suspend fun snapshot(relativePath: String): QfSnapshot = error("offline")
    }

    private class Store(
        private var state: QfSyncState? = null,
        private var rows: List<QfCacheRow> = emptyList(),
    ) : QfContentSyncStore {
        override fun state(filter: QfResourceFilter) = state?.takeIf { it.filter == filter }
        override fun rows(resource: QfResource, recordType: String, recordKeyPrefix: String?) =
            rows.filter { it.resource == resource && it.recordType == recordType }
        override fun clear() { state = null; rows = emptyList() }
        override fun apply(
            filter: QfResourceFilter,
            changes: List<QfContentChange>,
            snapshots: List<QfSnapshot>,
            nextToken: String,
            nowMs: Long,
        ) {
            rows = snapshots.single().rows
            state = QfSyncState(filter, nextToken, nowMs)
        }
    }
}
