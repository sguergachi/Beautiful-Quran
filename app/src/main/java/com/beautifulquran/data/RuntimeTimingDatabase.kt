package com.beautifulquran.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Canonical timing rows are published per reciter with their accepted generation in one transaction. */
internal class RuntimeTimingDatabase(context: Context) : RuntimeTimingStore {
    private val db by lazy {
        SQLiteDatabase.openOrCreateDatabase(File(context.noBackupFilesDir, "qf-timing-cache.db"), null).apply {
            execSQL("CREATE TABLE IF NOT EXISTS timing_state (reciter_id INTEGER PRIMARY KEY,revision TEXT,checked_at_ms INTEGER NOT NULL,metadata TEXT)")
            execSQL("CREATE TABLE IF NOT EXISTS timing_rows (reciter_id INTEGER NOT NULL,surah_id INTEGER NOT NULL,ayah_number INTEGER NOT NULL,segments TEXT NOT NULL,audio_onset_ms INTEGER NOT NULL,PRIMARY KEY(reciter_id,surah_id,ayah_number))")
        }
    }

    override fun states(): Map<Int, RuntimeTimingState> = db.rawQuery(
        "SELECT reciter_id,revision,checked_at_ms FROM timing_state", null,
    ).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) put(cursor.getInt(0), RuntimeTimingState(cursor.getString(1), cursor.getLong(2)))
        }
    }

    override fun chapter(reciterId: Int, surahId: Int): RuntimeTimingChapter = db.rawQuery(
        "SELECT ayah_number,segments,audio_onset_ms FROM timing_rows WHERE reciter_id=? AND surah_id=? ORDER BY ayah_number",
        arrayOf(reciterId.toString(), surahId.toString()),
    ).use { cursor ->
        val rows = mutableMapOf<Int, List<com.beautifulquran.data.model.Segment>>()
        val onsets = mutableMapOf<Int, Long>()
        while (cursor.moveToNext()) {
            val ayah = cursor.getInt(0)
            rows[ayah] = QuranRepository.parseSegments(cursor.getString(1))
            onsets[ayah] = cursor.getLong(2)
        }
        RuntimeTimingChapter(rows, onsets)
    }

    override fun apply(snapshot: RuntimeTimingSnapshot, checkedAtMs: Long) = transaction {
        delete("timing_rows", "reciter_id=?", arrayOf(snapshot.reciterId.toString()))
        compileStatement("INSERT INTO timing_rows VALUES (?,?,?,?,?)").use { insert ->
            snapshot.rows.forEach { row ->
                insert.bindLong(1, snapshot.reciterId.toLong())
                insert.bindLong(2, row.surahId.toLong())
                insert.bindLong(3, row.ayahNumber.toLong())
                insert.bindString(4, row.segments)
                insert.bindLong(5, row.audioOnsetMs)
                insert.executeInsert()
            }
        }
        writeState(snapshot.reciterId, snapshot.revision, checkedAtMs, snapshot.metadata)
    }

    override fun withdraw(reciterId: Int, checkedAtMs: Long) = transaction {
        delete("timing_rows", "reciter_id=?", arrayOf(reciterId.toString()))
        writeState(reciterId, null, checkedAtMs, null)
    }

    override fun clear() = transaction {
        delete("timing_rows", null, null)
        delete("timing_state", null, null)
    }

    private fun SQLiteDatabase.writeState(reciterId: Int, revision: String?, checkedAtMs: Long, metadata: String?) {
        delete("timing_state", "reciter_id=?", arrayOf(reciterId.toString()))
        insertOrThrow("timing_state", null, ContentValues().apply {
            put("reciter_id", reciterId)
            put("revision", revision)
            put("checked_at_ms", checkedAtMs)
            put("metadata", metadata)
        })
    }

    private inline fun transaction(write: SQLiteDatabase.() -> Unit) {
        db.beginTransaction()
        try {
            db.write()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

/** Counts are canonical positions, independent of QCF segmentation or provider timing labels. */
internal fun readCanonicalTimingCounts(database: QuranDatabase): Map<String, Int> = database.db.rawQuery(
    "SELECT surah_id,ayah_number,COUNT(*) FROM words GROUP BY surah_id,ayah_number", null,
).use { cursor ->
    buildMap {
        while (cursor.moveToNext()) put("${cursor.getInt(0)}:${cursor.getInt(1)}", cursor.getInt(2))
    }
}
