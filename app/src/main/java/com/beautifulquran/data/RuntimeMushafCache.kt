package com.beautifulquran.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class RuntimeMushafWord(
    val surahId: Int,
    val ayahNumber: Int,
    val position: Int,
    val translation: String,
    val transliteration: String,
    val qcfV2: String,
    val qcfPage: Int,
    val qcfLine: Int,
    val qcfSpanEnd: Int,
    val ayahPage: Int,
)

/** Seven-day local cache for every Quran.com-derived word and QCF field. */
class RuntimeMushafCache(
    api: QfContentSyncApi,
    private val store: QfContentSyncStore,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val minimumWords: Int = 77_429,
) {
    private val _diagnostics = MutableStateFlow(RuntimeCacheDiagnostics())
    val diagnostics: StateFlow<RuntimeCacheDiagnostics> = _diagnostics
    private val syncer = QfContentSyncer(counted(api), store, nowMs)
    private var syncing = false
    private var parsedToken: String? = null
    private var parsedWords = emptyMap<String, RuntimeMushafWord>()
    private var parsedBySurah = emptyMap<Int, Map<Pair<Int, Int>, RuntimeMushafWord>>()
    private var refreshJob: Job? = null
    private var expiryJob: Job? = null
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes: SharedFlow<Unit> = _changes

    fun status(now: Long = nowMs()): RuntimeCacheStatus {
        val diagnostics = _diagnostics.value
        val resource = diagnostics.resources[MUSHAF_ID]
        val updated = resource?.updatedAtMs
        val phase = when {
            resource?.refreshing == true -> RuntimeCachePhase.REFRESHING
            resource?.lastError != null -> RuntimeCachePhase.ERROR
            updated == null -> RuntimeCachePhase.EMPTY
            !isQfContentFresh(updated, now) -> RuntimeCachePhase.EXPIRED
            now - updated !in 0..QF_REVALIDATE_AFTER_MS -> RuntimeCachePhase.REFRESH_DUE
            else -> RuntimeCachePhase.FRESH
        }
        return RuntimeCacheStatus(
            phase, updated, updated?.plus(QF_REVALIDATE_AFTER_MS),
            updated?.plus(QF_MAX_CACHE_AGE_MS), diagnostics.apiCalls, resource?.lastError,
        )
    }

    fun words(surahId: Int): Map<Pair<Int, Int>, RuntimeMushafWord>? =
        currentWords()?.let { parsedBySurah[surahId].orEmpty() }

    fun word(surahId: Int, ayah: Int, position: Int): RuntimeMushafWord? =
        currentWords()?.get(key(surahId, ayah, position))

    fun allWords(): Collection<RuntimeMushafWord>? = currentWords()?.values
    internal fun snapshotWords(): Map<String, RuntimeMushafWord>? = currentWords()

    fun refreshIfNeeded() {
        val state = store.state(FILTER)
        updateResource { it.copy(updatedAtMs = state?.updatedAtMs) }
        scheduleChecks(state)
        val age = state?.let { nowMs() - it.updatedAtMs }
        if (age == null || age !in 0..QF_REVALIDATE_AFTER_MS) refresh()
    }

    fun refresh() {
        synchronized(this) {
            if (syncing) return
            syncing = true
        }
        updateResource {
            it.copy(updatedAtMs = store.state(FILTER)?.updatedAtMs, refreshing = true, lastError = null)
        }
        scope.launch {
            try {
                syncer.sync(FILTER)
                parsedToken = null
                val state = store.state(FILTER)
                updateResource { it.copy(updatedAtMs = state?.updatedAtMs) }
                scheduleChecks(state)
                _changes.emit(Unit)
            } catch (error: Exception) {
                updateResource { it.copy(lastError = error.message ?: error::class.simpleName) }
            } finally {
                synchronized(this@RuntimeMushafCache) { syncing = false }
                updateResource { it.copy(refreshing = false) }
            }
        }
    }

    private fun currentWords(): Map<String, RuntimeMushafWord>? {
        val state = store.state(FILTER)
        updateResource { it.copy(updatedAtMs = state?.updatedAtMs) }
        if (state == null || nowMs() - state.updatedAtMs !in 0..QF_REVALIDATE_AFTER_MS) refreshIfNeeded()
        if (!isQfContentFresh(state?.updatedAtMs, nowMs())) return null
        val current = state ?: return null
        if (parsedToken == current.token) return parsedWords
        return runCatching {
            store.rows(RESOURCE, RECORD_TYPE).associate { row ->
                val record = json.parseToJsonElement(row.payload).jsonObject
                val word = RuntimeMushafWord(
                    surahId = record.requiredInt("surah_id"),
                    ayahNumber = record.requiredInt("ayah_number"),
                    position = record.requiredInt("position"),
                    translation = record.requiredString("translation_en"),
                    transliteration = record.requiredString("transliteration"),
                    qcfV2 = record.requiredString("qcf_v2"),
                    qcfPage = record.requiredInt("qcf_page"),
                    qcfLine = record.requiredInt("qcf_line"),
                    qcfSpanEnd = record.requiredInt("qcf_span_end"),
                    ayahPage = record.requiredInt("ayah_page"),
                )
                check(row.recordKey == key(word.surahId, word.ayahNumber, word.position))
                row.recordKey to word
            }.also {
                check(it.size >= minimumWords)
                parsedWords = it
                parsedBySurah = it.values.groupBy { word -> word.surahId }.mapValues { (_, words) ->
                    words.associateBy { word -> word.ayahNumber to word.position }
                }
                parsedToken = current.token
            }
        }.getOrNull()
    }

    private fun counted(api: QfContentSyncApi) = object : QfContentSyncApi {
        override suspend fun sync(request: QfSyncRequest): QfSyncPage {
            _diagnostics.update { RuntimeCacheDiagnostics(it.apiCalls + 1, it.resources) }
            return api.sync(request)
        }

        override suspend fun snapshot(relativePath: String): QfSnapshot {
            _diagnostics.update { RuntimeCacheDiagnostics(it.apiCalls + 1, it.resources) }
            return api.snapshot(relativePath)
        }
    }

    /** Refresh while current, then notify readers exactly when retained data expires. */
    private fun scheduleChecks(state: QfSyncState?) {
        refreshJob?.cancel()
        expiryJob?.cancel()
        if (state == null) return
        val token = state.token
        val now = nowMs()
        val refreshDelay = state.updatedAtMs + QF_REVALIDATE_AFTER_MS - now
        if (refreshDelay > 0) {
            refreshJob = scope.launch {
                delay(refreshDelay)
                refresh()
            }
        }
        expiryJob = scope.launch {
            delay((state.updatedAtMs + QF_MAX_CACHE_AGE_MS - now).coerceAtLeast(0) + 1)
            val current = store.state(FILTER)
            if (current?.token == token && !isQfContentFresh(current.updatedAtMs, nowMs())) {
                parsedToken = null
                _changes.emit(Unit)
                refreshIfNeeded()
            }
        }
    }

    private fun updateResource(transform: (RuntimeCacheResource) -> RuntimeCacheResource) {
        _diagnostics.update { current ->
            RuntimeCacheDiagnostics(
                current.apiCalls,
                current.resources + (MUSHAF_ID to transform(
                    current.resources[MUSHAF_ID] ?: RuntimeCacheResource(),
                )),
            )
        }
    }

    private fun kotlinx.serialization.json.JsonObject.requiredInt(name: String) =
        get(name)?.jsonPrimitive?.int ?: error("Mushaf field $name is missing")

    private fun kotlinx.serialization.json.JsonObject.requiredString(name: String) =
        get(name)?.jsonPrimitive?.content ?: error("Mushaf field $name is missing")

    private companion object {
        const val MUSHAF_ID = 1
        const val RECORD_TYPE = "mushaf_word"
        val FILTER = QfResourceFilter("mushafs:1")
        val RESOURCE = QfResource("mushafs", 1)
        fun key(surahId: Int, ayah: Int, position: Int) = "$surahId:$ayah:$position"
    }
}
