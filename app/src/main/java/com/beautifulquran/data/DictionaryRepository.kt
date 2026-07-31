package com.beautifulquran.data

import com.beautifulquran.data.model.DictionaryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * English Wiktionary Arabic senses for QAC lemmas. Read-only and lazy: a
 * missing asset simply yields no entry rather than taking the Root Viewer down.
 */
class DictionaryRepository(private val database: DictionaryDatabase) {

    @Volatile
    private var credit: String? = null

    suspend fun entryFor(lemma: String): DictionaryEntry? = withContext(Dispatchers.IO) {
        if (lemma.isBlank()) return@withContext null
        val db = database.db ?: return@withContext null
        runCatching {
            db.rawQuery(
                "SELECT word, payload FROM lemma_entries WHERE lemma = ?",
                arrayOf(lemma),
            ).use { c ->
                if (!c.moveToFirst()) return@use null
                val groups = parseDictionaryPayload(c.getString(1))
                if (groups.isEmpty()) {
                    null
                } else {
                    DictionaryEntry(
                        lemma = lemma,
                        word = c.getString(0),
                        groups = groups,
                        credit = creditLine(),
                    )
                }
            }
        }.getOrNull()
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
