package com.beautifulquran.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import java.io.File
import java.io.FileReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** On-device implementation of QF's separate cache and sync-token tables. */
class QfContentCacheDatabase(context: Context) : QfContentSyncStore, QfRuntimeMushafStore {
    private var contentChangedInApply = false
    private val db = SQLiteDatabase.openOrCreateDatabase(
        File(context.noBackupFilesDir, "qf-content-cache.db"),
        null,
    ).apply {
        execSQL("""
            CREATE TABLE IF NOT EXISTS sync_state (
                resource_filter TEXT PRIMARY KEY NOT NULL,
                sync_token TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                last_refresh_api_calls INTEGER
            )
        """.trimIndent())
        if (!hasColumn("sync_state", "last_refresh_api_calls")) {
            execSQL("ALTER TABLE sync_state ADD COLUMN last_refresh_api_calls INTEGER")
            // Every successful legacy snapshot reads the same 190 chapter pages.
            execSQL(
                "UPDATE sync_state SET last_refresh_api_calls = 190 " +
                    "WHERE sync_token LIKE 'legacy-%'",
            )
        }
        execSQL("""
            CREATE TABLE IF NOT EXISTS cached_rows (
                resource_group TEXT NOT NULL,
                resource_id INTEGER NOT NULL,
                record_type TEXT NOT NULL,
                record_key TEXT NOT NULL,
                payload TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                verse_id INTEGER,
                position_in_verse INTEGER,
                PRIMARY KEY (resource_group, resource_id, record_type, record_key)
            )
        """.trimIndent())
        execSQL("""
            CREATE TABLE IF NOT EXISTS reader_words (
                surah_id INTEGER NOT NULL,
                ayah_number INTEGER NOT NULL,
                position INTEGER NOT NULL,
                translation_en TEXT NOT NULL,
                transliteration TEXT NOT NULL,
                qcf_v2 TEXT NOT NULL,
                qcf_page INTEGER NOT NULL,
                qcf_line INTEGER NOT NULL,
                qcf_span_end INTEGER NOT NULL,
                ayah_page INTEGER NOT NULL,
                PRIMARY KEY (surah_id, ayah_number, position)
            )
        """.trimIndent())
        if (!hasColumn("cached_rows", "verse_id")) {
            execSQL("ALTER TABLE cached_rows ADD COLUMN verse_id INTEGER")
            execSQL("ALTER TABLE cached_rows ADD COLUMN position_in_verse INTEGER")
        }
        // Schema v5 adds stable QF verse ordering and the typed reader view.
        if (version < 5) {
            execSQL("DELETE FROM cached_rows")
            execSQL("DELETE FROM sync_state")
            execSQL("DELETE FROM reader_words")
            version = 5
        }
    }

    override fun state(filter: QfResourceFilter): QfSyncState? =
        db.rawQuery(
            "SELECT sync_token, updated_at_ms, last_refresh_api_calls " +
                "FROM sync_state WHERE resource_filter = ?",
            arrayOf(filter.value),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else QfSyncState(
                filter,
                cursor.getString(0),
                cursor.getLong(1),
                cursor.getLong(2).takeUnless { cursor.isNull(2) },
            )
        }

    override fun rows(
        resource: QfResource,
        recordType: String,
        recordKeyPrefix: String?,
    ): List<QfCacheRow> {
        val prefixClause = if (recordKeyPrefix == null) "" else "AND record_key LIKE ? "
        val args = buildList {
            add(resource.group)
            add(resource.id.toString())
            add(recordType)
            if (recordKeyPrefix != null) add("$recordKeyPrefix%")
        }.toTypedArray()
        return db.rawQuery(
            "SELECT record_key,payload,updated_at FROM cached_rows " +
                "WHERE resource_group=? AND resource_id=? AND record_type=? " +
                prefixClause + "ORDER BY record_key",
            args,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        QfCacheRow(
                            resource = resource,
                            recordType = recordType,
                            recordKey = cursor.getString(0),
                            payload = cursor.getString(1),
                            updatedAt = cursor.getString(2),
                        ),
                    )
                }
            }
        }
    }

    override fun clear() = db.transaction {
        delete("cached_rows", null, null)
        delete("sync_state", null, null)
        delete("reader_words", null, null)
        Unit
    }

    override fun deleteResource(resource: QfResource) = db.transaction {
        deleteResourceRows(resource)
        Unit
    }

    override fun readerWords(): List<RuntimeMushafWord> = db.rawQuery(
        "SELECT surah_id,ayah_number,position,translation_en,transliteration,qcf_v2," +
            "qcf_page,qcf_line,qcf_span_end,ayah_page FROM reader_words " +
            "ORDER BY surah_id,ayah_number,position",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                RuntimeMushafWord(
                    cursor.getInt(0), cursor.getInt(1), cursor.getInt(2), cursor.getString(3),
                    cursor.getString(4), cursor.getString(5), cursor.getInt(6), cursor.getInt(7),
                    cursor.getInt(8), cursor.getInt(9),
                ),
            )
        }
    }

    override fun rebuildReaderWords(
        canonical: Map<Int, Map<Int, List<String>>>,
        expectedPages: IntRange,
    ) {
        val expectedWords = canonical.values.sumOf { ayahs -> ayahs.values.sumOf { it.size } }
        if (!contentChangedInApply && android.database.DatabaseUtils.queryNumEntries(db, "reader_words") == expectedWords.toLong()) {
            return
        }
        val json = Json { ignoreUnknownKeys = true }
        fun parsedRows(resource: QfResource, type: String) = rows(resource, type)
            .map { json.parseToJsonElement(it.payload).jsonObject }
        val metadata = parsedRows(QF_MUSHAF_RESOURCE, "mushaf").single()
        check(metadata.int("pages_count") == expectedPages.count() && metadata.int("lines_per_page") == 15) {
            "Unexpected QF Mushaf layout"
        }
        val pages = parsedRows(QF_MUSHAF_RESOURCE, "mushaf_page")
        check(pages.size == expectedPages.count() && pages.map { it.int("page_number") }.toSet() == expectedPages.toSet()) {
            "QF Mushaf omitted a page"
        }
        val translations = db.wordTexts(WORD_TRANSLATION_RESOURCE, unique = true, json)
        val transliterations = db.wordTexts(WORD_TRANSLITERATION_RESOURCE, unique = false, json)
        val supplements = db.wordTexts(WORD_SUPPLEMENT_RESOURCE, unique = true, json)
        val nextCode = IntArray(expectedPages.last + 1) { QCF_V2_FIRST_CODEPOINT }
        db.delete("reader_words", null, null)
        val insert = db.compileStatement(
            "INSERT INTO reader_words VALUES (?,?,?,?,?,?,?,?,?,?)",
        )
        try {
            db.rawQuery(
                "SELECT payload FROM cached_rows WHERE resource_group=? AND resource_id=? " +
                    "AND record_type='mushaf_word' ORDER BY verse_id,position_in_verse",
                arrayOf(QF_MUSHAF_RESOURCE.group, QF_MUSHAF_RESOURCE.id.toString()),
            ).use { cursor ->
                var hasRow = cursor.moveToFirst()
                var current = if (hasRow) json.parseToJsonElement(cursor.getString(0)).jsonObject else null
                var verseId = 0
                canonical.toSortedMap().forEach { (surah, ayahs) ->
                    ayahs.toSortedMap().forEach { (ayah, canonicalWords) ->
                        verseId++
                        val records = mutableListOf<JsonObject>()
                        while (current?.int("verse_id") == verseId) {
                            records += current!!
                            hasRow = cursor.moveToNext()
                            current = if (hasRow) {
                                json.parseToJsonElement(cursor.getString(0)).jsonObject
                            } else {
                                null
                            }
                        }
                        val words = records.filter { it.string("char_type_name") == "word" }
                            .sortedBy { it.int("position_in_verse") }
                        val ends = records.filter { it.string("char_type_name") == "end" }
                        check(words.indices.all { words[it].int("position_in_verse") == it + 1 } && ends.size == 1) {
                            "Invalid QF word topology $surah:$ayah"
                        }
                        mapQfVerse(
                            surah, ayah, canonicalWords.size, words, ends.single().requiredString("text"),
                            { id -> translations.value(id) },
                            { id -> supplements.valueOrNull(id) ?: transliterations.value(id) },
                        ).forEach { word ->
                            if (word.qcfV2.isNotEmpty()) word.qcfV2.codePoints().forEach { code ->
                                if (!Character.isWhitespace(code)) {
                                    check(code == nextCode[word.qcfPage]++) {
                                        "QCF V2 page ${word.qcfPage} glyph is out of sequence"
                                    }
                                }
                            }
                            insert.clearBindings()
                            insert.bindLong(1, word.surahId.toLong())
                            insert.bindLong(2, word.ayahNumber.toLong())
                            insert.bindLong(3, word.position.toLong())
                            insert.bindString(4, word.translation)
                            insert.bindString(5, word.transliteration)
                            insert.bindString(6, word.qcfV2)
                            insert.bindLong(7, word.qcfPage.toLong())
                            insert.bindLong(8, word.qcfLine.toLong())
                            insert.bindLong(9, word.qcfSpanEnd.toLong())
                            insert.bindLong(10, word.ayahPage.toLong())
                            insert.executeInsert()
                        }
                    }
                }
                check(!hasRow && current == null) { "QF Mushaf verse coverage mismatch" }
                check(verseId == 6_236) { "Canonical Quran verse coverage mismatch" }
            }
        } finally {
            insert.close()
        }
        expectedPages.forEach { page ->
            check(nextCode[page] > QCF_V2_FIRST_CODEPOINT) { "QCF V2 page $page has no glyphs" }
        }
    }

    override fun apply(
        filter: QfResourceFilter,
        changes: List<QfContentChange>,
        snapshots: List<QfSnapshot>,
        nextToken: String,
        nowMs: Long,
        lastRefreshApiCalls: Long?,
        reset: Boolean,
        validate: () -> Unit,
    ) = db.transaction {
        var contentChanged = reset
        if (reset) {
            delete("cached_rows", null, null)
            delete("reader_words", null, null)
        }
        val fetchedSnapshots = snapshots.iterator()
        changes.forEach { change ->
            when (change) {
                is QfContentChange.Snapshot -> {
                    check(fetchedSnapshots.hasNext()) { "Missing fetched QF snapshot" }
                    val snapshot = fetchedSnapshots.next()
                    check(snapshot.resource == change.resource) { "QF snapshot order mismatch" }
                    contentChanged = if (snapshot.file == null) {
                        applySnapshotDelta(snapshot) || contentChanged
                    } else {
                        applySnapshotFile(snapshot, compareExisting = !reset) || contentChanged
                    }
                }
                is QfContentChange.Upsert -> contentChanged = upsert(change.row, compareExisting = true) || contentChanged
                is QfContentChange.DeleteRow -> contentChanged = delete(
                    "cached_rows",
                    "resource_group = ? AND resource_id = ? AND record_type = ? AND record_key = ?",
                    arrayOf(change.resource.group, change.resource.id.toString(), change.recordType, change.recordKey),
                ) > 0 || contentChanged
                is QfContentChange.DeleteResource -> contentChanged = deleteResourceRows(change.resource) > 0 || contentChanged
                QfContentChange.FreshnessMarker -> Unit
            }
        }
        check(!fetchedSnapshots.hasNext()) { "Unused fetched QF snapshot" }
        contentChangedInApply = contentChanged
        validate()
        replace("sync_state", null, ContentValues().apply {
            put("resource_filter", filter.value)
            put("sync_token", nextToken)
            put("updated_at_ms", nowMs)
            if (lastRefreshApiCalls == null) putNull("last_refresh_api_calls")
            else put("last_refresh_api_calls", lastRefreshApiCalls)
        })
        Unit
    }

    private fun SQLiteDatabase.deleteResourceRows(resource: QfResource): Int =
        delete(
            "cached_rows",
            "resource_group = ? AND resource_id = ?",
            arrayOf(resource.group, resource.id.toString()),
        )

    /** An invalidated QF snapshot rewrites only rows whose payload changed. */
    private fun SQLiteDatabase.applySnapshotDelta(snapshot: QfSnapshot): Boolean {
        check(snapshot.rows.all { it.resource == snapshot.resource }) {
            "QF snapshot contained a row from another resource"
        }
        val existing = rowsForResource(snapshot.resource)
        val delta = qfSnapshotDelta(existing, snapshot.rows)
        delta.deletes.forEach { row ->
            delete(
                "cached_rows",
                "resource_group = ? AND resource_id = ? AND record_type = ? AND record_key = ?",
                arrayOf(
                    row.resource.group,
                    row.resource.id.toString(),
                    row.recordType,
                    row.recordKey,
                ),
            )
        }
        delta.upserts.forEach { upsert(it) }
        return delta.deletes.isNotEmpty() || delta.upserts.isNotEmpty()
    }

    /** Parses one record at a time so a 20+ MB QF snapshot never becomes a heap-sized JSON tree. */
    private fun SQLiteDatabase.applySnapshotFile(snapshot: QfSnapshot, compareExisting: Boolean): Boolean {
        val file = requireNotNull(snapshot.file)
        execSQL("DROP TABLE IF EXISTS temp.qf_incoming_keys")
        execSQL("CREATE TEMP TABLE qf_incoming_keys(record_type TEXT, record_key TEXT, PRIMARY KEY(record_type,record_key))")
        var group: String? = null
        var id: Long? = null
        var schema: Int? = null
        var sawRecords = false
        var changed = false
        JsonReader(FileReader(file).buffered()).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) when (reader.nextName()) {
                "resource_group" -> group = reader.nextString()
                "resource_id" -> id = reader.nextLong()
                "schema_version" -> schema = reader.nextInt()
                "records" -> {
                    sawRecords = true
                    reader.beginArray()
                    while (reader.hasNext()) {
                        val payload = reader.readElement() as? JsonObject ?: error("Invalid QF snapshot record")
                        val type = when (snapshot.resource) {
                            WORD_TRANSLATION_RESOURCE -> "word_translation"
                            WORD_TRANSLITERATION_RESOURCE -> "word_transliteration"
                            else -> payload["record_type"]?.jsonPrimitive?.contentOrNull
                                ?: error("QF snapshot record type is missing")
                        }
                        val key = payload["id"]?.jsonPrimitive?.longOrNull?.toString()
                            ?: error("QF snapshot record id is missing")
                        check(
                            insertWithOnConflict(
                                "qf_incoming_keys", null, ContentValues().apply {
                                    put("record_type", type)
                                    put("record_key", key)
                                }, SQLiteDatabase.CONFLICT_IGNORE,
                            ) != -1L,
                        ) { "Duplicate QF snapshot row" }
                        changed = upsert(
                            QfCacheRow(snapshot.resource, type, key, payload.toString(), payload.string("updated_at").orEmpty()),
                            payload.longOrNull("verse_id"),
                            payload.intOrNull("position_in_verse"),
                            compareExisting,
                        ) || changed
                    }
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
            reader.endObject()
        }
        check(group == snapshot.resource.group && id == snapshot.resource.id && schema == 1) {
            "QF snapshot metadata mismatch"
        }
        check(sawRecords) { "QF snapshot has no records" }
        changed = delete(
            "cached_rows",
            "resource_group=? AND resource_id=? AND NOT EXISTS (" +
                "SELECT 1 FROM qf_incoming_keys i WHERE i.record_type=cached_rows.record_type " +
                "AND i.record_key=cached_rows.record_key)",
            arrayOf(snapshot.resource.group, snapshot.resource.id.toString()),
        ) > 0 || changed
        execSQL("DROP TABLE temp.qf_incoming_keys")
        return changed
    }

    private fun SQLiteDatabase.rowsForResource(resource: QfResource): List<QfCacheRow> =
        rawQuery(
            "SELECT record_type,record_key,payload,updated_at FROM cached_rows " +
                "WHERE resource_group=? AND resource_id=?",
            arrayOf(resource.group, resource.id.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        QfCacheRow(
                            resource,
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getString(2),
                            cursor.getString(3),
                        ),
                    )
                }
            }
        }

    private fun SQLiteDatabase.upsert(
        row: QfCacheRow,
        verseId: Long? = null,
        positionInVerse: Int? = null,
        compareExisting: Boolean = false,
    ): Boolean {
        val order = if (row.resource == QF_MUSHAF_RESOURCE && row.recordType == "mushaf_word" && verseId == null) {
            Json.parseToJsonElement(row.payload).jsonObject.let {
                it.longOrNull("verse_id") to it.intOrNull("position_in_verse")
            }
        } else verseId to positionInVerse
        val values = ContentValues().apply {
            put("resource_group", row.resource.group)
            put("resource_id", row.resource.id)
            put("record_type", row.recordType)
            put("record_key", row.recordKey)
            put("payload", row.payload)
            put("updated_at", row.updatedAt)
            order.first?.let { put("verse_id", it) }
            order.second?.let { put("position_in_verse", it) }
        }
        if (compareExisting && rawQuery(
                "SELECT payload,updated_at,verse_id,position_in_verse FROM cached_rows " +
                    "WHERE resource_group=? AND resource_id=? AND record_type=? AND record_key=?",
                arrayOf(row.resource.group, row.resource.id.toString(), row.recordType, row.recordKey),
            ).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0) == row.payload && cursor.getString(1) == row.updatedAt &&
                    cursor.getLong(2).takeUnless { cursor.isNull(2) } == order.first &&
                    cursor.getInt(3).takeUnless { cursor.isNull(3) } == order.second
            }
        ) return false
        replace("cached_rows", null, values)
        return true
    }
}

private fun JsonObject.string(name: String) = get(name)?.jsonPrimitive?.contentOrNull
private fun JsonObject.requiredString(name: String) = string(name) ?: error("QF field $name is missing")
private fun JsonObject.int(name: String) = get(name)?.jsonPrimitive?.intOrNull ?: error("QF field $name is missing")
private fun JsonObject.intOrNull(name: String) = get(name)?.jsonPrimitive?.intOrNull
private fun JsonObject.longOrNull(name: String) = get(name)?.jsonPrimitive?.longOrNull

private class WordTexts {
    private var values = arrayOfNulls<String>(1024)
    private var ambiguous = BooleanArray(1024)

    fun put(id: Long, value: String, unique: Boolean) {
        check(id in 1..1_000_000) { "Invalid QF word id" }
        val index = id.toInt()
        if (index >= values.size) {
            val size = Integer.highestOneBit(index).shl(1)
            values = values.copyOf(size)
            ambiguous = ambiguous.copyOf(size)
        }
        if (values[index] != null) {
            check(!unique) { "Duplicate QF word owner" }
            ambiguous[index] = true
        } else {
            values[index] = value
        }
    }

    fun value(id: Long): String = valueOrNull(id)?.takeUnless { ambiguous[id.toInt()] }
        ?: error("QF word text is ambiguous or missing for word $id")

    fun valueOrNull(id: Long): String? = id.toInt().takeIf { it in values.indices }?.let(values::get)
}

private fun SQLiteDatabase.wordTexts(resource: QfResource, unique: Boolean, json: Json): WordTexts =
    WordTexts().also { result ->
        rawQuery(
            "SELECT payload FROM cached_rows WHERE resource_group=? AND resource_id=?",
            arrayOf(resource.group, resource.id.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val row = json.parseToJsonElement(cursor.getString(0)).jsonObject
                val text = row.string("text")?.trim() ?: continue
                result.put(
                    row["word_id"]?.jsonPrimitive?.longOrNull ?: error("QF word id is missing"),
                    text,
                    unique,
                )
            }
        }
    }

private fun JsonReader.readElement(): JsonElement = when (peek()) {
    android.util.JsonToken.BEGIN_OBJECT -> buildJsonObject {
        beginObject()
        while (hasNext()) put(nextName(), readElement())
        endObject()
    }
    android.util.JsonToken.BEGIN_ARRAY -> buildJsonArray {
        beginArray()
        while (hasNext()) add(readElement())
        endArray()
    }
    android.util.JsonToken.STRING -> JsonPrimitive(nextString())
    android.util.JsonToken.NUMBER -> nextString().let { raw ->
        raw.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(raw.toDouble())
    }
    android.util.JsonToken.BOOLEAN -> JsonPrimitive(nextBoolean())
    android.util.JsonToken.NULL -> { nextNull(); JsonNull }
    else -> error("Unsupported JSON token ${peek()}")
}

internal data class QfSnapshotDelta(
    val upserts: List<QfCacheRow>,
    val deletes: List<QfCacheRow>,
)

/** Computes the smallest row mutation set needed to publish a full snapshot. */
internal fun qfSnapshotDelta(
    existing: List<QfCacheRow>,
    incoming: List<QfCacheRow>,
): QfSnapshotDelta {
    fun QfCacheRow.key() = recordType to recordKey
    val before = existing.associateBy { it.key() }
    val after = incoming.associateBy { it.key() }
    check(after.size == incoming.size) { "Duplicate QF snapshot row" }
    return QfSnapshotDelta(
        upserts = incoming.filter { before[it.key()] != it },
        deletes = existing.filter { it.key() !in after },
    )
}

private fun SQLiteDatabase.hasColumn(table: String, column: String): Boolean =
    rawQuery("PRAGMA table_info($table)", null).use { cursor ->
        generateSequence { if (cursor.moveToNext()) cursor.getString(1) else null }
            .any { it == column }
    }

private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    return try {
        block().also { setTransactionSuccessful() }
    } finally {
        endTransaction()
    }
}
