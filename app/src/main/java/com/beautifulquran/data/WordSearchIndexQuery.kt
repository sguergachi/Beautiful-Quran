package com.beautifulquran.data

/**
 * Uses the existing (surah_id, ayah_number) index instead of scanning the word
 * table for an arithmetic verse key. Callers bound candidates to 600 ayahs;
 * this predicate preserves every supplied valid key, including duplicates.
 * Empty/invalid selections match nothing, never the whole corpus.
 */
internal fun wordSearchAyahFilter(alias: String, keys: IntArray): String {
    val grouped = keys.distinct()
        .filter { it / 1_000 in 1..114 && it % 1_000 in 1..286 }
        .groupBy { it / 1_000 }
    if (grouped.isEmpty()) return "WHERE 0"
    return grouped.toSortedMap().entries.joinToString(" OR ", prefix = "WHERE ") { (surah, keys) ->
        val ayahs = keys.map { it % 1_000 }.sorted().joinToString(",")
        "($alias.surah_id = $surah AND $alias.ayah_number IN ($ayahs))"
    }
}
