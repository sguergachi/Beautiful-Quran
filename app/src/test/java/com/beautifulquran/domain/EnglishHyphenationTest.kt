package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishHyphenationTest {
    private val wj = EnglishHyphenation.WORD_JOINER

    @Test
    fun `breaks long words where the table says`() {
        assertEquals(listOf(5, 9), EnglishHyphenation.breaks("righteousness"))
        assertEquals(listOf(5), EnglishHyphenation.breaks("transgress"))
        assertEquals(listOf(4), EnglishHyphenation.breaks("guidance"))
        assertEquals(listOf(4), EnglishHyphenation.breaks("promise"))
        // Raw: the table also proposes 10, whose tail (`nt`) the book refuses.
        assertEquals(
            listOf(3, 7),
            EnglishHyphenation.vetted(EnglishHyphenation.breaks("fulfillment"), 12),
        )
    }

    @Test
    fun `lookup ignores case`() {
        assertEquals(listOf(5, 9), EnglishHyphenation.breaks("Righteousness"))
    }

    @Test
    fun `vetoes cuts with short fragments on either side`() {
        // de-scends, op-press, in-deed, re-pelled: refused outright.
        assertEquals(emptyList<Int>(), EnglishHyphenation.vetted(listOf(2), 8))
        // obe-di-ence: the middle fragment stands two letters.
        assertEquals(listOf(3), EnglishHyphenation.vetted(listOf(3, 5), 9))
        // Per-pet-u-al: the last fragment stands two letters.
        assertEquals(listOf(3, 6), EnglishHyphenation.vetted(listOf(3, 6, 7), 9))
        // right-eous-ness: every fragment stands.
        assertEquals(listOf(5, 9), EnglishHyphenation.vetted(listOf(5, 9), 14))
    }

    @Test
    fun `joins vetoed cuts and leaves the rest to the breaker`() {
        val set = EnglishHyphenation.setProse("descends transgress indeed repelled")
        assertEquals("de${wj}scend${wj}s transgress in${wj}deed re${wj}pelled", set)
    }

    @Test
    fun `setting prose is idempotent`() {
        val text = "Whoever repents and believes, and does righteousness."
        val once = EnglishHyphenation.setProse(text)
        assertEquals(once, EnglishHyphenation.setProse(once))
        // No plain space was harmed: pagination still cuts on ' '.
        assertTrue(once.contains(' '))
    }

    @Test
    fun `fully vetted words pass through untouched`() {
        // right-eous-ness: every fragment stands, so there is nothing to veto
        // and the breaker is free to take any of the cuts.
        assertEquals("righteousness", EnglishHyphenation.setProse("righteousness"))
        assertEquals("transgress", EnglishHyphenation.setProse("transgress"))
        // ...while descends is joined at both its bad cuts.
        assertEquals("de${wj}scend${wj}s", EnglishHyphenation.setProse("descends"))
    }

    @Test
    fun `vetoes hold across the vocabulary`() {
        // Every raw cut the book refuses carries a joiner; every cut it keeps
        // stays open — over a spread of the translation's long words.
        val words = listOf(
            "acceptable", "assembly", "ornament", "whoever", "muhammad",
            "punishment", "righteous", "message", "abandon", "believe",
            "except", "vision", "inquire", "rebellious", "account",
            "address", "heaven", "departed", "reminded", "difficulty",
            "creation", "obedience", "perpetual", "righteousness",
        )
        words.forEach { word ->
            val set = EnglishHyphenation.setProse(word)
            // The joiners add nothing visible.
            assertEquals(word, set.replace(EnglishHyphenation.WORD_JOINER.toString(), ""))
            val cuts = EnglishHyphenation.breaks(word)
            val kept = EnglishHyphenation.vetted(cuts, word.length).toSet()
            // Each refused cut is joined, and only refused cuts are: walk the
            // set word, reading each joiner as a veto at its source boundary.
            val vetoed = HashSet<Int>()
            var source = 0
            set.forEach { c ->
                if (c == EnglishHyphenation.WORD_JOINER) vetoed += source else source++
            }
            assertEquals(word to (cuts.toSet() - kept), word to vetoed)
        }
    }
}
