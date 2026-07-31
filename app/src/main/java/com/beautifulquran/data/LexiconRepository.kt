package com.beautifulquran.data

import com.beautifulquran.data.model.LexiconEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lane's *Arabic-English Lexicon* (1863–93), keyed by the same QAC roots the
 * reader already carries. Read-only and lazy: the first lookup extracts and
 * opens the asset, and a missing or unreadable asset simply yields no entry
 * rather than taking the Root Viewer down with it.
 */
class LexiconRepository(private val database: LexiconDatabase) {

    /** Perseus asks that this credit travel with their text. Cached on open. */
    @Volatile
    private var credit: String? = null

    suspend fun entryFor(root: String): LexiconEntry? = withContext(Dispatchers.IO) {
        if (root.isBlank()) return@withContext null
        val db = database.db ?: return@withContext null
        val entry = runCatching {
            db.rawQuery(
                "SELECT entry, page FROM root_entries WHERE root = ?",
                arrayOf(root),
            ).use { c ->
                if (!c.moveToFirst()) null
                else LexiconEntry(
                    root = root,
                    text = c.getString(0),
                    page = c.getInt(1),
                    credit = creditLine(),
                )
            }
        }.getOrNull()
        entry?.takeIf { it.text.isNotBlank() }
    }

    private fun creditLine(): String {
        credit?.let { return it }
        val db = database.db ?: return ""
        val value = runCatching {
            db.rawQuery("SELECT value FROM meta WHERE key = 'credit'", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else ""
            }
        }.getOrDefault("")
        credit = value
        return value
    }
}
