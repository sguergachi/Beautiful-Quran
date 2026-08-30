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
                PRIMARY KEY (resource_group, resource_id, record_type, record_key)
            )
        """.trimIndent())
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
        lastRefreshApiCalls: Long?,
    ) = db.transaction {
        val fetchedSnapshots = snapshots.iterator()
        changes.forEach { change ->
            when (change) {
                is QfContentChange.Snapshot -> {
                    check(fetchedSnapshots.hasNext()) { "Missing fetched QF snapshot" }
                    val snapshot = fetchedSnapshots.next()
                    check(snapshot.resource == change.resource) { "QF snapshot order mismatch" }
                    applySnapshotDelta(snapshot)
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
            if (lastRefreshApiCalls == null) putNull("last_refresh_api_calls")
            else put("last_refresh_api_calls", lastRefreshApiCalls)
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

    /** A legacy full comparison must not rewrite 77,429 unchanged rows. */
    private fun SQLiteDatabase.applySnapshotDelta(snapshot: QfSnapshot) {
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
