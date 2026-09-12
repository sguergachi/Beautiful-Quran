package com.beautifulquran.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Host-side check that quick-search ayah filters use indexed columns and
 * return the same rows as the old arithmetic `surah_id * 1000 + ayah_number`
 * encoding. Timings here are SQLite-on-the-dev-machine, not a phone claim.
 */
class WordSearchIndexQueryTest {

    private val sampleKeys = intArrayOf(2_001, 2_255, 2_282, 36_001, 112_001)

    @Test
    fun emptyKeysMatchNothing() {
        assertEquals("WHERE 0", wordSearchAyahFilter("w", intArrayOf()))
    }

    @Test
    fun outOfRangeKeysMatchNothing() {
        assertEquals("WHERE 0", wordSearchAyahFilter("a", intArrayOf(0, 115_001, 2_000)))
    }

    @Test
    fun groupsByIndexedSurahAndAyah() {
        val sql = wordSearchAyahFilter("w", sampleKeys)
        assertFalse(sql.contains("* 1000"))
        assertFalse(sql.contains("*1000"))
        assertTrue(sql.contains("w.surah_id = 2 AND w.ayah_number IN (1,255,282)"))
        assertTrue(sql.contains("w.surah_id = 36 AND w.ayah_number IN (1)"))
        assertTrue(sql.contains("w.surah_id = 112 AND w.ayah_number IN (1)"))
    }

    @Test
    fun duplicatesNeverCrowdOutAnotherVerse() {
        val keys = IntArray(601) { if (it < 600) 2_001 else 114_001 }
        assertEquals(
            "WHERE (w.surah_id = 2 AND w.ayah_number IN (1)) OR " +
                "(w.surah_id = 114 AND w.ayah_number IN (1))",
            wordSearchAyahFilter("w", keys),
        )
    }

    @Test
    fun sampleKeysMatchArithmeticRowsAndUseTheAyahIndex() {
        assumeTrue("Host SQL check needs sqlite3", runCatching {
            ProcessBuilder("sqlite3", "-version").start().waitFor() == 0
        }.getOrDefault(false))
        val db = repoFile("data/quran.db")
        val filter = wordSearchAyahFilter("w", sampleKeys)
        val arithmetic =
            "WHERE w.surah_id * 1000 + w.ayah_number IN (${sampleKeys.joinToString(",")})"
        val order = "ORDER BY w.surah_id, w.ayah_number, w.position"
        val indexedRows = sqlite(db, "SELECT w.surah_id, w.ayah_number, w.position FROM words w $filter $order;")
        val arithmeticRows = sqlite(db, "SELECT w.surah_id, w.ayah_number, w.position FROM words w $arithmetic $order;")
        assertEquals(arithmeticRows, indexedRows)
        assertEquals(184, indexedRows.lines().count { it.isNotBlank() })

        val ayahFilter = wordSearchAyahFilter("a", sampleKeys)
        val ayahOrder = "ORDER BY a.surah_id, a.ayah_number"
        val indexedAyahs = sqlite(db, "SELECT a.surah_id, a.ayah_number FROM ayahs a $ayahFilter $ayahOrder;")
        val arithmeticAyahs = sqlite(
            db,
            "SELECT a.surah_id, a.ayah_number FROM ayahs a " +
                "WHERE a.surah_id * 1000 + a.ayah_number IN (${sampleKeys.joinToString(",")}) $ayahOrder;",
        )
        assertEquals(arithmeticAyahs, indexedAyahs)
        assertEquals(5, indexedAyahs.lines().count { it.isNotBlank() })

        val wordPlan = sqlite(db, "EXPLAIN QUERY PLAN SELECT w.surah_id FROM words w $filter;")
        assertTrue(wordPlan, wordPlan.contains("SEARCH"))
        assertTrue(wordPlan, wordPlan.contains("idx_words_ayah"))
        assertFalse(wordPlan, wordPlan.contains("SCAN words"))

        val ayahPlan = sqlite(db, "EXPLAIN QUERY PLAN SELECT a.surah_id FROM ayahs a $ayahFilter;")
        assertTrue(ayahPlan, ayahPlan.contains("SEARCH"))
        assertFalse(ayahPlan, ayahPlan.contains("SCAN ayahs"))
    }

    private fun sqlite(db: File, sql: String): String {
        val proc = ProcessBuilder("sqlite3", "-readonly", db.absolutePath, sql)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        assertEquals("sqlite3 failed: $out", 0, proc.waitFor())
        return out
    }

    private fun repoFile(path: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, path).takeIf(File::isFile)?.let { return it }
            dir = dir.parentFile
        }
        error("Could not find $path")
    }
}
