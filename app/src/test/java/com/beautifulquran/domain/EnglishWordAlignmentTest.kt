package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        // an ending, and a four-letter opening is enough to tie them.
        val text = "He reveals the heavens"
        val ends = EnglishWordAlignment.wordEnds(text, listOf("He", "revealed", "the heaven"))!!
        assertEquals("He reveals", text.substring(0, (ends[1] * text.length).toInt()))
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
}
