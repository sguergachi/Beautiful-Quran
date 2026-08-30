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
                    contentAgeMs = 10L,
                ),
            ),
            snapshots = listOf(QfSnapshot(resource, listOf(row))),
        )

        QfContentSyncer(api, store) { 42L }.sync(filter)

        assertEquals(listOf(QfSyncRequest.Bootstrap(filter), QfSyncRequest.NextPage("/api/v4/resources/sync?cursor=second")), api.requests)
        assertEquals("next-token", store.savedState?.token)
        assertEquals(32L, store.savedState?.updatedAtMs)
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
    fun `successful checkpoint records exact API usage for that refresh`() = runBlocking {
        var calls = 0L
        val store = FakeStore()
        val api = object : QfContentSyncApi {
            override suspend fun sync(request: QfSyncRequest): QfSyncPage {
                calls++
                return QfSyncPage(
                    listOf(QfContentChange.Snapshot(resource, "/api/v4/resources/snapshots/recitations/7")),
                    null,
                    "new-token",
                )
            }

            override suspend fun snapshot(relativePath: String): QfSnapshot {
                calls++
                return QfSnapshot(resource, listOf(row))
            }
        }

        QfContentSyncer(api, store).sync(filter) { calls }

        assertEquals(2L, store.savedState?.lastRefreshApiCalls)
    }

    @Test
    fun `freshness expires after exactly seven days`() {
        assertTrue(isQfContentFresh(0L, QF_MAX_CACHE_AGE_MS))
        assertFalse(isQfContentFresh(0L, QF_MAX_CACHE_AGE_MS + 1))
        assertFalse(isQfContentFresh(null, 0L))
    }

    @Test
    fun `invalid provider age never advances checkpoint`() = runBlocking {
        val store = FakeStore(QfSyncState(filter, "old-token", 1L))
        val api = FakeApi(
            pages = listOf(
                QfSyncPage(
                    emptyList(),
                    null,
                    "new-token",
                    QF_MAX_CACHE_AGE_MS + 1,
                ),
            ),
            snapshots = emptyList(),
        )

        runCatching { QfContentSyncer(api, store) { 42L }.sync(filter) }

        assertEquals("old-token", store.savedState?.token)
        assertEquals(1L, store.savedState?.updatedAtMs)
    }

    @Test
    fun `duplicate row delivery is idempotent and later deletion wins`() = runBlocking {
        val store = FakeStore()
        QfContentSyncer(
            FakeApi(
                pages = listOf(
                    QfSyncPage(
                        listOf(
                            QfContentChange.Upsert(row),
                            QfContentChange.Upsert(row),
                            QfContentChange.FreshnessMarker,
                        ),
                        null,
                        "one",
                    ),
                ),
                snapshots = emptyList(),
            ),
            store,
        ).sync(filter)
        assertEquals(listOf(row), store.rows)

        QfContentSyncer(
            FakeApi(
                pages = listOf(
                    QfSyncPage(
                        listOf(
                            QfContentChange.DeleteRow(resource, "audio_file", "1:1"),
                        ),
                        null,
                        "two",
                    ),
                ),
                snapshots = emptyList(),
            ),
            store,
        ).sync(filter)
        assertTrue(store.rows.isEmpty())
        assertEquals("two", store.savedState?.token)
    }

    @Test
    fun `full snapshot computes only changed row mutations`() {
        val removed = row.copy(recordKey = "1:2", payload = "old")
        val changed = row.copy(payload = "new")
        val added = row.copy(recordKey = "1:3", payload = "added")

        val delta = qfSnapshotDelta(
            existing = listOf(row, removed),
            incoming = listOf(changed, added),
        )

        assertEquals(listOf(changed, added), delta.upserts)
        assertEquals(listOf(removed), delta.deletes)
        assertTrue(qfSnapshotDelta(listOf(row), listOf(row)).upserts.isEmpty())
        assertTrue(qfSnapshotDelta(listOf(row), listOf(row)).deletes.isEmpty())
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

        override fun rows(
            resource: QfResource,
            recordType: String,
            recordKeyPrefix: String?,
        ) = rows.filter {
            it.resource == resource &&
                it.recordType == recordType &&
                (recordKeyPrefix == null || it.recordKey.startsWith(recordKeyPrefix))
        }

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
            lastRefreshApiCalls: Long?,
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
            savedState = QfSyncState(filter, nextToken, nowMs, lastRefreshApiCalls)
        }
    }
}
