package com.beautifulquran.data

import com.beautifulquran.data.model.Segment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

data class CachedTimingRows(
    val segments: Map<Int, List<Segment>>,
    val audioOnsets: Map<Int, Long>,
)

/**
 * Local-first timing reader. Fresh cached rows win; otherwise the caller uses
 * bundled quran-align while a single background Content Sync refresh runs.
 */
class RuntimeTimingCache(
    api: QfContentSyncApi,
    private val store: QfContentSyncStore,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val syncer = QfContentSyncer(api, store, nowMs)
    private val syncing = mutableSetOf<Int>()
    private val _changes = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val changes: SharedFlow<Int> = _changes

    fun rows(reciterId: Int, surahId: Int): CachedTimingRows? {
        if (reciterId !in RUNTIME_TIMING_RECITERS) return null
        val filter = filter(reciterId)
        val state = store.state(filter)
        val now = nowMs()
        if (state == null || now - state.updatedAtMs !in 0..QF_REVALIDATE_AFTER_MS) {
            refresh(reciterId)
        }
        if (!isQfContentFresh(state?.updatedAtMs, now)) return null

        return runCatching {
            val parsed = store.rows(
                resource(reciterId),
                TIMING_RECORD,
                "$surahId:",
            ).map { row ->
                val payload = json.parseToJsonElement(row.payload).jsonObject
                val chapter = payload["surah_id"]!!.jsonPrimitive.long.toInt()
                val ayah = payload["ayah_number"]!!.jsonPrimitive.long.toInt()
                check(chapter == surahId)
                check(payload["record_type"]!!.jsonPrimitive.content == TIMING_RECORD)
                check(payload["record_key"]!!.jsonPrimitive.content == row.recordKey)
                check(row.recordKey == "$chapter:$ayah")
                val segments = payload["segments"]!!.jsonArray.map { raw ->
                    val values = raw.jsonArray
                    Segment(
                        position = values[0].jsonPrimitive.long.toInt(),
                        startMs = values[1].jsonPrimitive.long,
                        endMs = values[2].jsonPrimitive.long,
                    ).also {
                        check(values.size == 3)
                        check(it.position > 0 && it.startMs >= 0 && it.endMs > it.startMs)
                    }
                }
                Triple(chapter, ayah, segments) to
                    (payload["audio_onset_ms"]?.jsonPrimitive?.long ?: 0L)
            }
            check(parsed.map { it.first.second }.distinct().size == parsed.size)
            if (parsed.isEmpty()) return@runCatching null
            CachedTimingRows(
                segments = parsed.associate { it.first.second to it.first.third },
                audioOnsets = parsed.mapNotNull { (timing, onset) ->
                    onset.takeIf { it > 0L }?.let { timing.second to it }
                }.toMap(),
            )
        }.getOrNull()
    }

    fun refresh(reciterId: Int) {
        if (reciterId !in RUNTIME_TIMING_RECITERS) return
        synchronized(syncing) {
            if (!syncing.add(reciterId)) return
        }
        scope.launch {
            try {
                syncer.sync(filter(reciterId))
                _changes.emit(reciterId)
            } catch (_: Exception) {
                // The bundled quran-align row remains the honest offline path.
            } finally {
                synchronized(syncing) { syncing.remove(reciterId) }
            }
        }
    }

    private fun filter(reciterId: Int) = QfResourceFilter("recitations:$reciterId")
    private fun resource(reciterId: Int) = QfResource("recitations", reciterId.toLong())

    private companion object {
        const val TIMING_RECORD = "timing"
        val RUNTIME_TIMING_RECITERS = setOf(1, 2, 3, 4, 5, 7)
    }
}
