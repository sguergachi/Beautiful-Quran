package com.beautifulquran.domain

import com.beautifulquran.data.model.WordSearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordSearchTest {

    private fun entry(
        surahId: Int,
        ayah: Int,
        position: Int,
        arabic: String,
        translation: String,
        transliteration: String = "",
        ayahText: String = arabic,
        ayahTranslation: String = "",
    ): WordSearchIndexEntry {
        val norm = normalizeArabicForSearch(arabic)
        return WordSearchIndexEntry(
            surahId = surahId,
            ayahNumber = ayah,
            position = position,
            arabic = arabic,
            arabicNorm = norm,
            translation = translation,
            translationLower = translation.lowercase(),
            transliteration = transliteration,
            transliterationLower = transliteration.lowercase(),
            context = WordSearchAyahContext(
                ayahText = ayahText,
                ayahTranslation = ayahTranslation,
                surahNameTransliteration = "Surah$surahId",
                surahNameArabic = "س$surahId",
            ),
        )
    }

    private val index = listOf(
        entry(1, 1, 1, "بِسۡمِ", "In the name", "bis'mi", "بِسۡمِ ٱللَّهِ ٱلرَّحۡمَٰنِ ٱلرَّحِيمِ"),
        entry(1, 1, 3, "ٱلرَّحۡمَٰنِ", "the Most Gracious", "al-rahmani", "بِسۡمِ ٱللَّهِ ٱلرَّحۡمَٰنِ ٱلرَّحِيمِ"),
        entry(1, 1, 4, "ٱلرَّحِيمِ", "the Most Merciful", "al-rahimi", "بِسۡمِ ٱللَّهِ ٱلرَّحۡمَٰنِ ٱلرَّحِيمِ"),
        entry(2, 163, 2, "ٱلرَّحۡمَٰنُ", "the Most Gracious", "al-rahmanu", "وَإِلَٰهُكُمۡ إِلَٰهٞ وَٰحِدٞۖ …"),
        entry(2, 37, 1, "فَتَابَ", "so He turned", "fa-taba"),
        entry(55, 1, 1, "ٱلرَّحۡمَٰنُ", "The Most Merciful", "al-rahman"),
    )

    @Test
    fun `normalize strips tashkeel and unifies alef`() {
        assertEquals("الرحمن", normalizeArabicForSearch("ٱلرَّحۡمَٰنِ"))
        assertEquals("الله", normalizeArabicForSearch("ٱللَّهِ"))
        assertEquals("بسم", normalizeArabicForSearch("بِسۡمِ"))
    }

    @Test
    fun `english gloss matches are case-insensitive`() {
        val hits = matchWordSearch(index, "merciful")
        assertEquals(listOf(1 to 4, 55 to 1), hits.map { it.surahId to it.position })
    }

    @Test
    fun `arabic matches without diacritics`() {
        val hits = matchWordSearch(index, "الرحمن")
        assertTrue(hits.any { it.surahId == 1 && it.position == 3 })
        assertTrue(hits.any { it.surahId == 2 && it.position == 2 })
        assertTrue(hits.any { it.surahId == 55 && it.position == 1 })
    }

    @Test
    fun `one edit typo falls back to fuzzy word matches`() {
        assertEquals(
            listOf(1 to 4, 55 to 1),
            matchWordSearch(index, "mercifl").map { it.surahId to it.position },
        )
        assertEquals(2, matchWordSearch(index, "mercifull").size)
        assertEquals(
            listOf(1 to 4, 55 to 1),
            matchWordSearch(index, "mercfiul").map { it.surahId to it.position },
        )
        assertTrue(matchWordSearch(index, "الرحمان").any { it.surahId == 1 && it.position == 3 })
    }

    @Test
    fun `semantic vocabulary outranks and suppresses spelling neighbors`() {
        val semanticIndex = listOf(
            entry(7, 154, 2, "سَكَتَ", "(was) calmed", ayahTranslation = "the anger subsided"),
            entry(7, 44, 1, "وَنَادَىٰ", "will call out", ayahTranslation = "they will call out"),
            entry(9, 26, 1, "سَكِينَتَهُ", "His tranquility", ayahTranslation = "His tranquility"),
        )
        val hits = matchWordSearch(
            semanticIndex,
            "calm",
            thesaurus = mapOf("calm" to listOf(RelatedSearchTerm("tranquility", 2))),
        )
        assertEquals(listOf(7 to 154, 9 to 26), hits.map { it.surahId to it.ayahNumber })
        assertEquals("tranquility", hits[1].matchTerm)
        assertTrue(hits.none { it.ayahNumber == 44 })
    }

    @Test
    fun `quoted query stays literal and disables fuzzy spelling`() {
        assertTrue(matchWordSearch(index, "\"mercifull\"").isEmpty())
        assertEquals(2, matchWordSearch(index, "\"merciful\"").size)
        assertEquals(2, matchWordSearch(index, "“merciful”").size)
        assertTrue(matchWordSearch(index, "\"mercy\"").isEmpty())
        assertFalse(isWordSearchQuery("\"\""))
    }

    @Test
    fun `quoted phrase searches full ayah translation`() {
        val phrase = listOf(
            entry(
                surahId = 1,
                ayah = 1,
                position = 1,
                arabic = "بِسْمِ",
                translation = "In the name",
                ayahTranslation = "In the name of Allah, the Entirely Merciful.",
            ),
        )
        val hit = matchWordSearch(phrase, "\"name of Allah\"").single()
        assertEquals(0, hit.position)
    }

    @Test
    fun `ontology vocabulary retrieves and labels related concepts`() {
        val concept = SearchConcept(
            name = "Divine Mercy",
            primaryTerms = listOf("mercy of Allah", "divine compassion"),
            secondaryTerms = listOf("clemency", "forgiveness"),
            category = "Divine Attributes and Signs",
            domain = "Aqeedah",
            ayahKeys = intArrayOf(1_001, 55_001),
        )
        val hits = matchWordSearch(index, "clemency", concepts = listOf(concept))
        assertEquals(listOf(1 to 1, 55 to 1), hits.map { it.surahId to it.ayahNumber })
        assertTrue(hits.all { it.position == 0 && it.matchLabel == "Divine Mercy" })
        assertTrue(matchWordSearch(index, "clemncy", concepts = listOf(concept)).isNotEmpty())
        assertTrue(matchWordSearch(index, "\"clemency\"", concepts = listOf(concept)).isEmpty())
        assertTrue(conceptRelevance(concept, parseSearchQuery("show me verses about clemency")) > 0)
    }

    @Test
    fun `literal relevance ranks ahead of concept matches`() {
        val lexical = index + entry(60, 1, 1, "رَحْمَة", "clemency")
        val concept = SearchConcept(
            name = "Divine Mercy",
            primaryTerms = listOf("clemency"),
            secondaryTerms = emptyList(),
            category = "Divine Attributes",
            domain = "Aqeedah",
            ayahKeys = intArrayOf(1_001),
        )
        assertEquals(60, matchWordSearch(lexical, "clemency", concepts = listOf(concept)).first().surahId)
    }

    @Test
    fun `matched Arabic root expands to related word forms`() {
        val rooted = listOf(
            entry(2, 37, 1, "فَتَابَ", "so He turned").copy(root = "توب"),
            entry(9, 104, 2, "ٱلتَّوَّٰبُ", "the Oft-Returning").copy(root = "توب"),
        )
        val hits = matchWordSearch(rooted, "turned")
        assertEquals(listOf(2, 9), hits.map { it.surahId })
        assertEquals(listOf(1, 2), hits.map { it.position })
    }

    @Test
    fun `exact matches take precedence over fuzzy neighbors`() {
        val neighbors = listOf(
            entry(1, 1, 1, "قَالَ", "lone"),
            entry(2, 1, 1, "حُبّ", "love"),
        )
        assertEquals(listOf("love"), matchWordSearch(neighbors, "love", maxHits = 1).map { it.translation })
    }

    @Test
    fun `short and distant words do not fuzzy match`() {
        assertFalse(fuzzyWordContains("the Most Merciful", "met"))
        assertFalse(fuzzyWordContains("the Most Merciful", "mercy"))
    }

    @Test
    fun `short queries yield nothing`() {
        assertTrue(matchWordSearch(index, "a").isEmpty())
        assertTrue(matchWordSearch(index, " ").isEmpty())
        assertFalse(isWordSearchQuery("a"))
        assertTrue(isWordSearchQuery("ab"))
    }

    @Test
    fun `sections truncate until expanded`() {
        val hits = List(5) { i ->
            WordSearchHit(
                surahId = 2,
                ayahNumber = i + 1,
                position = 1,
                arabic = "و",
                translation = "and",
                transliteration = "wa",
                ayahText = "و",
                ayahTranslation = "",
                surahNameTransliteration = "Al-Baqarah",
                surahNameArabic = "البقرة",
            )
        }
        val collapsed = sectionWordSearchHits(hits, expandedSurahIds = emptySet(), previewLimit = 3)
        assertEquals(1, collapsed.size)
        assertEquals(3, collapsed[0].hits.size)
        assertEquals(5, collapsed[0].totalCount)
        assertEquals(2, collapsed[0].hiddenCount)
        assertFalse(collapsed[0].expanded)

        val expanded = sectionWordSearchHits(hits, expandedSurahIds = setOf(2), previewLimit = 3)
        assertEquals(5, expanded[0].hits.size)
        assertEquals(0, expanded[0].hiddenCount)
        assertTrue(expanded[0].expanded)
    }

    @Test
    fun `sections preserve Quranic surah order`() {
        val hits = matchWordSearch(index, "الرحمن")
        val sections = sectionWordSearchHits(hits, emptySet())
        assertEquals(listOf(1, 2, 55), sections.map { it.surahId })
    }

    @Test
    fun `ayah highlight marks the word at position`() {
        val text = "بِسۡمِ ٱللَّهِ ٱلرَّحۡمَٰنِ ٱلرَّحِيمِ"
        val spans = ayahHighlightSpans(text, position = 3, fallbackWord = "ٱلرَّحۡمَٰنِ")
        val highlighted = spans.filter { it.highlighted }.map { it.text }
        assertEquals(listOf("ٱلرَّحۡمَٰنِ"), highlighted)
        assertEquals(text, spans.joinToString("") { it.text })
    }

    @Test
    fun `english translation highlight prefers the query`() {
        val ayah =
            "In the name of Allah, the Entirely Merciful, the Especially Merciful."
        val spans = englishTranslationHighlightSpans(ayah, "Merciful", "the Most Merciful")
        assertEquals(
            listOf("Merciful", "Merciful"),
            spans.filter { it.highlighted }.map { it.text },
        )
        assertEquals(ayah, spans.joinToString("") { it.text })
    }

    @Test
    fun `english translation highlight falls back to gloss token`() {
        val ayah = "And He is the Oft-Returning, the Merciful."
        val spans = englishTranslationHighlightSpans(
            ayah,
            "التواب",
            "(is) the Oft-returning (to mercy)",
        )
        assertTrue(spans.any { it.highlighted && it.text.equals("Oft-Returning", ignoreCase = true) })
    }

    @Test
    fun `english highlight chooses the word that won typo fallback`() {
        val spans = englishTranslationHighlightSpans(
            "And the companions of Paradise will call out",
            "calp",
            "And they will call out",
        )
        assertEquals(listOf("call"), spans.filter { it.highlighted }.map { it.text })
        assertTrue(
            englishTranslationHighlightSpans(
                "their inscription was guidance",
                "calp",
                "(was) calmed",
            ).none { it.highlighted },
        )
        assertTrue(
            englishTranslationHighlightSpans("They will answer", "calp", "will")
                .none { it.highlighted },
        )
    }

    @Test
    fun `semantic highlight uses a visible concept word`() {
        val spans = englishTranslationHighlightSpans(
            "Peace be upon you.",
            "calm",
            wordGloss = "",
            semanticLabel = "Peace and Reconciliation",
        )
        assertEquals(listOf("Peace"), spans.filter { it.highlighted }.map { it.text })
    }

    @Test
    fun `english snippet windows around a mid-ayah match`() {
        val words = (1..40).joinToString(" ") { "w$it" }
        val ayah = "$words resting place more words after that keep going"
        val spans = englishTranslationHighlightSpans(ayah, "rest", "a resting place")
        val text = spans.joinToString("") { it.text }
        assertTrue(text.contains("resting", ignoreCase = true))
        assertTrue(spans.any { it.highlighted && it.text.startsWith("rest", ignoreCase = true) })
        assertTrue(text.startsWith("…") || text.length < ayah.length)
        // Lead-in words far from the match should be clipped.
        assertFalse(text.contains("w1 "))
    }

    @Test
    fun `match uses gloss line when SI ayah lacks the query`() {
        val entries = listOf(
            entry(
                surahId = 2,
                ayah = 22,
                position = 4,
                arabic = "ٱلۡأَرۡضَ",
                translation = "the earth",
                ayahTranslation = "[He] who made for you the earth a bed [spread out]",
            ),
            entry(
                surahId = 2,
                ayah = 22,
                position = 5,
                arabic = "فِرَٰشٗا",
                translation = "a resting place",
                ayahTranslation = "[He] who made for you the earth a bed [spread out]",
            ),
            entry(
                surahId = 2,
                ayah = 22,
                position = 6,
                arabic = "وَٱلسَّمَآءَ",
                translation = "and the sky",
                ayahTranslation = "[He] who made for you the earth a bed [spread out]",
            ),
        )
        val hits = matchWordSearch(entries, "rest")
        assertEquals(1, hits.size)
        assertTrue(hits[0].ayahTranslation.contains("resting", ignoreCase = true))
        assertTrue(hits[0].ayahTranslation.contains("the earth"))
        assertTrue(hits[0].ayahTranslation.contains("and the sky"))
        val spans = englishTranslationHighlightSpans(
            hits[0].ayahTranslation,
            "rest",
            hits[0].translation,
        )
        assertTrue(spans.any { it.highlighted && it.text.startsWith("rest", ignoreCase = true) })
    }

    @Test
    fun `windowAroundMatch keeps neighbors and ellipsis`() {
        val text = "one two three four five six seven eight nine ten eleven twelve"
        val windowed = windowAroundMatch(text, "seven", wordsBefore = 2, wordsAfter = 2)
        assertEquals("…five six seven eight nine…", windowed)
    }
}
