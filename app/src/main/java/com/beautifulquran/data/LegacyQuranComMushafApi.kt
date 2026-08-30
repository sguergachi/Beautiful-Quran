package com.beautifulquran.data

import com.beautifulquran.domain.normalizeArabicForSearch
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Transitional direct adapter. Replace only this transport after QF approval. */
class LegacyQuranComMushafApi internal constructor(
    private val canonicalWords: () -> Map<Int, Map<Int, List<String>>>,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val minimumWords: Int = MIN_WORDS,
    private val chapters: IntRange = 1..114,
    private val expectedQcfPages: IntRange = 1..604,
    transport: ((String) -> JsonElement)? = null,
) : QfContentSyncApi, QfNetworkCallReporter, QfSyncProgressReporter {
    constructor(database: QuranDatabase) : this({ readCanonicalWords(database) })

    @Volatile
    private var reportNetworkCall: () -> Unit = {}
    @Volatile
    private var reportSyncProgress: (QfSyncProgress) -> Unit = {}
    private val transport = transport ?: { path -> httpGet(path) }

    override fun setNetworkCallReporter(reporter: () -> Unit) {
        reportNetworkCall = reporter
    }

    override fun setSyncProgressReporter(reporter: (QfSyncProgress) -> Unit) {
        reportSyncProgress = reporter
    }

    override suspend fun sync(request: QfSyncRequest): QfSyncPage {
        val filter = when (request) {
            is QfSyncRequest.Bootstrap -> request.filter
            is QfSyncRequest.Incremental -> request.filter
            is QfSyncRequest.NextPage -> error("Legacy mushaf sync is not paged at the cache boundary")
        }
        check(filter == FILTER) { "Unsupported legacy Quran.com resource" }
        return QfSyncPage(
            changes = listOf(QfContentChange.Snapshot(RESOURCE, SNAPSHOT_PATH)),
            nextPagePath = null,
            nextSyncToken = "legacy-${nowMs()}",
            contentAgeMs = 0L,
        )
    }

    override suspend fun snapshot(relativePath: String): QfSnapshot {
        check(relativePath == SNAPSHOT_PATH) { "Unexpected legacy mushaf snapshot path" }
        return withContext(Dispatchers.IO) {
            val canonical = canonicalWords()
            val gate = Semaphore(CHAPTER_CONCURRENCY)
            val completed = AtomicInteger()
            val totalChapters = chapters.count()
            reportSyncProgress(QfSyncProgress(0, totalChapters))
            val rows = coroutineScope {
                chapters.map { surah ->
                    async {
                        gate.withPermit {
                            normalizeLegacyChapter(
                                surah,
                                canonical[surah] ?: error("Canonical text omitted surah $surah"),
                                fetchVerses(surah),
                            ).also {
                                reportSyncProgress(
                                    QfSyncProgress(completed.incrementAndGet(), totalChapters),
                                )
                            }
                        }
                    }
                }.awaitAll().flatten()
            }
            check(rows.size == minimumWords) { "Expected $minimumWords Quran words, got ${rows.size}" }
            check(rows.map { it.recordKey }.distinct().size == rows.size) { "Duplicate Quran word rows" }
            assertQcfV2Runs(rows, expectedQcfPages)
            QfSnapshot(RESOURCE, rows)
        }
    }

    private fun fetchVerses(surah: Int): List<JsonObject> = buildList {
        var page = 1
        do {
            val root = get(chapterPath(surah, page)).jsonObject
            val verses = root["verses"]?.jsonArray ?: error("Quran.com response has no verses")
            verses.forEach { verse -> add(verse.jsonObject) }
            val pagination = root["pagination"]?.jsonObject ?: error("Quran.com response has no pagination")
            page = pagination.intOrNull("next_page") ?: 0
        } while (page > 0)
    }

    private fun get(path: String): JsonElement {
        reportNetworkCall()
        return transport(path)
    }

    private fun httpGet(path: String): JsonElement {
        val connection = URL(BASE_URL + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("User-Agent", "Beautiful-Quran/0.7")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= MAX_RESPONSE_BYTES) { "Quran.com response is too large" }
                    output.write(buffer, 0, count)
                }
                String(output.toByteArray(), StandardCharsets.UTF_8)
            }.orEmpty()
            check(status in 200..299) { "Quran.com returned $status" }
            return json.parseToJsonElement(body)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val BASE_URL = "https://api.quran.com"
        const val SNAPSHOT_PATH = "/api/v4/resources/snapshots/mushafs/1"
        const val MIN_WORDS = 77_429
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        const val CHAPTER_CONCURRENCY = 4
        val FILTER = QfResourceFilter("mushafs:1")
        val RESOURCE = QfResource("mushafs", 1)
    }
}

internal fun normalizeLegacyMushaf(
    canonical: Map<Int, Map<Int, List<String>>>,
    versesBySurah: Map<Int, List<JsonObject>>,
): List<QfCacheRow> = canonical.entries.flatMap { (surah, ayahs) ->
    normalizeLegacyChapter(surah, ayahs, versesBySurah[surah].orEmpty())
}

internal fun normalizeLegacyChapter(
    surah: Int,
    canonical: Map<Int, List<String>>,
    verses: List<JsonObject>,
): List<QfCacheRow> {
    val source = mutableMapOf<Int, MutableList<SourceWord>>()
    val ayahPages = mutableMapOf<Int, Int>()
    verses.forEach { verse ->
        val verseKey = verse.requiredString("verse_key")
        val ayah = verseKey.substringAfter(':').toIntOrNull()
            ?: error("Quran.com verse_key $verseKey is not chapter:ayah")
        check(verseKey.substringBefore(':').toIntOrNull() == surah) {
            "Quran.com verse $verseKey is not in surah $surah"
        }
        val page = verse.intOrNull("page_number") ?: 0
        ayahPages.putIfAbsent(ayah, page)
        val words = source.getOrPut(ayah) { mutableListOf() }
        verse["words"]?.jsonArray?.forEach { wordElement ->
            val word = wordElement.jsonObject
            val type = word.requiredString("char_type_name")
            if (type == "word") {
                words += SourceWord(
                    text = word.requiredString("text_uthmani"),
                    glyph = word.requiredString("code_v2"),
                    page = word.intOrNull("page_number") ?: 0,
                    line = word.intOrNull("line_number") ?: 0,
                    translation = word.nestedText("translation"),
                    transliteration = word.nestedText("transliteration"),
                )
            } else if (type == "end" && words.isNotEmpty()) {
                val marker = word.requiredString("code_v2")
                if (marker.isNotBlank()) words[words.lastIndex] = words.last().withMarker(marker)
            }
        }
    }

    return buildList {
        canonical.forEach { (ayah, arabicWords) ->
            val sourceWords = source[ayah]
            if (sourceWords.isNullOrEmpty()) error("Quran.com omitted $surah:$ayah")
            val aligned = alignQcfWords(arabicWords, sourceWords, surah, ayah)
            arabicWords.indices.forEach { index ->
                val position = index + 1
                val gloss = sourceWords[minOf(index, sourceWords.lastIndex)]
                val qcf = aligned[position] ?: AlignedQcf("", 0, 0, position)
                val record = buildJsonObject {
                    put("record_type", RECORD_TYPE)
                    put("record_key", "$surah:$ayah:$position")
                    put("surah_id", surah)
                    put("ayah_number", ayah)
                    put("position", position)
                    put("translation_en", gloss.translation)
                    put("transliteration", gloss.transliteration)
                    put("qcf_v2", qcf.glyph)
                    put("qcf_page", qcf.page)
                    put("qcf_line", qcf.line)
                    put("qcf_span_end", qcf.spanEnd)
                    put("ayah_page", ayahPages[ayah] ?: qcf.page)
                }
                parseMushafWord(record)
                add(QfCacheRow(RESOURCE, RECORD_TYPE, "$surah:$ayah:$position", record.toString(), ""))
            }
        }
    }
}

private fun alignQcfWords(
    canonical: List<String>,
    source: List<SourceWord>,
    surah: Int,
    ayah: Int,
): Map<Int, AlignedQcf> {
    val canonicalNorm = canonical.map(::normalizeForAlignment)
    val sourceNorm = source.map { normalizeForAlignment(it.text) }
    val aligned = mutableMapOf<Int, AlignedQcf>()
    var canonicalIndex = 0
    var sourceIndex = 0
    while (canonicalIndex < canonical.size && sourceIndex < source.size) {
        val start = canonicalIndex
        val page = source[sourceIndex].page
        val line = source[sourceIndex].line
        val glyphs = mutableListOf<String>()
        var canonicalText = ""
        var sourceText = ""
        while (true) {
            if (canonicalText.isEmpty() && canonicalIndex < canonical.size) {
                canonicalText += canonicalNorm[canonicalIndex++]
            }
            if (sourceText.isEmpty() && sourceIndex < source.size) {
                glyphs += source[sourceIndex].glyph
                sourceText += sourceNorm[sourceIndex++]
            }
            if (canonicalText.isNotEmpty() && sourceText.isNotEmpty() &&
                looselyEqual(canonicalText, sourceText)
            ) {
                break
            }
            if (canonicalIndex >= canonical.size && sourceIndex >= source.size) break
            if (canonicalText.length <= sourceText.length && canonicalIndex < canonical.size) {
                canonicalText += canonicalNorm[canonicalIndex++]
            } else if (sourceIndex < source.size) {
                glyphs += source[sourceIndex].glyph
                sourceText += sourceNorm[sourceIndex++]
            } else {
                canonicalText += canonicalNorm[canonicalIndex++]
            }
        }
        check(looselyEqual(canonicalText, sourceText)) { "Cannot align Quran.com word $surah:$ayah" }
        aligned[start + 1] = AlignedQcf(glyphs.joinToString(" "), page, line, canonicalIndex)
    }
    check(canonicalIndex == canonical.size && sourceIndex == source.size) {
        "Quran.com alignment ended early for $surah:$ayah"
    }
    return aligned
}

/** Proves that every glyph is in the contiguous run encoded by its page font. */
internal fun assertQcfV2Runs(rows: List<QfCacheRow>, expectedPages: IntRange) {
    val pages = rows.asSequence()
        .map { Json.parseToJsonElement(it.payload).jsonObject }
        .filter { it.requiredString("qcf_v2").isNotBlank() }
        .groupBy { it.intOrNull("qcf_page") ?: 0 }
    expectedPages.forEach { page ->
        val glyphs = pages[page] ?: error("QCF V2 page $page has no glyphs")
        val codes = glyphs
            .sortedWith(compareBy({ it.intOrNull("surah_id") }, { it.intOrNull("ayah_number") }, { it.intOrNull("position") }))
            .flatMap { it.requiredString("qcf_v2").codePoints().toArray().filterNot(Character::isWhitespace).asIterable() }
        codes.forEachIndexed { index, code ->
            val expected = QCF_V2_FIRST_CODEPOINT + index
            check(code == expected) {
                "QCF V2 page $page glyph $index is U+${code.toString(16).uppercase()}, " +
                    "expected U+${expected.toString(16).uppercase()}"
            }
        }
    }
}

private fun normalizeForAlignment(value: String) =
    normalizeArabicForSearch(Normalizer.normalize(value, Normalizer.Form.NFKD))

private fun readCanonicalWords(database: QuranDatabase): Map<Int, Map<Int, List<String>>> {
    val chapters = mutableMapOf<Int, MutableMap<Int, MutableList<String>>>()
    database.db.rawQuery(
        "SELECT surah_id,ayah_number,arabic FROM words ORDER BY surah_id,ayah_number,position",
        null,
    ).use { cursor ->
        while (cursor.moveToNext()) {
            chapters.getOrPut(cursor.getInt(0)) { mutableMapOf() }
                .getOrPut(cursor.getInt(1)) { mutableListOf() }
                .add(cursor.getString(2))
        }
    }
    return chapters.mapValues { (_, ayahs) -> ayahs.mapValues { it.value.toList() } }
}

private fun chapterPath(surah: Int, page: Int) =
    "/api/v4/verses/by_chapter/$surah?words=true&per_page=50&page=$page&" +
        "word_fields=location,line_number,char_type_name,code_v2,text_uthmani,page_number"

private fun JsonObject.requiredString(name: String) =
    get(name)?.jsonPrimitive?.contentOrNull ?: error("Quran.com field $name is missing")

private fun JsonObject.intOrNull(name: String): Int? {
    val primitive = get(name) as? JsonPrimitive ?: return null
    return primitive.intOrNull
}

private fun JsonObject.nestedText(name: String) =
    get(name)?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty().trim()

private fun looselyEqual(first: String, second: String) =
    first == second || first.replace('ي', 'ا') == second.replace('ي', 'ا')

private data class SourceWord(
    val text: String,
    val glyph: String,
    val page: Int,
    val line: Int,
    val translation: String,
    val transliteration: String,
) {
    fun withMarker(marker: String) = copy(glyph = "$glyph $marker")
}

private data class AlignedQcf(val glyph: String, val page: Int, val line: Int, val spanEnd: Int)

private const val RECORD_TYPE = "mushaf_word"
private const val QCF_V2_FIRST_CODEPOINT = 0xFC41
private val RESOURCE = QfResource("mushafs", 1)
