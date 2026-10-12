package com.beautifulquran.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeTimingCacheTest {
    private val counts = mapOf("1:1" to 2)

    @Test
    fun `entrance waits for all six accepted or withdrawn resources and then relaunch calls no API`() = runTest {
        val store = Store()
        val calls = mutableListOf<Int>()
        val cache = RuntimeTimingCache(RuntimeTimingApi {
            calls += it
            timingTestPayload(it)
        }, store, backgroundScope, { counts }, { 100 })
        assertFalse(cache.status.value.entranceReady)
        cache.refreshIfNeeded()
        runCurrent()
        assertEquals(RUNTIME_TIMING_PROFILES.keys.toList(), calls)
        assertTrue(cache.status.value.entranceReady)
        assertEquals(6, cache.status.value.settledReciters)
        val relaunched = RuntimeTimingCache(RuntimeTimingApi { error("must not request") }, store, backgroundScope, { counts }, { 101 })
        relaunched.refreshIfNeeded()
        runCurrent()
        assertTrue(relaunched.status.value.entranceReady)
        assertEquals(listOf(1, 2, 1, 2), relaunched.chapter(1, 1)?.segments?.get(1)?.map { it.position })
    }

    @Test
    fun `offline copies stay readable beyond seven days without advancing their successful check`() = runTest {
        val store = filledStore()
        val before = store.retained.toMap()
        val cache = RuntimeTimingCache(RuntimeTimingApi { error("offline") }, store, backgroundScope, { counts }, { QF_MAX_CACHE_AGE_MS + 100 })
        cache.refreshIfNeeded()
        runCurrent()
        assertTrue(cache.status.value.entranceReady)
        assertEquals(before, store.retained)
        assertEquals(80L, cache.chapter(1, 1)?.audioOnsets?.get(1))
        assertEquals("offline", cache.status.value.lastError)
    }

    @Test
    fun `an unknown source or malformed pack preserves prior rows while other reciters can commit`() = runTest {
        val store = filledStore()
        val original = store.snapshots.getValue(1)
        val cache = RuntimeTimingCache(RuntimeTimingApi {
            if (it == 1) "{}" else timingTestPayload(it)
        }, store, backgroundScope, { counts }, { 100 })
        cache.refresh()
        runCurrent()
        assertEquals(original, store.snapshots.getValue(1))
        assertEquals(1L, store.retained.getValue(1).checkedAtMs)
        assertEquals(100L, store.retained.getValue(2).checkedAtMs)
        assertTrue(cache.chapter(1, 1)!!.segments.isNotEmpty())
        assertTrue(cache.status.value.lastError != null)
    }

    @Test
    fun `failed atomic publication leaves the accepted revision and onset intact`() = runTest {
        val store = filledStore().apply { failWrite = true }
        val before = store.snapshots.toMap()
        val cache = RuntimeTimingCache(RuntimeTimingApi { timingTestPayload(it) }, store, backgroundScope, { counts }, { 100 })
        cache.refresh()
        runCurrent()
        assertEquals(before, store.snapshots)
        assertEquals(80L, cache.chapter(1, 1)?.audioOnsets?.get(1))
        assertEquals(1L, store.retained.getValue(1).checkedAtMs)
    }

    @Test
    fun `only the withdrawn reciter is purged and existing peers remain available`() = runTest {
        val store = filledStore()
        val cache = RuntimeTimingCache(RuntimeTimingApi {
            if (it == 1) throw QfTimingResourceDeletedException()
            timingTestPayload(it)
        }, store, backgroundScope, { counts }, { 100 })
        cache.refresh()
        runCurrent()
        assertFalse(cache.available(1))
        assertNull(cache.chapter(1, 1))
        assertTrue(cache.available(2))
        assertTrue(cache.status.value.entranceReady)
        assertNull(store.retained.getValue(1).revision)
        assertFalse(store.snapshots.containsKey(1))
    }

    @Test
    fun `global access rejection purges every reciter and signals the other QF cache`() = runTest {
        val store = filledStore()
        var purgedPeer = false
        val cache = RuntimeTimingCache(RuntimeTimingApi { throw QfAccessRevokedException() }, store, backgroundScope, { counts }, { 100 }, { purgedPeer = true })
        cache.refresh()
        runCurrent()
        assertTrue(purgedPeer)
        assertTrue(store.retained.isEmpty())
        assertTrue(store.snapshots.isEmpty())
        assertFalse(cache.available(1))
        assertTrue(cache.status.value.entranceReady)
    }

    @Test
    fun `a word-cache revocation cannot be undone by an in-flight timing response`() = runTest {
        val pending = CompletableDeferred<String>()
        val store = filledStore()
        val cache = RuntimeTimingCache(RuntimeTimingApi { pending.await() }, store, backgroundScope, { counts }, { 100 })
        cache.refresh()
        runCurrent()
        cache.clearRevokedContent()
        pending.complete(timingTestPayload())
        runCurrent()
        assertTrue(store.snapshots.isEmpty())
        assertFalse(cache.available(1))
    }

    @Test
    fun `concurrent refreshes coalesce and unchanged revisions advance checks without reloading the reader`() = runTest {
        val pending = CompletableDeferred<Unit>()
        val store = filledStore()
        var calls = 0
        var changes = 0
        val cache = RuntimeTimingCache(RuntimeTimingApi {
            calls++
            pending.await()
            timingTestPayload(it)
        }, store, backgroundScope, { counts }, { 100 })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { cache.changes.drop(1).collect { changes++ } }
        cache.refresh()
        cache.refresh()
        runCurrent()
        assertEquals(1, calls)
        pending.complete(Unit)
        runCurrent()
        assertEquals(6, calls)
        assertEquals(0, changes)
        assertEquals(0L, cache.generation(1))
        assertTrue(store.retained.values.all { it.checkedAtMs == 100L })
    }

    @Test
    fun `accepted changed revisions notify an already-open reader`() = runTest {
        val store = filledStore()
        var changes = 0
        val rows = Json.parseToJsonElement("[[1,1,[[1,100,250],[2,300,450],[1,650,750],[2,800,1000]],80]]").jsonArray
        val cache = RuntimeTimingCache(RuntimeTimingApi { timingTestPayload(it, rows) }, store, backgroundScope, { counts }, { 100 })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { cache.changes.drop(1).collect { changes++ } }
        cache.refresh()
        runCurrent()
        assertEquals(6, changes)
        assertEquals(1L, cache.generation(1))
        assertEquals(650L, cache.chapter(1, 1)?.segments?.get(1)?.get(2)?.startMs)
    }

    @Test
    fun `a peer reciter update cannot invalidate the active reciters timing generation`() = runTest {
        val store = filledStore()
        val changed = mutableListOf<Map<Int, Long>>()
        val rows = Json.parseToJsonElement("[[1,1,[[1,100,250],[2,300,450],[1,650,750],[2,800,1000]],80]]").jsonArray
        val cache = RuntimeTimingCache(RuntimeTimingApi {
            if (it == 2) timingTestPayload(it, rows) else timingTestPayload(it)
        }, store, backgroundScope, { counts }, { 100 })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { cache.changes.drop(1).collect { changed += it } }
        cache.refresh()
        runCurrent()
        assertEquals(listOf(mapOf(2 to 1L)), changed)
        assertEquals(0L, cache.generation(1))
        assertEquals(1L, cache.generation(2))
    }

    @Test
    fun `six-day timer revalidates while retries remain bounded and connectivity starts another episode`() = runTest {
        val store = filledStore(0)
        var calls = 0
        val cache = RuntimeTimingCache(RuntimeTimingApi {
            calls++
            error("offline")
        }, store, backgroundScope, { counts }, { testScheduler.currentTime })
        cache.refreshIfNeeded()
        runCurrent()
        assertEquals(0, calls)
        advanceTimeBy(QF_REVALIDATE_AFTER_MS)
        runCurrent()
        assertEquals(6, calls)
        advanceTimeBy(500_000)
        runCurrent()
        assertEquals(30, calls)
        cache.refreshIfNeeded()
        runCurrent()
        assertEquals(36, calls)
        assertTrue(cache.available(1))
    }

    @Test
    fun `a delayed active reader cannot lose its change behind five peer publications`() = runTest {
        val store = filledStore()
        val rows = Json.parseToJsonElement("[[1,1,[[1,100,250],[2,300,450],[1,650,750],[2,800,1000]],80]]").jsonArray
        val cache = RuntimeTimingCache(RuntimeTimingApi { timingTestPayload(it, rows) }, store, backgroundScope, { counts }, { 100 })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val observed = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            cache.changes.observeTimingGeneration(MutableStateFlow<Int?>(1)).collect { (_, generation) ->
                observed += generation
                if (generation == 0L) {
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
        runCurrent()
        assertTrue(entered.isCompleted)
        cache.refresh()
        runCurrent()
        assertEquals(listOf(0L), observed)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(0L, 1L), observed)
        assertEquals(650L, cache.chapter(1, 1)?.segments?.get(1)?.get(2)?.startMs)
    }

    @Test
    fun `a delayed reader observes withdrawal after global purge and subsequent peer refills`() = runTest {
        val cache = RuntimeTimingCache(RuntimeTimingApi {
            if (it == 1) throw QfTimingResourceDeletedException()
            timingTestPayload(it)
        }, filledStore(), backgroundScope, { counts }, { 100 })
        val release = CompletableDeferred<Unit>()
        val observed = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            cache.changes.observeTimingGeneration(MutableStateFlow<Int?>(1)).collect { (_, generation) ->
                observed += generation
                if (generation == 0L) release.await()
            }
        }
        runCurrent()
        cache.clearRevokedContent()
        cache.refresh()
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(2L, observed.last())
        assertFalse(cache.available(1))
        assertTrue(cache.available(2))
        assertNull(cache.chapter(1, 1))
    }

    @Test
    fun `peer generations do not reload the reader but changing selection observes the latest provider`() = runTest {
        val rows = Json.parseToJsonElement("[[1,1,[[1,100,250],[2,300,450],[1,650,750],[2,800,1000]],80]]").jsonArray
        val cache = RuntimeTimingCache(RuntimeTimingApi {
            if (it == 2) timingTestPayload(it, rows) else timingTestPayload(it)
        }, filledStore(), backgroundScope, { counts }, { 100 })
        val selected = MutableStateFlow<Int?>(1)
        val observed = mutableListOf<Pair<Int, Long>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            cache.changes.observeTimingGeneration(selected).collect { observed += it }
        }
        runCurrent()
        cache.refresh()
        runCurrent()
        assertEquals(listOf(1 to 0L), observed)
        selected.value = 2
        runCurrent()
        assertEquals(listOf(1 to 0L, 2 to 1L), observed)
        cache.clearRevokedContent()
        runCurrent()
        assertEquals(2 to 2L, observed.last())
    }

    @Test
    fun `an unchanged withdrawal tombstone does not invalidate the empty provider again`() = runTest {
        val cache = RuntimeTimingCache(RuntimeTimingApi {
            if (it == 1) throw QfTimingResourceDeletedException()
            timingTestPayload(it)
        }, filledStore(), backgroundScope, { counts }, { 100 })
        cache.refresh()
        runCurrent()
        assertEquals(1L, cache.generation(1))
        cache.refresh()
        runCurrent()
        assertEquals(1L, cache.generation(1))
        assertFalse(cache.available(1))
    }

    private fun filledStore(checkedAtMs: Long = 1): Store = Store().apply {
        RUNTIME_TIMING_PROFILES.keys.forEach { apply(parseRuntimeTimingSnapshot(timingTestPayload(it), it, counts), checkedAtMs) }
    }

    private class Store : RuntimeTimingStore {
        val retained = mutableMapOf<Int, RuntimeTimingState>()
        val snapshots = mutableMapOf<Int, RuntimeTimingSnapshot>()
        var failWrite = false
        override fun states(): Map<Int, RuntimeTimingState> = retained.toMap()
        override fun chapter(reciterId: Int, surahId: Int): RuntimeTimingChapter {
            val rows = snapshots.getValue(reciterId).rows.filter { it.surahId == surahId }
            return RuntimeTimingChapter(
                rows.associate { it.ayahNumber to QuranRepository.parseSegments(it.segments) },
                rows.associate { it.ayahNumber to it.audioOnsetMs },
            )
        }
        override fun apply(snapshot: RuntimeTimingSnapshot, checkedAtMs: Long) {
            if (failWrite) error("atomic write failed")
            snapshots[snapshot.reciterId] = snapshot
            retained[snapshot.reciterId] = RuntimeTimingState(snapshot.revision, checkedAtMs)
        }
        override fun withdraw(reciterId: Int, checkedAtMs: Long) {
            snapshots.remove(reciterId)
            retained[reciterId] = RuntimeTimingState(null, checkedAtMs)
        }
        override fun clear() {
            retained.clear()
            snapshots.clear()
        }
    }
}
