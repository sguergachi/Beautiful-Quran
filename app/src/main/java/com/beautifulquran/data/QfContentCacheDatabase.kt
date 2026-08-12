package com.beautifulquran.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** On-device implementation of QF's separate cache and sync-token tables. */
class QfContentCacheDatabase(context: Context) : QfContentSyncStore {
    private val db = SQLiteDatabase.openOrCreateDatabase(
        File(context.noBackupFilesDir, "qf-content-cache.db"),
        null,
    ).apply {
        execSQL("""
            CREATE TABLE IF NOT EXISTS sync_state (
                resource_filter TEXT PRIMARY KEY NOT NULL,
                sync_token TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
        """.trimIndent())
        execSQL("""
            CREATE TABLE IF NOT EXISTS cached_rows (
                resource_group TEXT NOT NULL,
                resource_id INTEGER NOT NULL,
                record_type TEXT NOT NULL,
                record_key TEXT NOT NULL,
                payload TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                PRIMARY KEY (resource_group, resource_id, record_type, record_key)
            )
        """.trimIndent())
    }

    override fun state(filter: QfResourceFilter): QfSyncState? =
        db.rawQuery(
            "SELECT sync_token, updated_at_ms FROM sync_state WHERE resource_filter = ?",
            arrayOf(filter.value),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else QfSyncState(
                filter,
                cursor.getString(0),
                cursor.getLong(1),
            )
        }

    override fun clear() = db.transaction {
        delete("cached_rows", null, null)
        delete("sync_state", null, null)
        Unit
    }

    override fun apply(
        filter: QfResourceFilter,
        changes: List<QfContentChange>,
        snapshots: List<QfSnapshot>,
        nextToken: String,
        nowMs: Long,
    ) = db.transaction {
        val fetchedSnapshots = snapshots.iterator()
        changes.forEach { change ->
            when (change) {
                is QfContentChange.Snapshot -> {
                    check(fetchedSnapshots.hasNext()) { "Missing fetched QF snapshot" }
                    val snapshot = fetchedSnapshots.next()
                    check(snapshot.resource == change.resource) { "QF snapshot order mismatch" }
                    deleteResource(snapshot.resource)
                    snapshot.rows.forEach { row -> this.upsert(row) }
                }
                is QfContentChange.Upsert -> upsert(change.row)
                is QfContentChange.DeleteRow -> delete(
                    "cached_rows",
                    "resource_group = ? AND resource_id = ? AND record_type = ? AND record_key = ?",
                    arrayOf(change.resource.group, change.resource.id.toString(), change.recordType, change.recordKey),
                )
                is QfContentChange.DeleteResource -> deleteResource(change.resource)
                QfContentChange.FreshnessMarker -> Unit
            }
        }
        check(!fetchedSnapshots.hasNext()) { "Unused fetched QF snapshot" }
        replace("sync_state", null, ContentValues().apply {
            put("resource_filter", filter.value)
            put("sync_token", nextToken)
            put("updated_at_ms", nowMs)
        })
        Unit
    }

    private fun SQLiteDatabase.deleteResource(resource: QfResource) {
        delete(
            "cached_rows",
            "resource_group = ? AND resource_id = ?",
            arrayOf(resource.group, resource.id.toString()),
        )
    }

    private fun SQLiteDatabase.upsert(row: QfCacheRow) {
        replace("cached_rows", null, ContentValues().apply {
            put("resource_group", row.resource.group)
            put("resource_id", row.resource.id)
            put("record_type", row.recordType)
            put("record_key", row.recordKey)
            put("payload", row.payload)
            put("updated_at", row.updatedAt)
        })
    }
}

private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    return try {
        block().also { setTransactionSuccessful() }
    } finally {
        endTransaction()
    }
}
