package com.beautifulquran.data.model

data class Surah(
    val id: Int,
    val nameArabic: String,
    val nameTransliteration: String,
    val nameTranslation: String,
    val revelationPlace: String,
    val ayahCount: Int,
)

data class Word(
    val position: Int,
    val arabic: String,
    val translation: String,
    val transliteration: String,
    val qcfV2: String = "",
    val qcfPage: Int = 0,
    val qcfLine: Int = 0,
    val qcfSpanEnd: Int = position,
)

data class Ayah(
    val surahId: Int,
    val number: Int,
    val text: String,
    val translation: String,
    val page: Int = 0,
    val words: List<Word>,
)

data class Reciter(
    val id: Int,
    val slug: String,
    val name: String,
    val style: String,
    val hasTimings: Boolean,
) {
    fun audioUrl(surah: Int, ayah: Int): String {
        val s = surah.toString().padStart(3, '0')
        val a = ayah.toString().padStart(3, '0')
        return "https://everyayah.com/data/$slug/$s$a.mp3"
    }

    /**
     * Basmalah lead-in audio: Al-Fatihah 1:1 (`001001.mp3`). That file is part
     * of every everyayah pack (unlike the optional `bismillah.mp3`), and its
     * word timings are already in the DB — so chapter-start playback and the
     * calligraphy ink wash share one source of truth.
     */
    fun basmalahAudioUrl(): String = audioUrl(surah = 1, ayah = 1)
}

/** One highlighted span: word [position] is active from [startMs] until [endMs]. */
data class Segment(
    val position: Int,
    val startMs: Long,
    val endMs: Long,
)

data class SurahContent(
    val surah: Surah,
    val ayahs: List<Ayah>,
)

/** Read-only Quran text joined to one of the reader's persisted bookmarks. */
data class BookmarkedAyah(
    val surah: Surah,
    val ayahNumber: Int,
    val text: String,
    val translation: String,
    val createdAt: Long,
)

/** Morphology for one reader word, from the Quranic Arabic Corpus. */
data class WordMorphology(
    val surahId: Int,
    val ayahNumber: Int,
    val position: Int,
    val root: String,
    val lemma: String,
    val pos: String,
    val features: String,
)

/** One hit in a root concordance, joined with the word's surface form. */
data class RootOccurrence(
    val surahId: Int,
    val ayahNumber: Int,
    val position: Int,
    val arabic: String,
    val translation: String,
    val surahNameTransliteration: String,
)

data class RootSummary(
    val root: String,
    val occurrenceCount: Int,
    val occurrences: List<RootOccurrence>,
    /** Corpus lemmas annotated with this root, ordered by frequency. */
    val lemmas: List<RootLemmaSummary>,
)

/** One exact QAC lemma/POS analysis found under a root. */
data class RootLemmaSummary(
    val lemma: String,
    val pos: String,
    val occurrenceCount: Int,
    /** Most common word-by-word English rendering of this form; may be blank. */
    val gloss: String = "",
)

/** Lane's lexicon article for one root, as shipped in lexicon.db. */
data class LexiconEntry(
    val root: String,
    /** Display text: English prose with Arabic inline, Lane's own divisions. */
    val text: String,
    /** First printed page of the article, or 0 when the source records none. */
    val page: Int,
    /** Perseus' required credit line, carried with their text. */
    val credit: String,
)
