package com.beautifulquran.data

import com.beautifulquran.data.model.Segment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

internal fun interface RuntimeTimingApi {
    suspend fun fetch(reciterId: Int): String
}

/** A null revision records an explicitly withdrawn resource, rather than a fabricated empty pack. */
internal data class RuntimeTimingState(val revision: String?, val checkedAtMs: Long)

internal interface RuntimeTimingStore {
    fun states(): Map<Int, RuntimeTimingState>
    fun chapter(reciterId: Int, surahId: Int): RuntimeTimingChapter
    fun apply(snapshot: RuntimeTimingSnapshot, checkedAtMs: Long)
    fun withdraw(reciterId: Int, checkedAtMs: Long)
    fun clear()
}

internal data class RuntimeTimingChapter(
    val segments: Map<Int, List<Segment>> = emptyMap(),
    val audioOnsets: Map<Int, Long> = emptyMap(),
)

data class RuntimeTimingStatus(
    val settledReciters: Int = 0,
    val totalReciters: Int = RUNTIME_TIMING_PROFILES.size,
    val refreshing: Boolean = false,
    val completed: Int = 0,
    val lastError: String? = null,
) {
    /** A failed first fill releases the entrance; existing offline data never holds it. */
    val entranceReady: Boolean get() = settledReciters == totalReciters || lastError != null
}

internal class QfTimingResourceDeletedException : Exception("QF timing resource was withdrawn")

/** Maintained, independently committed timing copies remain readable throughout QF outages. */
class RuntimeTimingCache internal constructor(
    private val api: RuntimeTimingApi,
    private val store: RuntimeTimingStore,
    private val scope: CoroutineScope,
    private val wordCounts: () -> Map<String, Int>,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onAccessRevoked: () -> Unit = {},
) {
    private val _status = MutableStateFlow(RuntimeTimingStatus())
    val status: StateFlow<RuntimeTimingStatus> = _status
    private val _changes = MutableStateFlow<Map<Int, Long>>(emptyMap())
    /** Latest accepted or purged generation for every provider; slow readers cannot lose an invalidation. */
    val changes: StateFlow<Map<Int, Long>> = _changes
    private var states: Map<Int, RuntimeTimingState>? = null
    private var syncing = false
    private var accessGeneration = 0L
    private var retryAttempt = 0
    private var retryJob: Job? = null
    private var refreshJob: Job? = null
    private val canonicalCounts by lazy(wordCounts)
    internal fun generation(reciterId: Int): Long = _changes.value[reciterId] ?: 0L

    internal fun available(reciterId: Int): Boolean = synchronized(this) {
        rememberedStates()[reciterId]?.revision != null
    }

    internal fun chapter(reciterId: Int, surahId: Int): RuntimeTimingChapter? = synchronized(this) {
        if (!available(reciterId)) null else store.chapter(reciterId, surahId)
    }

    /** Launch and restored-connectivity hooks start a new bounded retry episode when due. */
    fun refreshIfNeeded() = refresh(force = false)

    fun refresh() = refresh(force = true)

    private fun refresh(force: Boolean, resetBackoff: Boolean = true) {
        val expectedGeneration = synchronized(this) {
            if (syncing) return
            syncing = true
            if (resetBackoff) {
                retryJob?.cancel()
                retryAttempt = 0
            }
            accessGeneration
        }
        scope.launch {
            try {
                val targets = synchronized(this@RuntimeTimingCache) {
                    val retained = rememberedStates()
                    RUNTIME_TIMING_PROFILES.keys.filter { force || timingRefreshDue(retained[it], nowMs()) }
                }
                if (targets.isEmpty()) return@launch
                _status.value = _status.value.copy(refreshing = true, completed = 0, lastError = null)
                var failure: Exception? = null
                targets.forEachIndexed { index, reciterId ->
                    try {
                        val snapshot = parseRuntimeTimingSnapshot(api.fetch(reciterId), reciterId, canonicalCounts)
                        synchronized(this@RuntimeTimingCache) {
                            checkGeneration(expectedGeneration)
                            val previous = rememberedStates()[reciterId]
                            val checked = nowMs()
                            store.apply(snapshot, checked)
                            states = rememberedStates() + (reciterId to RuntimeTimingState(snapshot.revision, checked))
                            if (previous?.revision != snapshot.revision) {
                                changed(reciterId)
                            }
                        }
                    } catch (_: QfTimingResourceDeletedException) {
                        synchronized(this@RuntimeTimingCache) {
                            checkGeneration(expectedGeneration)
                            val previous = rememberedStates()[reciterId]
                            val checked = nowMs()
                            store.withdraw(reciterId, checked)
                            states = rememberedStates() + (reciterId to RuntimeTimingState(null, checked))
                            if (previous == null || previous.revision != null) changed(reciterId)
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException || error is QfAccessRevokedException) throw error
                        failure = failure ?: error
                        _status.value = _status.value.copy(lastError = error.message ?: error::class.simpleName)
                    }
                    synchronized(this@RuntimeTimingCache) { checkGeneration(expectedGeneration) }
                    _status.value = _status.value.copy(completed = index + 1, settledReciters = rememberedStates().size)
                }
                failure?.let { throw it }
                retryAttempt = 0
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is QfAccessRevokedException) {
                    clearRevokedContent()
                    onAccessRevoked()
                }
                _status.value = _status.value.copy(lastError = error.message ?: error::class.simpleName)
                if (error !is QfAccessRevokedException) scheduleRetry()
            } finally {
                synchronized(this@RuntimeTimingCache) {
                    syncing = false
                    _status.value = _status.value.copy(refreshing = false)
                    scheduleRefresh()
                }
            }
        }
    }

    /** Shared access rejection purges the timing cache as well as the word/QCF cache. */
    internal fun clearRevokedContent() = synchronized(this) {
        accessGeneration++
        store.clear()
        states = emptyMap()
        retryJob?.cancel()
        refreshJob?.cancel()
        _status.value = RuntimeTimingStatus(lastError = "QF content access was revoked")
        _changes.value = RUNTIME_TIMING_PROFILES.keys.associateWith { generation(it) + 1 }
    }

    private fun changed(reciterId: Int) {
        _changes.value = _changes.value + (reciterId to generation(reciterId) + 1)
    }

    private fun checkGeneration(expected: Long) {
        if (accessGeneration != expected) throw CancellationException("Timing cache was purged during refresh")
    }

    private fun rememberedStates(): Map<Int, RuntimeTimingState> = synchronized(this) {
        states ?: store.states().also {
            states = it
            _status.value = _status.value.copy(settledReciters = it.size)
        }
    }

    private fun scheduleRefresh() {
        refreshJob?.cancel()
        val next = states?.values?.minOfOrNull { it.checkedAtMs + QF_REVALIDATE_AFTER_MS } ?: return
        if (next <= nowMs()) return
        refreshJob = scope.launch {
            delay(next - nowMs())
            refreshIfNeeded()
        }
    }

    private fun scheduleRetry() {
        if (retryAttempt >= RETRY_DELAYS_MS.size) return
        val wait = RETRY_DELAYS_MS[retryAttempt++]
        retryJob = scope.launch {
            delay(wait)
            refresh(force = false, resetBackoff = false)
        }
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(5_000, 15_000, 60_000, 5 * 60_000)
    }
}

internal fun timingRefreshDue(state: RuntimeTimingState?, nowMs: Long): Boolean =
    state == null || nowMs - state.checkedAtMs !in 0 until QF_REVALIDATE_AFTER_MS

/** React to the selected provider only, including a selection overtaking a pending refresh. */
internal fun Flow<Map<Int, Long>>.observeTimingGeneration(reciterIds: Flow<Int?>): Flow<Pair<Int, Long>> =
    combine(this, reciterIds.distinctUntilChanged()) { generations, reciterId ->
        reciterId?.let { it to (generations[it] ?: 0L) }
    }.filterNotNull().distinctUntilChanged()
