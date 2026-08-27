package com.beautifulquran.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
    private val syncer = QfContentSyncer(
        counted(api), store, beforeApply = ::markRequestsSettled, nowMs = nowMs,
    )
    private var syncing = false
    @Volatile private var cachedState: QfSyncState? = null
    @Volatile private var parsedToken: String? = null
    @Volatile private var unreadableToken: String? = null
    @Volatile private var scheduledToken: String? = null
    @Volatile private var blockReadRefresh = false
    private var parsedWords = emptyMap<String, RuntimeMushafWord>()
    private var parsedBySurah = emptyMap<Int, Map<Pair<Int, Int>, RuntimeMushafWord>>()
    private var refreshJob: Job? = null
    private var expiryJob: Job? = null
    private val _changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes: SharedFlow<Unit> = _changes

    init {
        (api as? QfNetworkCallReporter)?.setNetworkCallReporter(::countApiCall)
    }

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

    /** Launch / connectivity hook. Never retries a failed refresh from a reader lookup. */
    fun refreshIfNeeded() {
        blockReadRefresh = false
        val state = rememberedState()
        rememberUpdatedAt(state)
        scheduleChecks(state)
        val age = state?.let { nowMs() - it.updatedAtMs }
        if (age == null || age !in 0..QF_REVALIDATE_AFTER_MS) refresh()
    }

    fun refresh() {
        synchronized(this) {
            if (syncing) return
            syncing = true
        }
        blockReadRefresh = false
        _diagnostics.update {
            RuntimeCacheDiagnostics(it.apiCalls, it.resources, false)
        }
        updateResource {
            it.copy(updatedAtMs = rememberedState()?.updatedAtMs, refreshing = true, lastError = null)
        }
        scope.launch {
            try {
                syncer.sync(FILTER)
                val state = store.state(FILTER)
                cachedState = state
                parsedToken = null
                unreadableToken = null
                rememberUpdatedAt(state)
                scheduleChecks(state)
                _changes.tryEmit(Unit)
            } catch (error: Exception) {
                blockReadRefresh = true
                updateResource { it.copy(lastError = error.message ?: error::class.simpleName) }
            } finally {
                synchronized(this@RuntimeMushafCache) { syncing = false }
                updateResource { it.copy(refreshing = false) }
            }
        }
    }

    private fun currentWords(): Map<String, RuntimeMushafWord>? {
        val state = rememberedState()
        rememberUpdatedAt(state)
        val now = nowMs()
        if (!blockReadRefresh && (state == null || now - state.updatedAtMs !in 0..QF_REVALIDATE_AFTER_MS)) {
            refresh()
        }
        if (!isQfContentFresh(state?.updatedAtMs, now)) return null
        val current = state ?: return null
        if (parsedToken == current.token) return parsedWords
        if (unreadableToken == current.token) return null
        return install(current)
    }

    private fun install(expected: QfSyncState): Map<String, RuntimeMushafWord>? {
        val parsed = runCatching {
            store.rows(RESOURCE, RECORD_TYPE).associate { row ->
                val word = parseMushafWord(json.parseToJsonElement(row.payload).jsonObject)
                check(row.recordKey == key(word.surahId, word.ayahNumber, word.position))
                row.recordKey to word
            }.also { check(it.size >= minimumWords) }
        }.getOrElse { error ->
            unreadableToken = expected.token
            updateResource { it.copy(lastError = error.message ?: error::class.simpleName) }
            return null
        }
        val bySurah = parsed.values.groupBy { it.surahId }.mapValues { (_, words) ->
            words.associateBy { word -> word.ayahNumber to word.position }
        }
        synchronized(this) {
            if (cachedState?.token != expected.token) {
                return if (parsedToken == cachedState?.token) parsedWords else null
            }
            parsedWords = parsed
            parsedBySurah = bySurah
            parsedToken = expected.token
            unreadableToken = null
            return parsed
        }
    }

    private fun rememberedState(): QfSyncState? {
        cachedState?.let { return it }
        return store.state(FILTER).also { cachedState = it }
    }

    private fun rememberUpdatedAt(state: QfSyncState?) {
        val updated = state?.updatedAtMs
        if (_diagnostics.value.resources[MUSHAF_ID]?.updatedAtMs != updated) {
            updateResource { it.copy(updatedAtMs = updated) }
        }
    }

    private fun counted(api: QfContentSyncApi) = object : QfContentSyncApi {
        override suspend fun sync(request: QfSyncRequest): QfSyncPage {
            if (api !is QfNetworkCallReporter) countApiCall()
            return api.sync(request)
        }

        override suspend fun snapshot(relativePath: String): QfSnapshot {
            if (api !is QfNetworkCallReporter) countApiCall()
            return api.snapshot(relativePath)
        }
    }

    private fun countApiCall() {
        _diagnostics.update {
            RuntimeCacheDiagnostics(it.apiCalls + 1, it.resources, false)
        }
    }

    private fun markRequestsSettled() {
        _diagnostics.update {
            RuntimeCacheDiagnostics(it.apiCalls, it.resources, true)
        }
    }

    /** Refresh while current, then notify readers exactly when retained data expires. */
    private fun scheduleChecks(state: QfSyncState?) {
        if (state == null) {
            refreshJob?.cancel()
            expiryJob?.cancel()
            scheduledToken = null
            return
        }
        if (scheduledToken == state.token) return
        refreshJob?.cancel()
        expiryJob?.cancel()
        scheduledToken = state.token
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
            val current = cachedState ?: store.state(FILTER)
            if (current?.token == token && !isQfContentFresh(current.updatedAtMs, nowMs())) {
                parsedToken = null
                _changes.tryEmit(Unit)
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
                current.requestsSettled,
            )
        }
    }

    private companion object {
        const val MUSHAF_ID = 1
        const val RECORD_TYPE = "mushaf_word"
        val FILTER = QfResourceFilter("mushafs:1")
        val RESOURCE = QfResource("mushafs", 1)
        fun key(surahId: Int, ayah: Int, position: Int) = "$surahId:$ayah:$position"
    }
}

internal fun parseMushafWord(record: JsonObject): RuntimeMushafWord {
    val surah = record.int("surah_id")
    val ayah = record.int("ayah_number")
    val position = record.int("position")
    val qcf = record.string("qcf_v2")
    val qcfPage = record.int("qcf_page")
    val qcfLine = record.int("qcf_line")
    val spanEnd = record.int("qcf_span_end")
    val ayahPage = record.int("ayah_page")
    check(record.string("record_type") == "mushaf_word") { "Unexpected mushaf record type" }
    check(surah in 1..114 && ayah > 0 && position > 0) { "Invalid mushaf word identity" }
    check(record.string("record_key") == "$surah:$ayah:$position") { "Mushaf record_key mismatch" }
    check(
        if (qcf.isEmpty()) qcfPage == 0 && qcfLine == 0
        else qcfPage in 1..604 && qcfLine > 0,
    ) { "Invalid mushaf QCF coordinates" }
    check(spanEnd >= position) { "Invalid mushaf qcf_span_end" }
    check(ayahPage in 1..604) { "Invalid mushaf ayah_page" }
    return RuntimeMushafWord(
        surahId = surah,
        ayahNumber = ayah,
        position = position,
        translation = record.string("translation_en"),
        transliteration = record.string("transliteration"),
        qcfV2 = qcf,
        qcfPage = qcfPage,
        qcfLine = qcfLine,
        qcfSpanEnd = spanEnd,
        ayahPage = ayahPage,
    )
}

/** Hold the cold-start cover only for a missing/expired cache that can still refresh. */
internal fun runtimeMushafEntranceReady(status: RuntimeCacheStatus, nowMs: Long): Boolean =
    status.phase == RuntimeCachePhase.ERROR ||
        status.updatedAtMs?.let { isQfContentFresh(it, nowMs) } == true

private fun JsonObject.int(name: String) =
    get(name)?.jsonPrimitive?.intOrNull ?: error("Mushaf field $name is missing")

private fun JsonObject.string(name: String) =
    get(name)?.jsonPrimitive?.contentOrNull ?: error("Mushaf field $name is missing")
