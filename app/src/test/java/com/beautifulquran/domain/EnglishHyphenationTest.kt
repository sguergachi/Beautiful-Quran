package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishHyphenationTest {
    private val wj = EnglishHyphenation.WORD_JOINER
    private val shy = EnglishHyphenation.SOFT_HYPHEN
    private val cut = EnglishHyphenation.KEPT_CUT

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
    fun `joins vetoed cuts and writes the kept ones down`() {
        val set = EnglishHyphenation.setProse("descends transgress indeed repelled")
        assertEquals("de${wj}scend${wj}s trans${cut}gress in${wj}deed re${wj}pelled", set)
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
    fun `kept cuts are written down, so a vetoed word still breaks well`() {
        // right-eous-ness: every fragment stands, so every cut is written down.
        assertEquals("right${cut}eous${cut}ness", EnglishHyphenation.setProse("righteousness"))
        assertEquals("trans${cut}gress", EnglishHyphenation.setProse("transgress"))
        // descends has no cut worth keeping: joined at both, open nowhere.
        assertEquals("de${wj}scend${wj}s", EnglishHyphenation.setProse("descends"))
        // fulfillment keeps two cuts and refuses one. The platform hyphenator
        // breaks no word with a joiner in it, so without these the refusal
        // took the good cuts down with it; the leaf's rag takes them instead.
        val set = EnglishHyphenation.setProse("fulfillment")
        assertEquals(2, set.count { it == shy })
        assertEquals("two kept and one refused", 3, set.count { it == wj })
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
            // The marks add nothing visible.
            assertEquals(word, set.filter { it != wj && it != shy })
            val cuts = EnglishHyphenation.breaks(word)
            val kept = EnglishHyphenation.vetted(cuts, word.length).toSet()
            // Each refused cut is joined and each kept one is a soft hyphen:
            // walk the set word, reading each mark at its source boundary.
            val vetoed = HashSet<Int>()
            val written = HashSet<Int>()
            var source = 0
            var afterShy = false
            set.forEach { c ->
                when (c) {
                    // A joiner straight after a soft hyphen closes a kept cut.
                    wj -> if (!afterShy) vetoed += source
                    shy -> written += source
                    else -> source++
                }
                afterShy = c == shy
            }
            assertEquals(word to (cuts.toSet() - kept), word to vetoed)
            assertEquals(word to kept, word to written)
        }
    }
}
