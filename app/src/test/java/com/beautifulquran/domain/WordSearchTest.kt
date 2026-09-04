package com.beautifulquran.domain

import com.beautifulquran.data.model.WordSearchHit
import com.beautifulquran.data.model.WordSearchDisplaySource
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
        assertTrue(hits.all { it.matchReason == "Text match" })
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
        val corrected = matchWordSearch(index, "mercifl")
        assertEquals(
            listOf(1 to 4, 55 to 1),
            corrected.map { it.surahId to it.position },
        )
        assertTrue(corrected.all { it.matchReason == "Spelling match" })
        assertTrue(corrected.all { it.matchTerms == listOf("merciful") })
        assertEquals("merciful", spellingCorrection(corrected))
        assertEquals(null, spellingCorrection(matchWordSearch(index, "merciful")))
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
        assertEquals(listOf("tranquility"), hits[1].matchTerms)
        assertEquals("Related · tranquility", hits[1].matchReason)
        assertTrue(hits.none { it.ayahNumber == 44 })
    }

    @Test
    fun `search observes cancellation during the scan`() {
        val failure = runCatching {
            matchWordSearch(index, "merciful", checkCancelled = { error("cancelled") })
        }.exceptionOrNull()
        assertEquals("cancelled", failure?.message)
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
        assertEquals(1, hit.position)
    }

    @Test
    fun `search uses only reader-visible text sources`() {
        val verse = listOf(
            entry(
                surahId = 19,
                ayah = 45,
                position = 1,
                arabic = "قَرِينًا",
                translation = "a companion",
                transliteration = "qareenan",
                ayahText = "فَتَكُونَ لِلشَّيْطَانِ وَلِيًّا",
                ayahTranslation = "so you would be to Satan a companion [in Hellfire]",
            ),
        )
        val glossOnly = WordSearchSources(
            arabic = false,
            wordGloss = true,
            transliteration = false,
            verseTranslation = false,
        )
        val arabicOnly = WordSearchSources(
            arabic = true,
            wordGloss = false,
            transliteration = false,
            verseTranslation = false,
        )

        assertTrue(matchWordSearch(verse, "\"Hellfire\"", sources = glossOnly).isEmpty())
        val glossHit = matchWordSearch(verse, "companion", sources = glossOnly).single()
        assertEquals("a companion", glossHit.displayText)
        assertEquals(WordSearchDisplaySource.WORD_GLOSS, glossHit.displaySource)
        assertTrue(matchWordSearch(verse, "companion", sources = arabicOnly).isEmpty())
        val arabicHit = matchWordSearch(verse, "قرينا", sources = arabicOnly).single()
        assertEquals("فَتَكُونَ لِلشَّيْطَانِ وَلِيًّا", arabicHit.displayText)
        assertEquals(WordSearchDisplaySource.ARABIC, arabicHit.displaySource)
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
        assertTrue(hits.all { it.matchReason == "Concept · Divine Mercy" })
        assertEquals(null, spellingCorrection(hits))
        val corrected = matchWordSearch(index, "clemncy", concepts = listOf(concept))
        assertTrue(corrected.all { it.matchTerms.firstOrNull() == "clemency" })
        assertEquals("clemency", spellingCorrection(corrected))
        val corruption = concept.copy(
            name = "Prohibition of Corruption on Earth",
            primaryTerms = listOf("do not corrupt the earth"),
            secondaryTerms = emptyList(),
        )
        assertEquals(
            "corrupt",
            spellingCorrection(matchWordSearch(index, "corrupy", concepts = listOf(corruption))),
        )
        assertTrue(matchWordSearch(index, "\"clemency\"", concepts = listOf(concept)).isEmpty())
        assertTrue(conceptRelevance(concept, parseSearchQuery("show me verses about clemency")) > 0)
    }

    @Test
    fun `multi-word vocabulary retrieves a concept without another text scan`() {
        val concept = SearchConcept(
            name = "Wealth Management",
            primaryTerms = listOf("wealth management"),
            secondaryTerms = listOf("saving money"),
            category = "Economic Transactions",
            domain = "Mu'amalat",
            ayahKeys = intArrayOf(1_001),
        )

        val hit = matchWordSearch(index, "saving money", concepts = listOf(concept)).single()

        assertEquals("Concept · Wealth Management", hit.matchReason)
        assertEquals(0, hit.position)
        assertEquals(null, spellingCorrection(listOf(hit)))
        assertTrue(matchWordSearch(index, "\"saving money\"", concepts = listOf(concept)).isEmpty())
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
    fun `corrected concepts rank visible evidence ahead of broad associations`() {
        val evidenceIndex = listOf(
            entry(
                2,
                9,
                1,
                "يُخَادِعُونَ",
                "They deceive",
                ayahTranslation = "They deceive themselves",
            ),
            entry(
                2,
                11,
                1,
                "تُفْسِدُوا",
                "cause corruption",
                ayahTranslation = "Do not cause corruption on earth",
            ),
        )
        val broad = SearchConcept(
            "Diseases of the Heart",
            listOf("corrupt heart"),
            emptyList(),
            "Heart and Soul",
            "Tazkiyah",
            intArrayOf(2_009),
        )
        val direct = SearchConcept(
            "Prohibition of Corruption on Earth",
            listOf("do not corrupt the earth"),
            emptyList(),
            "Stewardship",
            "Ethics",
            intArrayOf(2_011),
        )

        val hits = matchWordSearch(evidenceIndex, "corrupy", concepts = listOf(broad, direct))

        assertEquals(listOf(11, 9), hits.map { it.ayahNumber })
        assertEquals("Prohibition of Corruption on Earth", hits.first().matchLabel)
        assertEquals("corrupt", spellingCorrection(hits))
    }

    @Test
    fun `concept result targets the word behind its visible highlight`() {
        val hearts = listOf(
            entry(3, 7, 16, "فِي", "in", ayahTranslation = "those in whose hearts is deviation"),
            entry(
                3,
                7,
                17,
                "قُلُوبِهِمْ",
                "their hearts",
                ayahTranslation = "those in whose hearts is deviation",
            ),
            entry(
                3,
                7,
                18,
                "زَيْغٌ",
                "is deviation",
                ayahTranslation = "those in whose hearts is deviation",
            ),
        )
        val concept = SearchConcept(
            "Diseases of the Heart",
            listOf("corrupt heart"),
            emptyList(),
            "Heart and Soul",
            "Tazkiyah",
            intArrayOf(3_007),
        )

        val hit = matchWordSearch(hearts, "corrupt", concepts = listOf(concept)).single()

        assertEquals(17, hit.position)
        assertEquals("their hearts", hit.translation)
        assertTrue(
            englishTranslationHighlightSpans(
                hit.ayahTranslation,
                "corrupt",
                hit.translation,
                hit.matchLabel.orEmpty(),
                hit.matchTerms,
            ).any { it.highlighted && it.text.equals("hearts", ignoreCase = true) },
        )
    }

    @Test
    fun `concept vocabulary targets Fire but never the substring in firewood`() {
        val verses = listOf(
            entry(32, 20, 1, "عَذَابَ", "the punishment"),
            entry(32, 20, 2, "ٱلنَّارِ", "the Fire"),
            entry(90, 20, 1, "عَلَيْهِمْ", "Over them"),
            entry(90, 20, 2, "نَارٌ", "Fire"),
            entry(111, 4, 1, "حَمَّالَةَ", "the carrier"),
            entry(111, 4, 2, "ٱلْحَطَبِ", "of firewood"),
        )
        val concepts = listOf(
            SearchConcept(
                "Punishments of Hell",
                listOf("hell punishment"),
                listOf("fire punishment"),
                "Afterlife",
                "Aqeedah",
                intArrayOf(32_020),
            ),
            SearchConcept(
                "Description of Hellfire",
                listOf("hellfire", "blazing fire"),
                listOf("fire of hell"),
                "Afterlife",
                "Aqeedah",
                intArrayOf(90_020),
            ),
            SearchConcept(
                "People of the Fire",
                listOf("people of hell"),
                listOf("dwellers of fire"),
                "Afterlife",
                "Aqeedah",
                intArrayOf(111_004),
            ),
        )
        val glossOnly = WordSearchSources(
            arabic = false,
            wordGloss = true,
            transliteration = false,
            verseTranslation = false,
        )

        val hits = matchWordSearch(verses, "hell", concepts = concepts, sources = glossOnly)
        val punishmentAndFire = hits.single { it.surahId == 32 }
        val fire = hits.single { it.surahId == 90 }
        val firewood = hits.single { it.surahId == 111 }

        assertEquals(listOf(1, 2), punishmentAndFire.targetPositions)
        assertEquals(2, fire.position)
        assertEquals(listOf(2), fire.targetPositions)
        assertEquals("Fire", fire.translation)
        assertEquals(0, firewood.position)
        assertTrue(firewood.targetPositions.isEmpty())
        assertEquals(null, spellingCorrection(hits))
        assertEquals(
            listOf("Fire"),
            englishTranslationHighlightSpans(
                "The Fire will burn the carrier of firewood",
                "hell",
                "",
                fire.matchLabel.orEmpty(),
                fire.matchTerms,
            ).filter(AyahTextSpan::highlighted).map(AyahTextSpan::text),
        )
        assertTrue(
            englishTranslationHighlightSpans(
                firewood.displayText,
                "hell",
                "",
                firewood.matchLabel.orEmpty(),
                firewood.matchTerms,
            ).none(AyahTextSpan::highlighted),
        )
    }

    @Test
    fun `translation-only auxiliary targets the nearby visible gloss`() {
        val cases = listOf(
            listOf(
                entry(2, 17, 16, "فِي", "(so) not", ayahTranslation = "they could not see"),
                entry(2, 17, 17, "يُبْصِرُونَ", "(do) they see", ayahTranslation = "they could not see"),
            ) to 17,
            listOf(
                entry(2, 20, 16, "ٱللَّهُ", "Allah", ayahTranslation = "He could have taken away their hearing"),
                entry(2, 20, 17, "لَذَهَبَ", "He would certainly have taken away", ayahTranslation = "He could have taken away their hearing"),
            ) to 17,
            listOf(
                entry(2, 71, 23, "كَادُواْ", "they were near", ayahTranslation = "they could hardly do it"),
                entry(2, 71, 24, "يَفۡعَلُونَ", "(to) doing (it)", ayahTranslation = "they could hardly do it"),
            ) to 24,
            listOf(
                entry(3, 80, 1, "وَلَا", "And not", ayahTranslation = "Nor could he order you"),
                entry(3, 80, 2, "يَأۡمُرَكُمۡ", "he will order you", ayahTranslation = "Nor could he order you"),
            ) to 2,
        )

        cases.forEach { (auxiliary, expectedPosition) ->
            assertEquals(expectedPosition, matchWordSearch(auxiliary, "could").single().position)
        }
    }

    @Test
    fun `translator addition never falls through a preposition into indeed`() {
        val ayahTranslation =
            "O my father, indeed I fear a punishment, so you would be a companion [in Hellfire]"
        val ayah = listOf(
            entry(19, 45, 1, "يَـٰٓأَبَتِ", "O my father", ayahTranslation = ayahTranslation),
            entry(19, 45, 2, "إِنِّيٓ", "Indeed, I", ayahTranslation = ayahTranslation),
            entry(19, 45, 3, "أَخَافُ", "[I] fear", ayahTranslation = ayahTranslation),
            entry(19, 45, 4, "وَلِيّٗا", "a friend", ayahTranslation = ayahTranslation),
        )

        val hit = matchWordSearch(ayah, "hell").single()

        assertEquals(0, hit.position)
        assertEquals("", hit.translation)
    }

    @Test
    fun `gloss alignment is bounded and inflection aware`() {
        assertEquals(0, glossAlignmentRelevance("Indeed, I", "in"))
        assertTrue(glossAlignmentRelevance("their hearts", "heart") > 0)
        assertTrue(glossAlignmentRelevance("(do) they see", "see") > 0)
        assertTrue(glossAlignmentRelevance("(to) doing (it)", "do") > 0)
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
        assertEquals(listOf("Text match", "Same Arabic root"), hits.map { it.matchReason })
        assertEquals(listOf("the Oft-Returning"), hits[1].matchTerms)
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
    fun `semantic highlight uses every visible related concept word`() {
        val spans = englishTranslationHighlightSpans(
            "Peace and reconciliation brought tranquility and stillness.",
            "calm",
            wordGloss = "",
            semanticLabel = "Peace and Reconciliation",
            semanticTerms = listOf("tranquility", "stillness"),
        )
        assertEquals(
            listOf("Peace", "reconciliation", "tranquility", "stillness"),
            spans.filter { it.highlighted }.map { it.text },
        )
        assertTrue(spans.any { !it.highlighted && "and" in it.text })
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
        assertTrue(hits[0].displayText.contains("resting", ignoreCase = true))
        assertTrue(hits[0].displayText.contains("the earth"))
        assertTrue(hits[0].displayText.contains("and the sky"))
        val spans = englishTranslationHighlightSpans(
            hits[0].displayText,
            "rest",
            hits[0].translation,
        )
        assertTrue(spans.any { it.highlighted && it.text.startsWith("rest", ignoreCase = true) })
    }

    @Test
    fun `gloss line coalesces only adjacent shared phrase copies`() {
        val entries = listOf(
            entry(25, 70, 1, "إِلَّا", "Except"),
            entry(25, 70, 6, "وَعَمِلَ", "righteous deeds"),
            entry(25, 70, 7, "صَٰلِحٗا", "righteous deeds"),
            entry(25, 70, 9, "يُبَدِّلُ", "Allah will replace"),
            entry(25, 70, 10, "ٱللَّهُ", "Allah will replace"),
            entry(25, 70, 11, "سَيِّـَٔاتِهِمۡ", "their evil deeds"),
            entry(25, 70, 12, "سَلَـٰمٰا", "Peace"),
            entry(25, 70, 13, "سَلَـٰمٰا", "Peace"),
        )

        assertEquals(
            "Except righteous deeds Allah will replace their evil deeds Peace Peace",
            sameAyahGlossLine(entries, 2),
        )
    }

    @Test
    fun `windowAroundMatch keeps neighbors and ellipsis`() {
        val text = "one two three four five six seven eight nine ten eleven twelve"
        val windowed = windowAroundMatch(text, "seven", wordsBefore = 2, wordsAfter = 2)
        assertEquals("…five six seven eight nine…", windowed)
    }
}
