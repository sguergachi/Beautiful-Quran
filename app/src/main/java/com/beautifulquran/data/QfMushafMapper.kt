package com.beautifulquran.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Joins QF's three independent resources onto our canonical Quran word rows. */
internal fun mapQfMushaf(
    canonical: Map<Int, Map<Int, List<String>>>,
    mushafRows: List<QfCacheRow>,
    translationRows: List<QfCacheRow>,
    transliterationRows: List<QfCacheRow>,
    supplementRows: List<QfCacheRow>,
    expectedPages: IntRange = 1..604,
    json: Json = Json { ignoreUnknownKeys = true },
): List<QfCacheRow> {
    val mushaf = mushafRows.map { json.parseToJsonElement(it.payload).jsonObject }
    val metadata = mushaf.single { it.string("record_type") == "mushaf" }
    check(metadata.int("pages_count") == expectedPages.count() && metadata.int("lines_per_page") == 15) {
        "Unexpected QF Mushaf layout"
    }
    val pages = mushaf.filter { it.string("record_type") == "mushaf_page" }
    check(pages.size == expectedPages.count() && pages.map { it.int("page_number") }.toSet() == expectedPages.toSet()) {
        "QF Mushaf omitted a page"
    }

    val translations = uniqueWordText(translationRows, json, "translation")
    val transliterations = groupedWordText(transliterationRows, json)
    val supplements = uniqueWordText(supplementRows, json, "supplement")
    val qcfByVerse = mushaf
        .filter { it.string("record_type") == "mushaf_word" }
        .groupBy { it.int("verse_id") }

    val verses = canonical.toSortedMap().flatMap { (surah, ayahs) ->
        ayahs.toSortedMap().map { (ayah, words) -> CanonicalVerse(surah, ayah, words) }
    }
    check(qcfByVerse.keys == (1..verses.size).toSet()) {
        "QF Mushaf verse coverage mismatch"
    }

    val output = buildList {
        verses.forEachIndexed { index, verse ->
            val records = qcfByVerse.getValue(index + 1)
            val words = records.filter { it.string("char_type_name") == "word" }
                .sortedBy { it.int("position_in_verse") }
            val ends = records.filter { it.string("char_type_name") == "end" }
            check(words.indices.all { words[it].int("position_in_verse") == it + 1 } && ends.size == 1) {
                "Invalid QF word topology ${verse.key}"
            }
            addAll(
                mapVerse(
                    verse,
                    words,
                    ends.single().requiredString("text"),
                    translations,
                    transliterations,
                    supplements,
                ),
            )
        }
    }
    check(output.size == verses.sumOf { it.words.size } && output.map { it.recordKey }.distinct().size == output.size) {
        "QF reader view is incomplete"
    }
    assertQcfV2Runs(output, expectedPages)
    return output
}

private fun mapVerse(
    verse: CanonicalVerse,
    qfWords: List<JsonObject>,
    endGlyph: String,
    translations: Map<Long, String>,
    transliterations: Map<Long, List<String>>,
    supplements: Map<Long, String>,
): List<QfCacheRow> {
    val rule = TOPOLOGY_RULES[verse.key]
    val expectedQfCount = verse.words.size + (rule?.qfCount ?: 1) - (rule?.canonicalCount ?: 1)
    check(qfWords.size == expectedQfCount) { "QF topology changed for ${verse.key}" }
    val mapped = mutableMapOf<Int, AlignedQfWord>()
    var canonicalPosition = 1
    var qfPosition = 1
    while (canonicalPosition <= verse.words.size) {
        val atRule = rule?.takeIf {
            it.canonicalStart == canonicalPosition && it.qfStart == qfPosition
        }
        val canonicalCount = atRule?.canonicalCount ?: 1
        val qfCount = atRule?.qfCount ?: 1
        val source = qfWords.subList(qfPosition - 1, qfPosition - 1 + qfCount)
        val page = source.first().int("page_number")
        val line = source.first().int("line_number")
        check(source.all { it.int("page_number") == page && it.int("line_number") == line }) {
            "A joined QF word crosses a line at ${verse.key}"
        }
        val ids = source.map { it.long("word_id") }
        mapped[canonicalPosition] = AlignedQfWord(
            glyph = source.joinToString(" ") { it.requiredString("text") },
            page = page,
            line = line,
            spanEnd = canonicalPosition + canonicalCount - 1,
            translation = joinQfGloss(ids.map(translations::getValue)),
            transliteration = joinQfGloss(ids.map { id ->
                supplements[id] ?: transliterations[id]?.singleOrNull()
                ?: error("QF transliteration is ambiguous or missing for word $id")
            }),
        )
        canonicalPosition += canonicalCount
        qfPosition += qfCount
    }
    check(qfPosition == qfWords.size + 1) { "QF topology ended early for ${verse.key}" }
    val lastOwner = mapped.keys.maxOrNull() ?: error("QF verse ${verse.key} has no words")
    mapped[lastOwner] = mapped.getValue(lastOwner).let { it.copy(glyph = "${it.glyph} $endGlyph") }
    val ayahPage = qfWords.first().int("page_number")

    return verse.words.indices.map { index ->
        val position = index + 1
        val value = mapped[position] ?: AlignedQfWord("", 0, 0, position, "", "")
        val key = "${verse.key}:$position"
        val payload = buildJsonObject {
            put("record_type", "mushaf_word")
            put("record_key", key)
            put("surah_id", verse.surah)
            put("ayah_number", verse.ayah)
            put("position", position)
            put("translation_en", value.translation)
            put("transliteration", value.transliteration)
            put("qcf_v2", value.glyph)
            put("qcf_page", value.page)
            put("qcf_line", value.line)
            put("qcf_span_end", value.spanEnd)
            put("ayah_page", ayahPage)
        }
        QfCacheRow(QF_MUSHAF_RESOURCE, "mushaf_word", key, payload.toString(), "")
    }
}

private fun groupedWordText(rows: List<QfCacheRow>, json: Json): Map<Long, List<String>> =
    rows.map { json.parseToJsonElement(it.payload).jsonObject }
        .groupBy({ it.long("word_id") }, { it.requiredString("text").trim() })

private fun uniqueWordText(
    rows: List<QfCacheRow>,
    json: Json,
    label: String,
): Map<Long, String> {
    val values = groupedWordText(rows, json)
    check(values.values.all { it.size == 1 }) { "Duplicate QF $label word owner" }
    return values.mapValues { it.value.single() }
}

private fun joinQfGloss(parts: List<String>) =
    parts.filter(String::isNotBlank).filterIndexed { index, part -> index == 0 || part != parts[index - 1] }
        .joinToString(" ")

private data class CanonicalVerse(val surah: Int, val ayah: Int, val words: List<String>) {
    val key = "$surah:$ayah"
}

private data class AlignedQfWord(
    val glyph: String,
    val page: Int,
    val line: Int,
    val spanEnd: Int,
    val translation: String,
    val transliteration: String,
)

private data class TopologyRule(
    val canonicalStart: Int,
    val canonicalCount: Int,
    val qfStart: Int,
    val qfCount: Int,
)

/** Ten QAC/QCF token-boundary differences; counts changing makes the cache fail closed. */
private val TOPOLOGY_RULES = mapOf(
    "2:72" to TopologyRule(4, 2, 4, 1),
    "2:181" to TopologyRule(3, 2, 3, 1),
    "8:6" to TopologyRule(4, 2, 4, 1),
    "13:37" to TopologyRule(8, 2, 8, 1),
    "15:7" to TopologyRule(1, 1, 1, 2),
    "27:20" to TopologyRule(4, 1, 4, 2),
    "36:22" to TopologyRule(1, 1, 1, 2),
    "37:130" to TopologyRule(3, 2, 3, 1),
    "37:164" to TopologyRule(1, 1, 1, 2),
    "41:47" to TopologyRule(25, 1, 25, 2),
)

private fun JsonObject.string(name: String) = get(name)?.jsonPrimitive?.contentOrNull
private fun JsonObject.requiredString(name: String) = string(name) ?: error("QF field $name is missing")
private fun JsonObject.int(name: String) = get(name)?.jsonPrimitive?.intOrNull ?: error("QF field $name is missing")
private fun JsonObject.long(name: String) = get(name)?.jsonPrimitive?.longOrNull ?: error("QF field $name is missing")

/** Proves that every glyph is in the contiguous run encoded by its page font. */
internal fun assertQcfV2Runs(rows: List<QfCacheRow>, expectedPages: IntRange) {
    val pages = rows.asSequence()
        .map { Json.parseToJsonElement(it.payload).jsonObject }
        .filter { it.requiredString("qcf_v2").isNotBlank() }
        .groupBy { it.int("qcf_page") }
    expectedPages.forEach { page ->
        val glyphs = pages[page] ?: error("QCF V2 page $page has no glyphs")
        val codes = glyphs
            .sortedWith(compareBy({ it.int("surah_id") }, { it.int("ayah_number") }, { it.int("position") }))
            .flatMap { row ->
                row.requiredString("qcf_v2").codePoints().toArray()
                    .filterNot(Character::isWhitespace).asIterable()
            }
        codes.forEachIndexed { index, code ->
            val expected = QCF_V2_FIRST_CODEPOINT + index
            check(code == expected) {
                "QCF V2 page $page glyph $index is U+${code.toString(16).uppercase()}, " +
                    "expected U+${expected.toString(16).uppercase()}"
            }
        }
    }
}

internal fun readCanonicalWords(database: QuranDatabase): Map<Int, Map<Int, List<String>>> {
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

private const val QCF_V2_FIRST_CODEPOINT = 0xFC41
