package com.beautifulquran.domain

import com.beautifulquran.data.model.Ayah
import com.beautifulquran.data.model.Surah
import com.beautifulquran.data.model.SurahContent
import com.beautifulquran.data.model.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The alignment is what makes the English leaf's ink land on the words the
 * listener is hearing. See `EnglishWordAlignment`.
 */
class EnglishWordAlignmentTest {

    /** 2:2, as the shipped DB has it. */
    private val baqarah2 =
        "This is the Book about which there is no doubt, a guidance for those conscious of Allah"
    private val baqarah2Glosses = listOf(
        "That", "(is) the book", "no", "doubt", "in it", "a Guidance", "for the God-conscious",
    )

    private fun shares(text: String, glosses: List<String>): List<String> {
        val ends = EnglishWordAlignment.wordEnds(text, glosses)
            ?: error("expected an alignment")
        var from = 0
        return ends.map { end ->
            val to = (end * text.length).toInt().coerceIn(from, text.length)
            text.substring(from, to).also { from = to }
        }
    }

    @Test
    fun `each Arabic word takes the English it is about`() {
        val shares = shares(baqarah2, baqarah2Glosses)
        assertEquals("This is", shares[0])
        assertEquals(" the Book", shares[1])
        // لَا رَيْبَ فِيهِ is "no doubt in it"; the translation says "about which
        // there is no doubt", so the run-up rides on the word that anchors it.
        assertEquals(" about which there is no", shares[2])
        assertEquals(" doubt", shares[3])
        assertEquals(" guidance", shares[5])
        assertEquals(" for those conscious of Allah", shares[6])
    }

    @Test
    fun `a reordered verb cannot wash over servants of Allah before they are said`() {
        // 76:6: Arabic puts "will drink" before the subject; the translation
        // puts it after. The gloss also calls the servants "slaves".
        val text = "A spring of which the [righteous] servants of Allah will drink; " +
            "they will make it gush forth in force [and abundance]"
        val glosses = listOf(
            "A spring", "will drink", "from it", "(the) slaves", "(of) Allah",
            "causing it to gush forth", "abundantly",
        )
        val ends = EnglishWordAlignment.wordEnds(text, glosses)!!
        val parts = shares(text, glosses)
        assertTrue("early verb took ${parts[1]}", ends[1] * text.length <= text.indexOf("servants"))
        assertTrue("servants took ${parts[3]}", parts[3].contains("servants"))
        assertTrue("Allah took ${parts[4]}", parts[4].contains("Allah"))
        val subject = (text.indexOf("servants") + 3f) / text.length
        assertEquals(4, englishSeekWordPosition(subject, glosses.size, ends))
        val at = (text.indexOf("Allah") + 2f) / text.length
        assertEquals(5, englishSeekWordPosition(at, glosses.size, ends))
    }

    @Test
    fun `singular and plural servant glosses keep a reordered subject anchored`() {
        for (gloss in listOf("slave", "slaves", "servant", "servants")) {
            val text = "Our servants will drink from a spring"
            val glosses = listOf("will drink", "Our $gloss", "from a spring")
            val ends = EnglishWordAlignment.wordEnds(text, glosses)!!
            val subject = (text.indexOf("servants") + 3f) / text.length
            assertEquals(gloss, 2, englishSeekWordPosition(subject, glosses.size, ends))
        }
    }

    @Test
    fun `vision follows the word for gaze rather than the following guard`() {
        val text = "Tell the believing women to reduce some of their vision and guard their private parts"
        val glosses = listOf(
            "And say", "to the believing women", "they should lower", "of",
            "their gaze", "and they should guard", "their chastity",
        )
        val ends = EnglishWordAlignment.wordEnds(text, glosses)!!
        val before = text.indexOf("vision")
        val after = before + "vision".length
        assertTrue("vision begins too early", ends[3] * text.length <= before)
        assertTrue("vision finishes too late", ends[4] * text.length >= after - 0.01f)
        assertEquals(5, englishSeekWordPosition((before + 3f) / text.length, ends.size, ends))
    }

    @Test
    fun `reordered possessives cannot give an earlier sons to a later brother`() {
        val text = "their sons, their husbands' sons, their brothers, their brothers' sons, their sisters' sons"
        val glosses = listOf(
            "their sons", "or", "sons", "of their husbands", "or", "their brothers",
            "or", "sons", "of their brothers", "or", "sons", "of their sisters",
        )
        val ends = EnglishWordAlignment.wordEnds(text, glosses)!!
        val brothers = text.indexOf("brothers")
        assertEquals(6, englishSeekWordPosition((brothers + 3f) / text.length, ends.size, ends))
        assertTrue("brothers begins before its Arabic word", ends[4] * text.length <= brothers)
        assertTrue("brothers stays dark after its Arabic word", ends[5] * text.length >= brothers + 8 - 0.01f)
    }

    @Test
    fun `reordered words keep their spoken owners across gaps in English`() {
        for ((text, glosses, expected) in listOf(
            Triple("Allah has set a seal", listOf("has set a seal", "Allah"), listOf("seal" to 1, "Allah" to 2)),
            Triple("a painful punishment", listOf("a punishment", "painful"), listOf("punishment" to 1, "painful" to 2)),
            Triple("Our servants will drink", listOf("will drink", "Our slaves"), listOf("drink" to 1, "servants" to 2)),
            Triple("The Approaching Day has approached", listOf("Has approached", "the Approaching Day"),
                listOf("approached" to 1, "Approaching" to 2, "Day" to 2)),
        )) {
            val alignment = EnglishWordAlignment.align(text, glosses)!!
            for ((word, position) in expected) {
                val at = (text.indexOf(word) + word.length / 2f) / text.length
                assertEquals("$text: $word", position, alignment.positionAt(at))
                val spoken = alignment.spans.filter { it.position == position }
                assertTrue("$word has no wash", spoken.any { at >= it.from && at < it.to })
            }
            assertEquals(0f, alignment.spans.first().from, 0f)
            assertEquals(1f, alignment.spans.last().to, 0f)
            assertTrue(alignment.spans.zipWithNext().all { (a, b) -> a.to == b.from })
        }
    }

    @Test
    fun `shared gaze gloss belongs to its head but real good repetitions stay separate`() {
        val gaze = EnglishWordAlignment.align("of their vision", listOf("their gaze", "their gaze"))!!
        assertEquals(2, gaze.positionAt(12f / 15))
        val repeated = EnglishWordAlignment.align("If you do good, you do good", listOf("If", "you do good", "you do good"))!!
        assertEquals(2, repeated.positionAt(11f / 26))
        assertEquals(3, repeated.positionAt(24f / 26))
    }

    @Test
    fun `repeated inflected forms do not spend each others occurrences`() {
        val text = "Believe as the people have believed, should we believe as the foolish have believed?"
        val alignment = EnglishWordAlignment.align(text, listOf(
            "Believe", "as", "believed", "the people", "Should we believe", "as", "believed", "the fools",
        ))!!
        val occurrences = Regex("believed").findAll(text).toList()
        assertEquals(3, alignment.positionAt((occurrences[0].range.first + 3f) / text.length))
        assertEquals(7, alignment.positionAt((occurrences[1].range.first + 3f) / text.length))
        for ((singular, plural) in listOf("thing" to "things", "morning" to "mornings", "degree" to "degrees")) {
            val line = "He names $plural today"
            val owned = EnglishWordAlignment.align(line, listOf("He", "names", singular, "today"))!!
            assertEquals(plural, 3, owned.positionAt((line.indexOf(plural) + 2f) / line.length))
        }
    }

    @Test
    fun `cached ownership uses the selected English source`() {
        val content = SurahContent(
            Surah(76, "", "", "", "", 1),
            listOf(Ayah(76, 6, "", "Our servants will drink", words = listOf(
                Word(1, "يشرب", "will drink", ""), Word(2, "عباد", "Our slaves", ""),
            ))),
        )
        val published = EnglishVerseAlignments(content).alignment(6)!!
        val gloss = EnglishVerseAlignments(content, wordByWord = true).alignment(6)!!
        assertEquals(2, published.positionAt(0.05f))
        assertEquals(1, gloss.positionAt(0.05f))
    }

    @Test
    fun `An Nur reported words and compounds have their own spoken spans`() {
        val line = javaClass.getResourceAsStream("/english-alignment.tsv")!!.bufferedReader()
            .use { it.readLines().first { line -> line.startsWith("24:31\t") } }
        val (_, text, glossText) = line.split('\t')
        val alignment = EnglishWordAlignment.align(text, glossText.split('|'))!!
        for ((word, positions) in mapOf(
            "vision" to listOf(5), "private parts" to listOf(7),
            "sons" to listOf(30, 32, 37, 40), "brothers" to listOf(35, 38),
            "sisters" to listOf(41), "chests" to listOf(18),
        )) {
            val matches = Regex("\\b${Regex.escape(word)}\\b").findAll(text).toList()
            positions.forEachIndexed { index, expected ->
                val at = (matches[index].range.first + word.length / 2f) / text.length
                assertEquals("24:31 $word #${index + 1}", expected, alignment.positionAt(at))
            }
        }
    }

    @Test
    fun `the shares tile the sentence, in order, to the end`() {
        val ends = EnglishWordAlignment.wordEnds(baqarah2, baqarah2Glosses)!!
        assertEquals(baqarah2Glosses.size, ends.size)
        var previous = 0f
        for (end in ends) {
            assertTrue("shares must not run backwards: $end after $previous", end >= previous)
            previous = end
        }
        assertEquals(1f, ends.last(), 1e-6f)
    }

    @Test
    fun `a boundary always lands between English words`() {
        val ends = EnglishWordAlignment.wordEnds(baqarah2, baqarah2Glosses)!!
        for (end in ends) {
            val at = (end * baqarah2.length).toInt()
            if (at == 0 || at == baqarah2.length) continue
            val before = baqarah2[at - 1]
            val after = baqarah2[at]
            assertTrue(
                "boundary at $at splits '${baqarah2.substring(at - 3, at + 3)}'",
                !before.isLetter() || !after.isLetter(),
            )
        }
    }

    @Test
    fun `a verse with nothing in common still divides the sentence evenly`() {
        // No lexical anchor anywhere: the alignment degrades to the proportion
        // the leaf used before it, which is the promise the fallback makes.
        val ends = EnglishWordAlignment.wordEnds(
            "One two three four",
            listOf("zzz", "yyy", "xxx", "www"),
        )!!
        assertEquals(4, ends.size)
        assertEquals(1f, ends.last(), 1e-6f)
        var previous = 0f
        for (end in ends) {
            assertTrue(end >= previous)
            previous = end
        }
    }

    @Test
    fun `an inflected match still anchors`() {
        // "revealed" / "reveals", "heaven" / "heavens": the two texts differ by
        // an ending, which must still match without the old four-letter shortcut.
        val text = "He reveals the heavens"
        val ends = EnglishWordAlignment.wordEnds(text, listOf("He", "revealed", "the heaven"))!!
        assertEquals("He reveals", text.substring(0, (ends[1] * text.length).toInt()))
    }

    @Test
    fun `a reordered first word can wait without revealing its first letters`() {
        val text = "Allah has set a seal"
        val ends = EnglishWordAlignment.wordEnds(text, listOf("has set a seal", "Allah"))!!
        assertEquals(0f, ends[0], 0f)
        assertEquals(2, englishSeekWordPosition(2f / text.length, ends.size, ends))
    }

    @Test
    fun `similar openings cannot trade hearing for hearts or messages for messengers`() {
        for ((text, glosses, target) in listOf(
            Triple("Their hearing in their hearts is sound", listOf("their hearts", "in it", "their hearing", "sound"), "hearing"),
            Triple("The messages came to messengers", listOf("messengers", "to them", "messages"), "messages"),
        )) {
            val ends = EnglishWordAlignment.wordEnds(text, glosses)!!
            val at = (text.indexOf(target) + 2f) / text.length
            assertEquals(text, 3, englishSeekWordPosition(at, ends.size, ends))
        }
    }

    @Test
    fun `apostrophes and possessives keep the same lexical anchors`() {
        val text = "They read the Qur’an with Allah’s servants"
        val ends = EnglishWordAlignment.wordEnds(text, listOf("They", "read", "the Quran", "with Allah", "slaves"))!!
        for ((target, position) in listOf("Qur’an" to 3, "Allah’s" to 4, "servants" to 5)) {
            assertEquals(target, position, englishSeekWordPosition((text.indexOf(target) + 2f) / text.length, ends.size, ends))
        }
    }

    @Test
    fun `corpus regressions hold unheard content without stealing a different occurrence`() {
        // Shipped Saheeh International verses and the corresponding QF glosses.
        // '=' names a spoken owner; '<=' protects an earlier, different meaning
        // or translator aside from being dragged to a late literal match.
        val cases = javaClass.getResourceAsStream("/english-alignment.tsv")!!
            .bufferedReader().use { it.readLines() }
        for (line in cases) {
            val (verse, text, glossText, targets) = line.split('\t')
            val glosses = glossText.split('|')
            val alignment = EnglishWordAlignment.align(text, glosses)!!
            val ends = alignment.wordEnds
            val boundaries = ends.map { (it * text.length).roundToInt() }
            assertEquals(verse, glosses.size, ends.size)
            assertEquals(verse, text.length, boundaries.last())
            assertTrue(verse, boundaries.zipWithNext().all { (a, b) -> a <= b })
            for (at in boundaries) {
                assertTrue("$verse splits a word at $at", at == 0 || at == text.length ||
                    !text[at - 1].isLetter() || !text[at].isLetter())
            }
            for (target in targets.split('|')) {
                val match = Regex("(.+?)(?:#(\\d+))?(<=|=)(\\d+)").matchEntire(target)!!
                val (word, occurrence, operator, expected) = match.destructured
                val token = Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE)
                    .findAll(text).elementAt((occurrence.toIntOrNull() ?: 1) - 1)
                val at = (token.range.first + word.length / 2f) / text.length
                val actual = englishSeekWordPosition(at, ends.size, ends)
                if (operator == "=") assertEquals("$verse $word", expected.toInt(), actual)
                else {
                    assertTrue("$verse $word moved to $actual", actual <= expected.toInt())
                    val owned = alignment.positionAt(at)
                    assertTrue("$verse $word ownership moved to $owned", owned <= expected.toInt())
                }
            }
        }
    }

    @Test
    fun `nothing to align on is refused rather than guessed`() {
        assertNull(EnglishWordAlignment.wordEnds("", listOf("a")))
        assertNull(EnglishWordAlignment.wordEnds("text", emptyList()))
        assertNull(EnglishWordAlignment.wordEnds("...", listOf("a")))
        assertNull(EnglishWordAlignment.wordEnds("text", listOf("(", ")")))
    }

    @Test
    fun `a tap lands on the word whose English it pointed at`() {
        val ends = EnglishWordAlignment.wordEnds(baqarah2, baqarah2Glosses)!!
        val words = baqarah2Glosses.size
        fun at(text: String): Int {
            val index = baqarah2.indexOf(text) + text.length / 2
            return englishSeekWordPosition(index.toFloat() / baqarah2.length, words, ends)
        }
        assertEquals(2, at("the Book"))
        assertEquals(4, at("doubt"))
        assertEquals(6, at("guidance"))
        assertEquals(7, at("conscious"))
    }

    @Test
    fun `a hold names the word its gloss names, not the wash's neighbour`() {
        val text = "In their hearts is disease, so Allah has increased their disease; " +
            "and for them is a painful punishment because they [habitually] used to lie"
        val glosses = listOf(
            "In", "their hearts", "(is) a disease", "so has increased them", "Allah",
            "(in) disease", "and for them", "(is) a punishment", "painful", "because",
            "they used to", "[they] lie",
        )
        fun held(word: String, nth: Int = 0): Int? {
            var at = -1
            repeat(nth + 1) { at = text.indexOf(word, at + 1) }
            return EnglishWordAlignment.ownerAt(text, glosses, at + word.length / 2)
        }
        assertEquals(8, held("painful"))
        assertEquals(7, held("punishment"))
        assertEquals(4, held("Allah"))
        // Said twice, glossed twice: each occurrence keeps its own word.
        assertEquals(2, held("disease"))
        assertEquals(5, held("disease", 1))
        assertNull(held("them"))
        assertNull(held("habitually"))
    }
}
