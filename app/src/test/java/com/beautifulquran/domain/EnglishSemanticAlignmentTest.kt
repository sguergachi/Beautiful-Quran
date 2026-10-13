package com.beautifulquran.domain

import com.beautifulquran.data.model.Ayah
import com.beautifulquran.data.model.Surah
import com.beautifulquran.data.model.SurahContent
import com.beautifulquran.data.model.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixed meaning correspondences, including words a literal diagnostic cannot judge. */
class EnglishSemanticAlignmentTest {
    @Test
    fun `a paraphrased shared verb phrase cannot be pushed beyond its subject`() {
        val text = "Allah has already given you victory in many regions"
        val alignment = EnglishWordAlignment.align(text,
            listOf("Verily", "Allah helped you", "Allah helped you", "in", "regions", "many"),
            listOf("قَد", "نَصَرَ", "ٱللَّه", "فِى", "مَوَاطِن", "كَثِيرَة"),
            listOf("لَقَدۡ", "نَصَرَكُمُ", "ٱللَّهُ", "فِي", "مَوَاطِنَ", "كَثِيرَةٖ"),
        )!!
        for (word in listOf("has", "already", "given", "you", "victory")) {
            val owner = alignment.positionAt((text.indexOf(word) + 1f) / text.length)
            org.junit.Assert.assertTrue("$word must not move to the following preposition", owner <= 3)
        }
        assertEquals(3, alignment.positionAt(1f / text.length))
        assertEquals(4, alignment.positionAt((text.indexOf(" in ") + 2f) / text.length))
    }

    @Test
    fun `a sentence closure respects a separately spoken grammar phrase`() {
        val text = "stays against you. Next"
        val alignment = EnglishWordAlignment.align(text, listOf("against you", "stays", "next"))!!
        assertEquals(1, alignment.positionAt((text.indexOf("against") + 1f) / text.length))
        assertEquals(1, alignment.positionAt((text.indexOf("you") + 1f) / text.length))
    }

    @Test
    fun `reviewed negative cases retain contextual verbs and literal grammar tails`() {
        val rows = javaClass.getResourceAsStream("/english-review-regressions.tsv")!!
            .bufferedReader().use { it.readLines() }.associate { line ->
                val columns = line.split('\t')
                columns[0] to columns
            }
        for ((key, phrase, expected) in listOf(
            Triple("9:4", "against you", 12), Triple("23:83", "before", 5),
        )) {
            val (_, text, glosses, lemmas, arabic) = rows.getValue(key)
            val alignment = EnglishWordAlignment.align(text, glosses.split('|'), lemmas.split('|'), arabic.split('|'))!!
            phrase.split(' ').forEach { token ->
                val start = text.indexOf(phrase) + phrase.indexOf(token)
                assertEquals("$key $token", expected, alignment.positionAt((start + 0.5f) / text.length))
            }
        }
        val (_, text, glosses, lemmas, arabic) = rows.getValue("9:25")
        val alignment = EnglishWordAlignment.align(text, glosses.split('|'), lemmas.split('|'), arabic.split('|'))!!
        assertEquals(3, alignment.positionAt(0.01f))
        for (token in listOf("has", "already", "given", "you", "victory")) {
            val offset = (text.indexOf(token) + 0.5f) / text.length
            org.junit.Assert.assertTrue("$token stranded after the shared verb and subject", alignment.positionAt(offset) <= 3)
        }
    }

    @Test
    fun `ever after keeps the complete abadan meaning`() {
        verifyCases("english-ever-after-owners.tsv", 1, checkTaps = true)
        for (phrase in listOf("ever after", "ever afterwards")) {
            val text = "$phrase. And those"
            val glosses = listOf("ever", "And those")
            val alignment = EnglishWordAlignment.align(text, glosses)!!
            for (offset in listOf(1, phrase.length - 2)) {
                assertEquals(phrase, 1, alignment.positionAt(offset / text.length.toFloat()))
            }
        }
    }

    @Test
    fun `unspoken tails close with their sentence only between adjacent Arabic anchors`() {
        for (stop in listOf(".", "?", "!", ";")) {
            val text = "stays from then on$stop Next"
            val alignment = EnglishWordAlignment.align(text, listOf("stays", "next"))!!
            assertEquals(stop, 1, alignment.positionAt(text.indexOf(" on") / text.length.toFloat()))
            assertEquals(stop, 2, alignment.positionAt(text.indexOf("Next") / text.length.toFloat()))
        }
        val separate = EnglishWordAlignment.align("ever after. Next", listOf("ever", "after", "next"))!!
        assertEquals(2, separate.positionAt(8f / 16))
        val unknown = EnglishWordAlignment.align("ever after. Next", listOf("ever", "unmatched", "next"))!!
        assertEquals(2, unknown.positionAt(8f / 16))
    }

    @Test
    fun `a sentence tail retains its earlier reflexive meaning`() {
        val text = "he took his brother to himself. Next"
        val alignment = EnglishWordAlignment.align(text, listOf("he took himself", "his brother", "next"))!!
        assertEquals(1, alignment.positionAt(text.indexOf("himself") / text.length.toFloat()))
    }

    @Test
    fun `a separately spoken after retains its owner`() {
        val text = "ever after"
        val glosses = listOf("ever", "after")
        val alignment = EnglishWordAlignment.align(text, glosses)!!
        assertEquals(1, alignment.positionAt(0.1f))
        assertEquals(2, alignment.positionAt(0.9f))
    }

    @Test
    fun `clear evidence and evidences keep their bayyinat owners across the Quran`() {
        verifyCases("english-clear-evidence-owners.tsv", 32, checkTaps = true)
    }

    @Test
    fun `a literal clear adjective and evidence noun retain separate owners`() {
        val text = "clear evidence"
        for (glosses in listOf(listOf("clear", "evidence"), listOf("evidence", "clear"))) {
            assertEquals(glosses.indexOf("clear"), EnglishWordAlignment.ownerAt(text, glosses, 2))
            assertEquals(glosses.indexOf("evidence"), EnglishWordAlignment.ownerAt(text, glosses, 8))
        }
    }

    @Test
    fun `expanded gendered opening starts on the first Arabic word`() {
        verifyCases("english-quran-ai-opening-owners.tsv", 3, checkTaps = true)
    }

    @Test
    fun `literal gender and offender heads keep their own meanings`() {
        for ((gender, names) in listOf("woman" to listOf("fornicatress", "adulteress"), "man" to listOf("fornicator", "adulterer"))) {
            for (name in names) {
                val text = "$gender and $name"
                assertEquals(1, EnglishWordAlignment.ownerAt(text, listOf(name, "and a $gender"), 2))
                assertEquals(0, EnglishWordAlignment.ownerAt(text, listOf(name, "and a $gender"), text.length - 2))
                assertEquals(0, EnglishWordAlignment.ownerAt("$gender first", listOf(name, "first"), 2))
                assertNull(EnglishWordAlignment.ownerAt("$name and $gender", listOf(name, "a person"), name.length + 6))
            }
        }
    }

    @Test
    fun `a female qualifier needs its own source head and preserves the male head`() {
        for (female in listOf("fornicatress", "adulteress")) for (male in listOf("fornicator", "adulterer")) {
            val text = "[female] $male and $male"
            assertEquals(0, EnglishWordAlignment.ownerAt(text, listOf(female, male), 11))
            assertEquals(1, EnglishWordAlignment.ownerAt(text, listOf(female, male), text.length - 2))
            assertEquals(0, EnglishWordAlignment.ownerAt("[female] $male", listOf(male), 11))
        }
    }

    @Test
    fun `quran ai reviewed owners distinguish synonyms shared heads and false reference cues`() {
        verifyCases("english-quran-ai-owners.tsv", 74, checkTaps = true)
    }

    @Test
    fun `word cards reject clamped lemma positions and keep home distinct from house`() {
        verifyCases("english-quran-ai-word-card-owners.tsv", 3, checkTaps = true)
    }

    @Test
    fun `modern quran ai translation spelling keeps named heads in place`() {
        verifyCases("english-quran-ai-modern-owners.tsv", 2, checkTaps = true)
    }

    @Test
    fun `decomposed Latin accents stay inside the printed word`() {
        val text = "Alla\u0304h"
        val alignment = EnglishWordAlignment.align(text, listOf("Allah"), listOf("ٱللَّه"))!!
        assertEquals(1, alignment.spans.single().position)
        assertEquals(0, EnglishWordAlignment.ownerAt(text, listOf("Allah"), 3, listOf("ٱللَّه")))
    }

    @Test
    fun `reversed neighbouring heads require reciprocal lexical evidence`() {
        val text = "soundness of the way"
        val lemmas = listOf("سَوَآء", "سَبِيل")
        val glosses = listOf("the way", "the right")
        assertEquals(0, EnglishWordAlignment.ownerAt(text, glosses, 3, lemmas))
        assertEquals(1, EnglishWordAlignment.ownerAt(text, glosses, text.length - 2, lemmas))
        assertNull(EnglishWordAlignment.ownerAt(text, listOf("song", "the right"), text.length - 2, lemmas))
    }

    @Test
    fun `a grammatical negative card needs complete Arabic evidence for its missing verb`() {
        val text = "we do not hear"
        val glosses = listOf("we do not", "and")
        assertEquals(0, EnglishWordAlignment.ownerAt(text, glosses, text.length - 2, listOf("سَمِعَ", "و"), listOf("يَسْمَعُوا", "و")))
        assertNull(EnglishWordAlignment.ownerAt(text, glosses, text.length - 2, listOf("سَمِعَ", "و")))
        assertNull(EnglishWordAlignment.ownerAt(text, glosses, text.length - 2, listOf("سَمِعَ")))
        assertNull(EnglishWordAlignment.ownerAt(text, glosses, text.length - 2))
    }

    @Test
    fun `different Arabic burdens retain the sentence guard with morphology present`() {
        verifyCases("english-distinct-owners.tsv", 1)
    }

    @Test
    fun `a book lemma is distinct from a writing lemma with the same bare letters`() {
        val text = "Book written"
        val glosses = listOf("Book written", "Book written")
        val alignment = EnglishWordAlignment.align(text, glosses, listOf("كَتَبَ", "كِتَٰب"))!!
        assertEquals(2, alignment.positionAt(0.1f))
        assertEquals(1, alignment.positionAt(0.9f))
        assertNull(EnglishWordAlignment.ownerAt(text, glosses, 2, listOf("كَتَبَ", "كَتَبَ")))
    }

    @Test
    fun `earth sky truth and mosque spellings keep their spoken owners`() {
        verifyCases("english-lexical-owners.tsv", 31)
    }

    @Test
    fun `same Arabic heads survive clause reordering and supplementary mentions of Allah`() {
        verifyCases("english-repeated-owners.tsv", 8)
    }

    @Test
    fun `reordered names across clauses and a title beside knows keep their exact owners`() {
        verifyCases("english-identity-owners.tsv", 14)
    }

    @Test
    fun `a divine title cannot absorb an ordinary knowing head`() {
        val text = "Knowing and those knowing"
        val alignment = EnglishWordAlignment.align(text, listOf("All-Knower", "and", "those knowing"))!!
        assertEquals(3, alignment.positionAt(0.9f))
    }

    @Test
    fun `Arabic lemmas resolve every shared name phrase without guessing its order`() {
        verifyCases("english-shared-owners.tsv", 21)
    }

    @Test
    fun `a shared phrase resolves either Arabic order and keeps real repeated verbs`() {
        val text = "Moses said"
        val glosses = listOf("Musa said", "Musa said")
        for (lemmas in listOf(listOf("قَالَ", "مُوسَىٰ"), listOf("مُوسَىٰ", "قَالَ"))) {
            val alignment = EnglishWordAlignment.align(text, glosses, lemmas)!!
            val name = lemmas.indexOf("مُوسَىٰ")
            assertEquals(name + 1, alignment.positionAt(0.1f))
            assertEquals(2 - name, alignment.positionAt(0.9f))
            assertEquals(name, EnglishWordAlignment.ownerAt(text, glosses, 2, lemmas))
            assertEquals(1 - name, EnglishWordAlignment.ownerAt(text, glosses, 8, lemmas))
            assertEquals(1, alignment.washSpans.first().position)
            assertEquals(2 - name, alignment.washSpans.last().position)
        }
        val repeated = EnglishWordAlignment.align("you do good, you do good",
            listOf("you do good", "you do good"), listOf("أَحْسَنَ", "أَحْسَنَ"))!!
        assertEquals(1, repeated.positionAt(0.3f))
        assertEquals(2, repeated.positionAt(0.8f))
    }

    @Test
    fun `reader cache uses the Arabic evidence in both English text sources`() {
        val content = SurahContent(Surah(10, "", "", "", "", 1), listOf(
            Ayah(10, 1, "قَالَ مُوسَىٰ", "Moses said", words = listOf(
                Word(1, "قَالَ", "Musa said", "", lemma = "قَالَ"),
                Word(2, "مُوسَىٰ", "Musa said", "", lemma = "مُوسَىٰ"),
            )),
        ))
        for (wordByWord in listOf(false, true)) {
            val alignment = EnglishVerseAlignments(content, wordByWord).alignment(1)!!
            assertEquals(2, alignment.positionAt(0.1f))
            assertEquals(1, alignment.positionAt(0.9f))
        }
    }

    @Test
    fun `partial or unknown Arabic evidence cannot invent a shared head owner`() {
        for (lemmas in listOf(emptyList(), listOf("مُوسَىٰ"), listOf("", ""))) {
            assertNull(EnglishWordAlignment.ownerAt("Moses said", listOf("Musa said", "Musa said"), 2, lemmas))
        }
    }

    @Test
    fun `shared source phrases stay visible in the audit without inventing exact tap owners`() {
        verifyCases("english-shared-owners.tsv", 21, exact = false, withLemmas = false)
        for (row in javaClass.getResourceAsStream("/english-shared-owners.tsv")!!.bufferedReader().use { it.readLines() }) {
            val (_, text, glosses, targets) = row.split('\t')
            for (target in targets.split('|')) {
                val (word, occurrence, _) = Regex("(.+?)(?:#(\\d+))?=(\\d+)").matchEntire(target)!!.destructured
                val at = Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE).findAll(text)
                    .elementAt((occurrence.toIntOrNull() ?: 1) - 1).range.first
                assertNull(target, EnglishWordAlignment.ownerAt(text, glosses.split('|'), at + word.length / 2))
            }
        }
    }

    @Test
    fun `prayer performance and establishment keep their separately spoken heads`() {
        verifyCases("english-prayer-alignment.tsv", 2)
    }

    @Test
    fun `a performance outside the prayer phrase does not mean establishing`() {
        assertNull(EnglishWordAlignment.ownerAt(
            "performance of work and prayer", listOf("establishing", "of work", "and", "prayer"), 3,
        ))
    }

    @Test
    fun `prayer performance recognizes action glosses and preserves a literal performance`() {
        val text = "performance of prayer"
        for (action in listOf("establishing", "establishment", "performance")) {
            val glosses = listOf(action, "the prayer")
            assertEquals(action, 0, EnglishWordAlignment.ownerAt(text, glosses, 3))
            val alignment = EnglishWordAlignment.align(text, glosses)!!
            assertEquals(action, 1, alignment.positionAt(0.1f))
            assertEquals(action, 2, alignment.positionAt(0.9f))
        }
    }

    @Test
    fun `commerce and sale keep distinct owners when reordered`() {
        val alignment = EnglishWordAlignment.align("commerce and sale", listOf("sale", "trade"))!!
        assertEquals(2, alignment.positionAt(0.1f))
        assertEquals(1, alignment.positionAt(0.9f))
    }

    @Test
    fun `reported verses keep the Arabic owners of their clear content heads`() {
        verifyCases("english-semantic-heads.tsv", 2)
    }

    @Test
    fun `wrap does not mean draw outside the headcover phrase`() {
        assertNull(EnglishWordAlignment.ownerAt("wrap a gift", listOf("draw", "a gift"), 2))
    }

    @Test
    fun `the All-Aware phrase does not merge ordinary awareness with acquaintance`() {
        val text = "The Acquainted and those aware"
        val alignment = EnglishWordAlignment.align(text, listOf("the All-Aware", "and", "those", "aware"))!!
        assertEquals(1, alignment.positionAt(0.2f))
        assertEquals(4, alignment.positionAt(0.9f))
    }

    @Test
    fun `Acquainted belongs to Khabeer in every published occurrence`() {
        verifyCases("english-semantic-alignment.tsv", 44)
    }

    @Test
    fun `corpus audit cases reach the spoken verb phrase title and name`() {
        verifyCases("english-semantic-patterns.tsv", 56, exact = false)
    }

    @Test
    fun `Rahman and Rahim stay distinct when both occur in reordered prose`() {
        val text = "Especially Merciful and Entirely Merciful"
        val alignment = EnglishWordAlignment.align(text, listOf("Most Gracious", "Most Merciful"))!!
        assertEquals(2, alignment.positionAt(0.2f))
        assertEquals(1, alignment.positionAt(0.9f))
        assertEquals(1, alignment.washSpans.single().position)
    }

    @Test
    fun `an explicit deed keeps its owner before the adjective evil`() {
        val text = "evil deeds"
        val alignment = EnglishWordAlignment.align(text, listOf("deeds", "evil"))!!
        assertEquals(1, alignment.positionAt(0.8f))
        assertEquals(2, alignment.positionAt(0.2f))
        assertEquals(1, alignment.washSpans.single().position)
    }

    @Test
    fun `an incomplete evil gloss cannot acquire the following noun`() {
        val alignment = EnglishWordAlignment.align("evil deeds", listOf("evil thoughts", "deeds"))!!
        assertEquals(2, alignment.positionAt(0.8f))
    }

    @Test
    fun `phrases cannot combine heads explicitly spoken by different Arabic words`() {
        for ((text, glosses, heads) in listOf(
            Triple("give life", listOf("give", "life"), listOf("give", "life")),
            Triple("acceptor of repentance", listOf("acceptor", "of repentance"), listOf("acceptor", "repentance")),
            Triple("come to know", listOf("come", "to know"), listOf("come", "know")),
            Triple("seek to hasten", listOf("seek", "to hasten"), listOf("seek", "hasten")),
            Triple("seems suitable", listOf("seems", "suitable"), listOf("seems", "suitable")),
            Triple("my way", listOf("my", "way"), listOf("my", "way")),
        )) {
            val alignment = EnglishWordAlignment.align(text, glosses)!!
            heads.forEachIndexed { index, head ->
                assertEquals(text, index + 1, alignment.positionAt((text.indexOf(head) + 1f) / text.length))
            }
        }
    }

    @Test
    fun `literal brought remains distinct from come when both are spoken`() {
        val text = "came and brought"
        val alignment = EnglishWordAlignment.align(text, listOf("brought", "came"))!!
        assertEquals(2, alignment.positionAt(0.1f))
        assertEquals(1, alignment.positionAt(0.8f))
    }

    private fun verifyCases(resource: String, verses: Int, exact: Boolean = true, withLemmas: Boolean = true, checkTaps: Boolean = false) {
        val cases = javaClass.getResourceAsStream("/$resource")!!
            .bufferedReader().use { it.readLines() }
        assertEquals(verses, cases.size)
        val failures = mutableListOf<String>()
        for (line in cases) {
            val columns = line.split('\t')
            val (verse, text, glosses, targets) = columns
            val lemmas = if (withLemmas) columns.getOrNull(4)?.split('|').orEmpty() else emptyList()
            val arabic = columns.getOrNull(5)?.split('|').orEmpty()
            val alignment = EnglishWordAlignment.align(text, glosses.split('|'), lemmas, arabic)!!
            for (target in targets.split('|')) {
                val match = Regex("(.+?)(?:#(\\d+))?=(\\d+)").matchEntire(target)!!
                val (word, occurrence, position) = match.destructured
                val at = Regex("(?U)\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE)
                    .findAll(text).elementAt((occurrence.toIntOrNull() ?: 1) - 1).range.first
                val actual = alignment.positionAt((at + word.length / 2f) / text.length)
                if (exact && actual != position.toInt()) failures += "$verse $target was $actual"
                val end = (at + word.length - 0.5f) / text.length
                val washed = alignment.washSpans.single { end >= it.from && end < it.to }.position
                if (washed > position.toInt()) failures += "$verse $target wash was $washed"
                if (checkTaps) {
                    val tap = EnglishWordAlignment.ownerAt(text, glosses.split('|'), at + word.length / 2, lemmas, arabic)
                    if (tap != null && tap + 1 != position.toInt()) failures += "$verse $target tap was ${tap + 1}"
                }
            }
        }
        assertEquals(emptyList<String>(), failures)
    }
}
