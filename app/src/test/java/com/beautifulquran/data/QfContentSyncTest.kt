package com.beautifulquran.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QfContentSyncTest {
    private val filter = QfResourceFilter("recitations:7")
    private val resource = QfResource("recitations", 7)
    private val row = QfCacheRow(resource, "audio_file", "1:1", "{\"id\":1}", "2026-08-10")

    @Test
    fun `bootstrap follows pages, replaces snapshots, and checkpoints only at the end`() = runBlocking {
        val store = FakeStore()
        val api = FakeApi(
            pages = listOf(
                QfSyncPage(
                    changes = listOf(QfContentChange.Snapshot(resource, "/api/v4/resources/snapshots/recitations/7")),
                    nextPagePath = "/api/v4/resources/sync?cursor=second",
                    nextSyncToken = null,
                ),
                QfSyncPage(
                    changes = listOf(QfContentChange.Upsert(row)),
                    nextPagePath = null,
                    nextSyncToken = "next-token",
                ),
            ),
            snapshots = listOf(QfSnapshot(resource, listOf(row))),
        )

        QfContentSyncer(api, store) { 42L }.sync(filter)

        assertEquals(listOf(QfSyncRequest.Bootstrap(filter), QfSyncRequest.NextPage("/api/v4/resources/sync?cursor=second")), api.requests)
        assertEquals("next-token", store.savedState?.token)
        assertEquals(42L, store.savedState?.updatedAtMs)
        assertEquals(listOf(row), store.rows)
    }

    @Test
    fun `failed snapshot never advances checkpoint`() = runBlocking {
        val store = FakeStore(QfSyncState(filter, "old-token", 1L))
        val api = object : QfContentSyncApi {
            override suspend fun sync(request: QfSyncRequest) = QfSyncPage(
                listOf(QfContentChange.Snapshot(resource, "/api/v4/resources/snapshots/recitations/7")),
                null,
                "new-token",
            )

            override suspend fun snapshot(relativePath: String): QfSnapshot = error("network failed")
        }

        runCatching { QfContentSyncer(api, store).sync(filter) }
        assertEquals("old-token", store.savedState?.token)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `freshness expires after exactly seven days`() {
        assertTrue(isQfContentFresh(0L, QF_MAX_CACHE_AGE_MS))
        assertFalse(isQfContentFresh(0L, QF_MAX_CACHE_AGE_MS + 1))
        assertFalse(isQfContentFresh(null, 0L))
    }

    private class FakeApi(
        private val pages: List<QfSyncPage>,
        private val snapshots: List<QfSnapshot>,
    ) : QfContentSyncApi {
        val requests = mutableListOf<QfSyncRequest>()
        private var page = 0
        private var snapshot = 0

        override suspend fun sync(request: QfSyncRequest): QfSyncPage {
            requests += request
            return pages[page++]
        }

        override suspend fun snapshot(relativePath: String): QfSnapshot = snapshots[snapshot++]
    }

    private class FakeStore(initial: QfSyncState? = null) : QfContentSyncStore {
        var savedState = initial
        var rows = emptyList<QfCacheRow>()

        override fun state(filter: QfResourceFilter) = savedState?.takeIf { it.filter == filter }

        override fun clear() {
            savedState = null
            rows = emptyList()
        }

        override fun apply(
            filter: QfResourceFilter,
            changes: List<QfContentChange>,
            snapshots: List<QfSnapshot>,
            nextToken: String,
            nowMs: Long,
        ) {
            val result = rows.toMutableList()
            val fetched = snapshots.iterator()
            changes.forEach { change ->
                when (change) {
                    is QfContentChange.Snapshot -> {
                        val snapshot = fetched.next()
                        result.removeAll { it.resource == snapshot.resource }
                        result += snapshot.rows
                    }
                    is QfContentChange.Upsert -> {
                        result.removeAll {
                            it.resource == change.row.resource && it.recordType == change.row.recordType && it.recordKey == change.row.recordKey
                        }
                        result += change.row
                    }
                    is QfContentChange.DeleteRow -> result.removeAll {
                        it.resource == change.resource && it.recordType == change.recordType && it.recordKey == change.recordKey
                    }
                    is QfContentChange.DeleteResource -> result.removeAll { it.resource == change.resource }
                    QfContentChange.FreshnessMarker -> Unit
                }
            }
            rows = result
            savedState = QfSyncState(filter, nextToken, nowMs)
        }
    }
}
