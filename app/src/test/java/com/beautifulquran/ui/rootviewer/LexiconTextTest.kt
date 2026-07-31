package com.beautifulquran.ui.rootviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LexiconTextTest {

    @Test
    fun `splits Lane's mixed prose into script runs`() {
        val runs = lexiconRuns("inf. n. كِتَابٌ and كِتَابَةٌ (S, K)")

        assertEquals(
            listOf(false, true, false, true, false),
            runs.map { it.isArabic },
        )
        assertEquals("inf. n. ", runs[0].text)
        assertTrue(runs[1].text.startsWith("كِتَابٌ"))
        assertTrue(runs[2].text.contains("and"))
        assertTrue(runs.last().isCitation)
        assertEquals("(S, K)", runs.last().text)
    }

    @Test
    fun `neutral punctuation stays with the run it follows`() {
        val runs = lexiconRuns("wrote it: (كَتَبَ) then")

        assertEquals(3, runs.size)
        assertEquals("wrote it: (", runs[0].text)
        assertTrue(runs[1].isArabic)
        assertTrue(runs[1].text.endsWith(") "))
        assertEquals("then", runs[2].text)
    }

    @Test
    fun `single-script text stays one run`() {
        assertEquals(1, lexiconRuns("He wrote it.").size)
        assertEquals(false, lexiconRuns("He wrote it.").single().isArabic)

        val arabic = lexiconRuns("كَتَبَ كِتَابًا")
        assertEquals(1, arabic.size)
        assertTrue(arabic.single().isArabic)
    }

    @Test
    fun `rejoining the runs reproduces the entry exactly`() {
        val entry = "Form 1. كَتَبَهُ , aor. كَتُبَ , inf. n. كَتْبٌ ; (Msb;) ↓ اكتتبهُ (K)"

        assertEquals(entry, lexiconRuns(entry).joinToString("") { it.text })
    }

    @Test
    fun `empty text has no runs`() {
        assertEquals(emptyList<LexiconRun>(), lexiconRuns(""))
    }

    @Test
    fun `source marks are citations but English asides are not`() {
        assertTrue(isLaneCitation("S, K"))
        assertTrue(isLaneCitation("Msb,"))
        assertTrue(isLaneCitation("Ksh and Bd in ii. 1:"))
        assertTrue(isLaneCitation("tropical:"))
        assertFalse(isLaneCitation("a thing"))
        assertFalse(isLaneCitation("see رِيبَةٌ;"))
    }

    @Test
    fun `reflow puts Form on its own line and breaks morph from gloss`() {
        val dense =
            "Form 1. رَابَنِى, (T, S, M, &c.,) aor. يَرِيبُ, (M, Msb,) " +
                "inf. n. رَيْبٌ (T, M,) It (a thing) occasioned in me disquiet."

        val reflowed = lexiconReflow(dense)

        assertTrue(reflowed.startsWith("Form 1.\n"))
        assertTrue(reflowed.contains("\n\nIt (a thing) occasioned"))
        assertFalse(reflowed.contains("M,) It"))
    }

    @Test
    fun `blocks expose Form headings and spaced senses`() {
        val article =
            "Form 1. كَتَبَهُ, (S,) aor. كَتُبَ, (K,) He wrote it.\n• And he prescribed it."

        val blocks = lexiconBlocks(lexiconReflow(article))

        assertEquals(3, blocks.size)
        assertEquals("Form 1.", blocks[0].form)
        assertTrue(blocks[0].text.contains("كَتَبَهُ"))
        assertEquals(null, blocks[1].form)
        assertTrue(blocks[1].text.startsWith("He wrote it."))
        assertEquals(null, blocks[2].form)
        assertTrue(blocks[2].text.startsWith("And he prescribed"))
        assertFalse(blocks[2].text.startsWith("•"))
    }

    @Test
    fun `a short article is previewed whole`() {
        val short = "يَدٌ The arm, from the shoulder-joint to the fingers. (Msb.)"

        assertEquals(short, lexiconPreview(short))
    }

    @Test
    fun `preview cuts at one of Lane's own divisions`() {
        val article = buildString {
            append("Form 1. ").append("He wrote it. ".repeat(60))
            append("\n• And he prescribed it. ")
            append("More senses follow. ".repeat(60))
        }

        val preview = lexiconPreview(article)

        assertTrue(preview.length < article.length)
        assertTrue(preview.endsWith("…"))
        assertTrue(article.startsWith(preview.removeSuffix(" …")))
        // Cut on a sense break or sentence end — never mid-clause.
        assertTrue(preview.removeSuffix(" …").endsWith("it."))
    }

    @Test
    fun `preview never returns a stub when no break is near`() {
        val unbroken = "ا".repeat(4_000)

        assertEquals(LEXICON_PREVIEW_CHARS + 2, lexiconPreview(unbroken).length)
    }

    @Test
    fun `see cross-references and editorial marks are quiet`() {
        assertTrue(isLaneCitation("tropical:"))
        val runs = lexiconRuns("I marked it. (M, K.) See نَارَ.")
        assertTrue(runs.any { it.isCitation && it.text.startsWith("See") })
        assertTrue(runs.any { it.isCitation && it.text == "(M, K.)" })
    }

    @Test
    fun `reflow breaks gloss after an Arabic headword`() {
        val reflowed = lexiconReflow("Form 3. نَازَلَهُ He alighted with him.")
        assertTrue(reflowed.contains("\n\nHe alighted"))
    }
}
