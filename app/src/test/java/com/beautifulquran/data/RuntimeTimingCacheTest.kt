package com.beautifulquran.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeTimingCacheTest {
    private val filter = QfResourceFilter("recitations:1")
    private val resource = QfResource("recitations", 1)
    private val row = QfCacheRow(
        resource,
        "timing",
        "2:1",
        """{"record_type":"timing","record_key":"2:1","surah_id":2,"ayah_number":1,"segments":[[1,20,40]],"audio_onset_ms":20}""",
        "1",
    )

    @Test
    fun `missing cache falls back immediately then publishes synced rows`() = runTest {
        val store = FakeStore()
        val api = object : QfContentSyncApi {
            override suspend fun sync(request: QfSyncRequest) = QfSyncPage(
                changes = listOf(QfContentChange.Snapshot(resource, "/api/v4/resources/snapshots/recitations/1")),
                nextPagePath = null,
                nextSyncToken = "token",
            )

            override suspend fun snapshot(relativePath: String) = QfSnapshot(resource, listOf(row))
        }
        val cache = RuntimeTimingCache(api, store, this, nowMs = { 100L })

        assertNull(cache.rows(1, 2))
        advanceUntilIdle()
        val loaded = cache.rows(1, 2)!!
        assertEquals(20L, loaded.segments.getValue(1).single().startMs)
        assertEquals(20L, loaded.audioOnsets[1])
    }

    @Test
    fun `expired cache is never served`() = runTest {
        val store = FakeStore(
            QfSyncState(filter, "old", 0L),
            mutableListOf(row),
        )
        val failing = object : QfContentSyncApi {
            override suspend fun sync(request: QfSyncRequest): QfSyncPage = error("offline")
            override suspend fun snapshot(relativePath: String): QfSnapshot = error("offline")
        }
        val cache = RuntimeTimingCache(
            failing,
            store,
            this,
            nowMs = { QF_MAX_CACHE_AGE_MS + 1 },
        )

        assertNull(cache.rows(1, 2))
        advanceUntilIdle()
        assertNull(cache.rows(1, 2))
    }

    @Test
    fun `six day cache stays readable while background refresh fails`() = runTest {
        val store = FakeStore(
            QfSyncState(filter, "old", 0L),
            mutableListOf(row),
        )
        val failing = object : QfContentSyncApi {
            override suspend fun sync(request: QfSyncRequest): QfSyncPage = error("offline")
            override suspend fun snapshot(relativePath: String): QfSnapshot = error("offline")
        }
        val cache = RuntimeTimingCache(
            failing,
            store,
            this,
            nowMs = { QF_REVALIDATE_AFTER_MS + 1 },
        )

        assertEquals(20L, cache.rows(1, 2)?.segments?.get(1)?.single()?.startMs)
        advanceUntilIdle()
        assertEquals(20L, cache.rows(1, 2)?.segments?.get(1)?.single()?.startMs)
    }

    private class FakeStore(
        private var savedState: QfSyncState? = null,
        private var savedRows: MutableList<QfCacheRow> = mutableListOf(),
    ) : QfContentSyncStore {
        override fun state(filter: QfResourceFilter) = savedState?.takeIf { it.filter == filter }

        override fun rows(
            resource: QfResource,
            recordType: String,
            recordKeyPrefix: String?,
        ) = savedRows.filter {
            it.resource == resource &&
                it.recordType == recordType &&
                (recordKeyPrefix == null || it.recordKey.startsWith(recordKeyPrefix))
        }

        override fun clear() {
            savedState = null
            savedRows.clear()
        }

        override fun apply(
            filter: QfResourceFilter,
            changes: List<QfContentChange>,
            snapshots: List<QfSnapshot>,
            nextToken: String,
            nowMs: Long,
        ) {
            val fetched = snapshots.iterator()
            changes.forEach { change ->
                when (change) {
                    is QfContentChange.Snapshot -> {
                        savedRows.removeAll { it.resource == change.resource }
                        savedRows += fetched.next().rows
                    }
                    is QfContentChange.Upsert -> {
                        savedRows.removeAll {
                            it.resource == change.row.resource &&
                                it.recordType == change.row.recordType &&
                                it.recordKey == change.row.recordKey
                        }
                        savedRows += change.row
                    }
                    is QfContentChange.DeleteRow -> savedRows.removeAll {
                        it.resource == change.resource &&
                            it.recordType == change.recordType &&
                            it.recordKey == change.recordKey
                    }
                    is QfContentChange.DeleteResource -> savedRows.removeAll {
                        it.resource == change.resource
                    }
                    QfContentChange.FreshnessMarker -> Unit
                }
            }
            savedState = QfSyncState(filter, nextToken, nowMs)
        }
    }
}
