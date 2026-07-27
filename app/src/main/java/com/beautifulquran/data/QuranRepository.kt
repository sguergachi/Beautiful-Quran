package com.beautifulquran.data

import android.database.Cursor
import com.beautifulquran.data.model.Ayah
import com.beautifulquran.data.model.BookmarkedAyah
import com.beautifulquran.data.model.Reciter
import com.beautifulquran.data.model.RootOccurrence
import com.beautifulquran.data.model.RootLemmaSummary
import com.beautifulquran.data.model.RootSummary
import com.beautifulquran.data.model.Segment
import com.beautifulquran.data.model.SubwordKeyframe
import com.beautifulquran.data.model.Surah
import com.beautifulquran.data.model.SurahContent
import com.beautifulquran.data.model.Word
import com.beautifulquran.data.model.WordMorphology
import com.beautifulquran.data.model.WordSearchHit
import com.beautifulquran.domain.WORD_SEARCH_MAX_HITS
import com.beautifulquran.domain.WordSearchAyahContext
import com.beautifulquran.domain.WordSearchIndexEntry
import com.beautifulquran.domain.isWordSearchQuery
import com.beautifulquran.domain.matchWordSearch
import com.beautifulquran.domain.normalizeArabicForSearch
import com.beautifulquran.timingslab.OverrideKey
import com.beautifulquran.timingslab.TimingOverrides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Preserve editable word spacing while keeping it behind immutable MP3 silence. */
internal fun alignToAudioOnset(segments: List<Segment>, onsetMs: Long): List<Segment> {
    val firstStartMs = segments.firstOrNull()?.startMs ?: return segments
    val shiftMs = (onsetMs - firstStartMs).coerceAtLeast(0L)
    if (shiftMs == 0L) return segments
    return segments.map { it.copy(startMs = it.startMs + shiftMs, endMs = it.endMs + shiftMs) }
}

@Serializable
private data class TimingV2Segment(
    val position: Int,
    val startMs: Long,
    val endMs: Long,
    val keyframes: List<TimingV2Keyframe>,
)

@Serializable
private data class TimingV2Keyframe(
    val offsetMs: Long,
    val progress: Float,
)

class QuranRepository(
    private val database: QuranDatabase,
    /** Optional on-device override store produced by the Timings Lab. When
     * set, any (reciter, surah, ayah) the user has hand-corrected is served
     * here instead of the bundled DB row. Null keeps this class usable from
     * JVM unit tests that don't ship an override store. */
    private val timingOverrides: TimingOverrides? = null,
) {

    /** Change signal for the Lab's on-device corrections: emits whenever the
     * override store changes so callers holding a [timings] snapshot can
     * re-pull it. Null when constructed without a store (JVM unit tests). */
    val timingOverridesChanged: StateFlow<Map<OverrideKey, List<Segment>>>?
        get() = timingOverrides?.overrides

    // @Volatile: read/written from Dispatchers.IO workers. Worst case without
    // a lock is one redundant query; the result is identical either way.
    @Volatile
    private var surahsCache: List<Surah>? = null

    @Volatile
    private var recitersCache: List<Reciter>? = null

    /** Lazily built once — ~77k word rows with ayah text for home search. */
    @Volatile
    private var wordSearchIndex: List<WordSearchIndexEntry>? = null

    /** Runs [sql] and maps every row with [map] — the shape of every query here. */
    private fun <T> queryList(sql: String, args: Array<String>? = null, map: (Cursor) -> T): List<T> =
        database.db.rawQuery(sql, args).use { c ->
            buildList {
                while (c.moveToNext()) add(map(c))
            }
        }

    suspend fun surahs(): List<Surah> = withContext(Dispatchers.IO) {
        surahsCache ?: queryList(
            "SELECT id, name_arabic, name_transliteration, name_translation, revelation_place, ayah_count FROM surahs ORDER BY id",
        ) { c ->
            Surah(c.getInt(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5))
        }.also { surahsCache = it }
    }

    suspend fun reciters(): List<Reciter> = withContext(Dispatchers.IO) {
        recitersCache ?: queryList(
            "SELECT id, slug, name, style, has_timings FROM reciters ORDER BY id",
        ) { c ->
            Reciter(c.getInt(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4) == 1)
        }.also { recitersCache = it }
    }

    suspend fun surahContent(surahId: Int): SurahContent = withContext(Dispatchers.IO) {
        val surah = surahs().first { it.id == surahId }
        val words = database.db.rawQuery(
            """
            SELECT ayah_number, position, arabic, translation_en, transliteration, qcf_v2, qcf_page, qcf_line, qcf_span_end
            FROM words
            WHERE surah_id = ?
            ORDER BY ayah_number, position
            """.trimIndent(),
            arrayOf(surahId.toString()),
        ).use { c ->
            val map = HashMap<Int, MutableList<Word>>()
            while (c.moveToNext()) {
                map.getOrPut(c.getInt(0)) { mutableListOf() }
                    .add(
                        Word(
                            position = c.getInt(1),
                            arabic = c.getString(2),
                            translation = c.getString(3),
                            transliteration = c.getString(4),
                            qcfV2 = c.getString(5),
                            qcfPage = c.getInt(6),
                            qcfLine = c.getInt(7),
                            qcfSpanEnd = c.getInt(8),
                        ),
                    )
            }
            map
        }
        val ayahs = queryList(
            "SELECT ayah_number, text_uthmani, translation_en, page FROM ayahs WHERE surah_id = ? ORDER BY ayah_number",
            arrayOf(surahId.toString()),
        ) { c ->
            val n = c.getInt(0)
            Ayah(surahId, n, c.getString(1), c.getString(2), c.getInt(3), words[n].orEmpty())
        }
        SurahContent(surah, ayahs)
    }

    /**
     * Resolves user bookmark keys to their immutable Quran text and chapter
     * metadata. Keys are queried in bounded batches to stay below SQLite's
     * bind-argument limit even when a reader has marked hundreds of verses.
     */
    suspend fun bookmarkedAyahs(bookmarks: List<Bookmark>): List<BookmarkedAyah> =
        withContext(Dispatchers.IO) {
            if (bookmarks.isEmpty()) return@withContext emptyList()
            val createdAtByKey = bookmarks.associate { (it.surahId to it.ayah) to it.createdAt }
            bookmarks.chunked(400).flatMap { batch ->
                val placeholders = batch.joinToString(",") { "(?,?)" }
                val args = batch.flatMap { listOf(it.surahId.toString(), it.ayah.toString()) }
                    .toTypedArray()
                queryList(
                    """
                    SELECT s.id, s.name_arabic, s.name_transliteration,
                           s.name_translation, s.revelation_place, s.ayah_count,
                           a.ayah_number, a.text_uthmani, a.translation_en
                    FROM ayahs a
                    JOIN surahs s ON s.id = a.surah_id
                    WHERE (a.surah_id, a.ayah_number) IN ($placeholders)
                    ORDER BY a.surah_id, a.ayah_number
                    """.trimIndent(),
                    args,
                ) { c ->
                    val surah = Surah(
                        id = c.getInt(0),
                        nameArabic = c.getString(1),
                        nameTransliteration = c.getString(2),
                        nameTranslation = c.getString(3),
                        revelationPlace = c.getString(4),
                        ayahCount = c.getInt(5),
                    )
                    val ayah = c.getInt(6)
                    BookmarkedAyah(
                        surah = surah,
                        ayahNumber = ayah,
                        text = c.getString(7),
                        translation = c.getString(8),
                        createdAt = createdAtByKey[surah.id to ayah] ?: 0L,
                    )
                }
            }.sortedWith(compareBy({ it.surah.id }, { it.ayahNumber }))
        }

    /** Morphology for one reader word, or null when QAC had no row for that
     *  position (the known word-count mismatch ayahs). */
    suspend fun wordMorphology(surahId: Int, ayah: Int, position: Int): WordMorphology? =
        withContext(Dispatchers.IO) {
            database.db.rawQuery(
                """
                SELECT surah_id, ayah_number, position, root, lemma, pos, features
                FROM word_morphology
                WHERE surah_id = ? AND ayah_number = ? AND position = ?
                """.trimIndent(),
                arrayOf(surahId.toString(), ayah.toString(), position.toString()),
            ).use { c ->
                if (!c.moveToFirst()) return@withContext null
                WordMorphology(
                    surahId = c.getInt(0),
                    ayahNumber = c.getInt(1),
                    position = c.getInt(2),
                    root = c.getString(3),
                    lemma = c.getString(4),
                    pos = c.getString(5),
                    features = c.getString(6),
                )
            }
        }

    /** Surface form + gloss for one word — used when the Root Viewer opens
     *  before the full surah content is needed. */
    suspend fun wordAt(surahId: Int, ayah: Int, position: Int): Word? =
        withContext(Dispatchers.IO) {
            database.db.rawQuery(
                """
                SELECT position, arabic, translation_en, transliteration
                FROM words
                WHERE surah_id = ? AND ayah_number = ? AND position = ?
                """.trimIndent(),
                arrayOf(surahId.toString(), ayah.toString(), position.toString()),
            ).use { c ->
                if (!c.moveToFirst()) return@withContext null
                Word(
                    position = c.getInt(0),
                    arabic = c.getString(1),
                    translation = c.getString(2),
                    transliteration = c.getString(3),
                )
            }
        }

    /**
     * Quran-wide word search for the cover sheet: Arabic (diacritic-insensitive),
     * English gloss, or transliteration substring. Returns hits in mushaf order.
     * Blank / too-short / `surah:ayah` queries yield an empty list.
     */
    suspend fun searchWords(query: String): List<WordSearchHit> = withContext(Dispatchers.IO) {
        if (!isWordSearchQuery(query)) return@withContext emptyList()
        matchWordSearch(wordSearchIndex(), query, WORD_SEARCH_MAX_HITS)
    }

    private fun wordSearchIndex(): List<WordSearchIndexEntry> {
        wordSearchIndex?.let { return it }
        // One shared context per ayah rather than per word: `Cursor.getString()`
        // returns a fresh String for every row, so binding the ayah/surah
        // columns straight onto each of the 77k word entries duplicated ~31 M
        // characters (see [WordSearchAyahContext]). The map is local — the
        // contexts stay alive only through the entries that reference them.
        val contexts = HashMap<Int, WordSearchAyahContext>(7_000)
        val built = queryList(
            """
            SELECT w.surah_id, w.ayah_number, w.position, w.arabic, w.translation_en, w.transliteration,
                   a.text_uthmani, a.translation_en,
                   s.name_transliteration, s.name_arabic
            FROM words w
            JOIN ayahs a
              ON a.surah_id = w.surah_id AND a.ayah_number = w.ayah_number
            JOIN surahs s ON s.id = w.surah_id
            ORDER BY w.surah_id, w.ayah_number, w.position
            """.trimIndent(),
        ) { c ->
            val surahId = c.getInt(0)
            val ayahNumber = c.getInt(1)
            val arabic = c.getString(3)
            val translation = c.getString(4)
            val transliteration = c.getString(5)
            WordSearchIndexEntry(
                surahId = surahId,
                ayahNumber = ayahNumber,
                position = c.getInt(2),
                arabic = arabic,
                arabicNorm = normalizeArabicForSearch(arabic),
                translation = translation,
                translationLower = translation.lowercase(),
                transliteration = transliteration,
                transliterationLower = transliteration.lowercase(),
                context = contexts.getOrPut(surahId * 1_000 + ayahNumber) {
                    WordSearchAyahContext(
                        ayahText = c.getString(6),
                        ayahTranslation = c.getString(7),
                        surahNameTransliteration = c.getString(8),
                        surahNameArabic = c.getString(9),
                    )
                },
            )
        }
        wordSearchIndex = built
        return built
    }

    /** Root concordance: count + every occurrence in Quranic order, joined
     *  with the word's Arabic/gloss and surah name for the jump list. */
    suspend fun rootSummary(root: String): RootSummary? = withContext(Dispatchers.IO) {
        if (root.isBlank()) return@withContext null
        val count = database.db.rawQuery(
            "SELECT occurrence_count FROM roots WHERE root = ?",
            arrayOf(root),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        if (count == 0) return@withContext null
        val occurrences = queryList(
            """
            SELECT o.surah_id, o.ayah_number, o.position,
                   w.arabic, w.translation_en, s.name_transliteration
            FROM root_occurrences o
            JOIN words w
              ON w.surah_id = o.surah_id
             AND w.ayah_number = o.ayah_number
             AND w.position = o.position
            JOIN surahs s ON s.id = o.surah_id
            WHERE o.root = ?
            ORDER BY o.surah_id, o.ayah_number, o.position
            """.trimIndent(),
            arrayOf(root),
        ) { c ->
            RootOccurrence(
                surahId = c.getInt(0),
                ayahNumber = c.getInt(1),
                position = c.getInt(2),
                arabic = c.getString(3),
                translation = c.getString(4),
                surahNameTransliteration = c.getString(5),
            )
        }
        val lemmas = queryList(
            """
            SELECT lemma, pos, COUNT(*)
            FROM word_morphology
            WHERE root = ? AND lemma <> ''
            GROUP BY lemma, pos
            ORDER BY COUNT(*) DESC, lemma, pos
            """.trimIndent(),
            arrayOf(root),
        ) { c ->
            RootLemmaSummary(
                lemma = c.getString(0),
                pos = c.getString(1),
                occurrenceCount = c.getInt(2),
            )
        }
        RootSummary(
            root = root,
            occurrenceCount = count,
            occurrences = occurrences,
            lemmas = lemmas,
        )
    }

    private data class BundledTimingRows(
        val segments: Map<Int, List<Segment>>,
        val audioOnsets: Map<Int, Long>,
    )

    private fun bundledTimingRows(reciterId: Int, surahId: Int): BundledTimingRows =
        database.db.rawQuery(
            "SELECT ayah_number, segments, audio_onset_ms FROM timings " +
                "WHERE reciter_id = ? AND surah_id = ?",
            arrayOf(reciterId.toString(), surahId.toString()),
        ).use { c ->
            val segments = mutableMapOf<Int, List<Segment>>()
            val audioOnsets = mutableMapOf<Int, Long>()
            while (c.moveToNext()) {
                val ayah = c.getInt(0)
                segments[ayah] = parseSegments(c.getString(1))
                c.getLong(2).takeIf { it > 0L }?.let { audioOnsets[ayah] = it }
            }
            BundledTimingRows(segments, audioOnsets)
        }

    /** The bundled DB timings for a reciter+surah, with **no** Lab overrides
     * fused in — the shipped defaults. The Lab uses this to reset a single word
     * back to how the app shipped it. */
    suspend fun bundledTimings(reciterId: Int, surahId: Int): Map<Int, List<Segment>> =
        withContext(Dispatchers.IO) {
            bundledTimingRows(reciterId, surahId).segments
        }

    /**
     * Machine-generated V2 rows over the pure bundled V1 clock. Missing or
     * rejected V2 ayahs retain V1 segments, keeping coverage and confidence
     * separate without admitting Timing Lab patches into the experiment.
     */
    private suspend fun bundledV2Timings(
        reciterId: Int,
        surahId: Int,
    ): Map<Int, List<Segment>> = withContext(Dispatchers.IO) {
        val fallback = bundledTimings(reciterId, surahId)
        val acoustic = database.db.rawQuery(
            """
            SELECT t.ayah_number, t.segments, COUNT(w.position)
            FROM timings_v2 t
            JOIN words w
              ON w.surah_id = t.surah_id
             AND w.ayah_number = t.ayah_number
            WHERE t.reciter_id = ? AND t.surah_id = ?
            GROUP BY t.ayah_number, t.segments
            """.trimIndent(),
            arrayOf(reciterId.toString(), surahId.toString()),
        ).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    parseV2Segments(c.getString(1), expectedWordCount = c.getInt(2))
                        .takeIf { it.isNotEmpty() }
                        ?.let { put(c.getInt(0), it) }
                }
            }
        }
        fallback + acoustic
    }

    /** ayah number -> word segments, for one reciter and surah. Any
     * hand-corrected override from the Timings Lab takes precedence over the
     * bundled DB row, so the reader immediately reflects edits. The MP3 voice
     * onset remains authoritative: it shifts an old relative-to-speech edit
     * without changing any of its word spacing. */
    suspend fun timings(
        reciterId: Int,
        surahId: Int,
        scheme: TimingScheme = TimingScheme.V1,
    ): Map<Int, List<Segment>> =
        withContext(Dispatchers.IO) {
            if (scheme == TimingScheme.V2) {
                return@withContext bundledV2Timings(reciterId, surahId)
            }
            val bundled = bundledTimingRows(reciterId, surahId)
            if (timingOverrides == null) return@withContext bundled.segments
            val overrides = timingOverrides.overrides.value
            if (overrides.isEmpty() || !overrides.keys.any { it.reciterId == reciterId && it.surahId == surahId }) {
                return@withContext bundled.segments
            }
            val merged = bundled.segments.toMutableMap()
            for (entry in overrides) {
                val key = entry.key
                if (key.reciterId == reciterId && key.surahId == surahId) {
                    merged[key.ayah] = alignToAudioOnset(
                        entry.value,
                        bundled.audioOnsets[key.ayah] ?: 0L,
                    )
                }
            }
            merged
        }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Best-effort like the rest of the parse: a malformed row (the DB is
         * build-validated, so only conceivable via corruption) yields no
         * highlighting for that ayah rather than crashing the reader. */
        fun parseSegments(raw: String): List<Segment> =
            runCatching {
                json.decodeFromString<List<List<Long>>>(raw)
                    .filter { it.size >= 3 }
                    .map { Segment(it[0].toInt(), it[1], it[2]) }
                    .sortedBy { it.startMs }
            }.getOrDefault(emptyList())

        /**
         * Parses confidence-gated V2 rows. Invalid topology rejects the row;
         * the repository then uses its bundled V1 fallback for that ayah.
         */
        fun parseV2Segments(
            raw: String,
            expectedWordCount: Int? = null,
        ): List<Segment> =
            runCatching {
                val parsed = json.decodeFromString<List<TimingV2Segment>>(raw)
                    .map { segment ->
                        require(segment.startMs < segment.endMs)
                        var lastOffset = 0L
                        var lastProgress = 0f
                        val keyframes = segment.keyframes.map { keyframe ->
                            require(keyframe.offsetMs in 1..(segment.endMs - segment.startMs))
                            require(keyframe.offsetMs > lastOffset)
                            require(keyframe.progress >= lastProgress && keyframe.progress <= 1f)
                            lastOffset = keyframe.offsetMs
                            lastProgress = keyframe.progress
                            SubwordKeyframe(keyframe.offsetMs, keyframe.progress)
                        }
                        require(keyframes.isNotEmpty() && keyframes.last().progress == 1f)
                        Segment(
                            position = segment.position,
                            startMs = segment.startMs,
                            endMs = segment.endMs,
                            subwordKeyframes = keyframes,
                        )
                    }
                require(parsed.zipWithNext().all { (left, right) ->
                    left.startMs < right.startMs
                })
                if (expectedWordCount != null) {
                    var expected = 1
                    val seen = HashSet<Int>(expectedWordCount)
                    for (segment in parsed) {
                        require(segment.position in 1..expectedWordCount)
                        if (seen.add(segment.position)) {
                            require(segment.position == expected++)
                        }
                    }
                    require(expected == expectedWordCount + 1)
                }
                parsed
            }.getOrDefault(emptyList())
    }
}
