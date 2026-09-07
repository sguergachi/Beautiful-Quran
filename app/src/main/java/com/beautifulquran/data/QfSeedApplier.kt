package com.beautifulquran.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Debug-build QF cache seed baked by tools/seed_qf_cache.py.
 *
 * The seed is a genuine Content Sync checkpoint (live token, snapshots,
 * supplements) packaged as a debug-only asset. When the cache holds no
 * mushaf words, the seed is applied through the same transactional
 * [QfContentSyncStore.apply] — with the same validation — as a network sync,
 * so the leaves are full on first paint with zero network. Ordinary
 * incremental refresh then tops it up when online.
 *
 * Total: never throws. A missing, stale, or corrupt seed returns false and
 * the caller falls through to a network bootstrap. A good cache is never
 * touched. Release builds carry no seed asset, so this is inert there.
 */
class QfSeedApplier(
    private val context: Context,
    private val store: QfContentSyncStore,
    private val canonicalWords: () -> Map<Int, Map<Int, List<String>>>,
    private val expectedPages: IntRange = 1..604,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val onValidated: (List<QfContentChange>) -> Unit = {},
) {
    suspend fun applyIfEmpty(): Boolean = withContext(Dispatchers.IO) {
        try {
            applySeed()
        } catch (_: Exception) {
            false
        }
    }

    private fun applySeed(): Boolean {
        if (store.hasRows(QF_MUSHAF_RESOURCE, RECORD_TYPE)) return false
        val manifest = json.parseToJsonElement(
            context.assets.open(SEED_DIR + "/manifest.json").bufferedReader().use { it.readText() },
        ).jsonObject
        check(manifest["format"]?.jsonPrimitive?.intOrNull == 1) { "Unsupported QF seed format" }
        check(manifest["resources"]?.jsonPrimitive?.contentOrNull == QF_READER_FILTER.value) {
            "QF seed was baked for another resource filter"
        }
        val token = manifest["sync_token"]?.jsonPrimitive?.contentOrNull
            .takeUnless { it.isNullOrBlank() } ?: error("QF seed has no checkpoint")
        val generatedAtMs = manifest["generated_at_ms"]?.jsonPrimitive?.longOrNull
            ?: error("QF seed has no timestamp")
        val files = manifest["files"]?.jsonObject ?: error("QF seed has no files")
        val snapshots = SNAPSHOT_RESOURCES.map { resource ->
            val name = files["${resource.group}:${resource.id}"]?.jsonPrimitive?.contentOrNull
                ?: error("QF seed is missing ${resource.group}:${resource.id}")
            QfSnapshot(resource, file = copyAsset(name))
        }
        // The relative path is unused for file snapshots (apply matches the
        // fetched list positionally by resource); the temp name keeps it distinct.
        val changes = snapshots.map { QfContentChange.Snapshot(it.resource, it.file!!.name) } +
            readSupplements(manifest["supplements"]?.jsonPrimitive?.contentOrNull ?: error("QF seed has no supplements"))
        try {
            store.apply(
                QF_READER_FILTER,
                changes,
                snapshots,
                token,
                generatedAtMs,
                lastRefreshApiCalls = null,
                reset = true,
                validate = {
                    (store as? QfRuntimeMushafStore)?.rebuildReaderWords(canonicalWords(), expectedPages)
                    onValidated(changes)
                },
            )
        } finally {
            snapshots.forEach { it.file?.delete() }
        }
        return true
    }

    private fun readSupplements(assetName: String): List<QfContentChange.Upsert> {
        val root = json.parseToJsonElement(
            context.assets.open(assetName).bufferedReader().use { it.readText() },
        ).jsonObject
        val rows = root.flatMap { (_, verses) ->
            verses.jsonArray.map { element ->
                val word = element.jsonObject
                val wordId = word["word_id"]?.jsonPrimitive?.longOrNull
                    ?: error("QF seed supplement word is missing its id")
                val text = word["text"]?.jsonPrimitive?.contentOrNull?.trim()
                    .takeUnless { it.isNullOrEmpty() }
                    ?: error("QF seed supplement omitted transliteration $wordId")
                QfCacheRow(
                    WORD_SUPPLEMENT_RESOURCE,
                    "word_transliteration",
                    wordId.toString(),
                    buildJsonObject {
                        put("word_id", wordId)
                        put("text", text)
                    }.toString(),
                    "",
                )
            }
        }
        check(rows.isNotEmpty()) { "QF seed has no supplement words" }
        check(rows.map { it.recordKey }.distinct().size == rows.size) { "Duplicate QF seed supplement" }
        return rows.map(QfContentChange::Upsert)
    }

    private fun copyAsset(assetName: String): File {
        val file = File.createTempFile(SEED_TMP_PREFIX, ".json", context.cacheDir)
        context.assets.open(assetName).use { input ->
            file.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        check(file.length() > 0) { "QF seed asset is empty: $assetName" }
        return file
    }

    private companion object {
        const val SEED_DIR = "qf-seed"
        const val RECORD_TYPE = "mushaf_word"
        const val SEED_TMP_PREFIX = "qf-seed-tmp-"
        val SNAPSHOT_RESOURCES = listOf(
            QF_MUSHAF_RESOURCE,
            WORD_TRANSLATION_RESOURCE,
            WORD_TRANSLITERATION_RESOURCE,
        )
    }
}
