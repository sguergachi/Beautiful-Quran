package com.beautifulquran.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val api = SnapshotApi()
        val cache = RuntimeMushafCache(api, store, backgroundScope, { 100L }, minimumWords = 1)

        assertNull(cache.word(5, 2, 19))
        runCurrent()
        assertEquals("seeking", cache.word(5, 2, 19)?.translation)
        assertEquals(106, cache.word(5, 2, 19)?.qcfPage)
        assertEquals(2L, cache.status().apiCalls)
        assertEquals(1, store.rowReads)
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
        val failing = CountingApi { error("offline") }
        val cache = RuntimeMushafCache(
            failing, store, backgroundScope,
            nowMs = { QF_REVALIDATE_AFTER_MS + 1 }, minimumWords = 1,
        )

        assertEquals("seeking", cache.word(5, 2, 19)?.translation)
        runCurrent()
        assertEquals("seeking", cache.word(5, 2, 19)?.translation)
        assertEquals(1, failing.syncs)
    }

    @Test
    fun `launch hook does not call API while cache is fresh`() = runTest {
        val store = Store(QfSyncState(filter, "cached", 90L), listOf(row))
        val api = CountingApi { error("should not fetch") }
        val cache = RuntimeMushafCache(api, store, backgroundScope, nowMs = { 100L }, minimumWords = 1)

        cache.refreshIfNeeded()
        runCurrent()

        assertEquals(0, api.syncs)
        assertEquals(RuntimeCachePhase.FRESH, cache.status().phase)
        assertEquals("seeking", cache.word(5, 2, 19)?.translation)
        assertEquals(0, api.syncs)
    }

    @Test
    fun `direct adapter reports physical HTTP calls instead of wrapper hops`() = runTest {
        val store = Store()
        val api = ReportingApi(httpCalls = 3)
        val cache = RuntimeMushafCache(api, store, backgroundScope, { 100L }, minimumWords = 1)

        assertNull(cache.word(5, 2, 19))
        runCurrent()

        assertEquals("seeking", cache.word(5, 2, 19)?.translation)
        assertEquals(3L, cache.status().apiCalls)
        assertEquals(1, api.syncs)
    }

    @Test
    fun `concurrent refresh coalesces to one sync`() = runTest {
        val store = Store()
        val api = SnapshotApi()
        val cache = RuntimeMushafCache(api, store, backgroundScope, { 100L }, minimumWords = 1)

        cache.refresh()
        cache.refresh()
        runCurrent()

        assertEquals(1, api.syncs)
        assertEquals("seeking", cache.word(5, 2, 19)?.translation)
    }

    @Test
    fun `failed refresh is not retried from a reader lookup`() = runTest {
        val store = Store()
        val api = CountingApi { error("offline") }
        val cache = RuntimeMushafCache(api, store, backgroundScope, { 100L }, minimumWords = 1)

        assertNull(cache.word(5, 2, 19))
        runCurrent()
        assertNull(cache.word(5, 2, 19))
        runCurrent()
        assertEquals(1, api.syncs)

        cache.refreshIfNeeded()
        runCurrent()
        assertEquals(2, api.syncs)
    }

    @Test
    fun `six day timer refreshes a still readable cache`() = runTest {
        val store = Store(QfSyncState(filter, "old", 0L), listOf(row))
        val api = SnapshotApi()
        val cache = RuntimeMushafCache(
            api, store, backgroundScope,
            nowMs = { testScheduler.currentTime }, minimumWords = 1,
        )

        cache.refreshIfNeeded()
        runCurrent()
        assertEquals(0, api.syncs)
        assertEquals("seeking", cache.word(5, 2, 19)?.translation)

        advanceTimeBy(QF_REVALIDATE_AFTER_MS)
        runCurrent()
        assertEquals(1, api.syncs)
        assertEquals("seeking", cache.word(5, 2, 19)?.translation)
    }

    @Test
    fun `seven day timer withholds the snapshot and notifies readers`() = runTest {
        val store = Store(QfSyncState(filter, "old", 0L), listOf(row))
        val api = CountingApi { error("offline") }
        val cache = RuntimeMushafCache(
            api, store, backgroundScope,
            nowMs = { testScheduler.currentTime }, minimumWords = 1,
        )

        cache.refreshIfNeeded()
        assertEquals("seeking", cache.word(5, 2, 19)?.translation)
        val notified = async { cache.changes.first() }
        runCurrent()

        advanceTimeBy(QF_MAX_CACHE_AGE_MS + 1)
        advanceUntilIdle()

        assertNull(cache.word(5, 2, 19))
        notified.await()
        assertTrue(api.syncs >= 1)
    }

    @Test
    fun `corrupt cached rows are withheld without rereading`() = runTest {
        val bad = QfCacheRow(
            resource, "mushaf_word", "5:2:19",
            """{"record_type":"mushaf_word","record_key":"5:2:19","surah_id":5,"ayah_number":2,"position":19,"translation_en":"seeking","transliteration":"x","qcf_v2":"x","qcf_page":0,"qcf_line":0,"qcf_span_end":19,"ayah_page":106}""",
            "1",
        )
        val store = Store(QfSyncState(filter, "old", 0L), listOf(bad))
        val cache = RuntimeMushafCache(
            failingApi(), store, backgroundScope,
            nowMs = { 100L }, minimumWords = 1,
        )

        assertNull(cache.word(5, 2, 19))
        assertNull(cache.word(5, 2, 19))
        assertEquals(1, store.rowReads)
        assertEquals(RuntimeCachePhase.ERROR, cache.status().phase)
    }

    private fun failingApi() = CountingApi { error("offline") }

    private inner class SnapshotApi : QfContentSyncApi {
        var syncs = 0
        override suspend fun sync(request: QfSyncRequest): QfSyncPage {
            syncs++
            return QfSyncPage(
                listOf(QfContentChange.Snapshot(resource, "/api/v4/resources/snapshots/mushafs/1")),
                null,
                "token",
            )
        }
        override suspend fun snapshot(relativePath: String) = QfSnapshot(resource, listOf(row))
    }

    private inner class ReportingApi(private val httpCalls: Int) : QfContentSyncApi, QfNetworkCallReporter {
        var syncs = 0
        private var reporter: () -> Unit = {}
        override fun setNetworkCallReporter(reporter: () -> Unit) {
            this.reporter = reporter
        }
        override suspend fun sync(request: QfSyncRequest): QfSyncPage {
            syncs++
            return QfSyncPage(
                listOf(QfContentChange.Snapshot(resource, "/api/v4/resources/snapshots/mushafs/1")),
                null,
                "token",
            )
        }
        override suspend fun snapshot(relativePath: String): QfSnapshot {
            repeat(httpCalls) { reporter() }
            return QfSnapshot(resource, listOf(row))
        }
    }

    private class CountingApi(private val onSync: () -> QfSyncPage) : QfContentSyncApi {
        var syncs = 0
        override suspend fun sync(request: QfSyncRequest): QfSyncPage {
            syncs++
            return onSync()
        }
        override suspend fun snapshot(relativePath: String): QfSnapshot = error("offline")
    }

    private class Store(
        private var state: QfSyncState? = null,
        private var rows: List<QfCacheRow> = emptyList(),
    ) : QfContentSyncStore {
        var rowReads = 0
        override fun state(filter: QfResourceFilter) = state?.takeIf { it.filter == filter }
        override fun rows(resource: QfResource, recordType: String, recordKeyPrefix: String?): List<QfCacheRow> {
            rowReads++
            return rows.filter { it.resource == resource && it.recordType == recordType }
        }
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
