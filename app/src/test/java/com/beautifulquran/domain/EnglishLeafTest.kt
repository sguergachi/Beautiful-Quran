package com.beautifulquran.domain

import com.beautifulquran.data.model.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishLeafTest {

    /** The leaf `EnglishBook` would make of one Madinah page's verses. */
    private fun leafOf(
        catalog: MushafCatalog,
        page: Int,
        hideParentheticals: Boolean = false,
        translation: (Int, Int) -> String,
    ) = englishLeaf(
        page = page,
        runs = englishLeafVerseKeys(catalog.page(page)!!).map { (s, a) ->
            EnglishVerseRun(s, a, from = 0, to = Int.MAX_VALUE)
        },
        hideParentheticals = hideParentheticals,
        translation = translation,
    )

    @Test
    fun `a verse belongs to the leaf it begins on, whole`() {
        // 2:2 runs from page 3 onto page 4. A sentence cannot be cut at a page
        // break, so it is set entire on the leaf it starts on.
        val catalog = buildMushafCatalog(
            listOf(
                source(2, 2, 1, page = 3, line = 1),
                source(2, 2, 2, page = 4, line = 1),
                source(2, 3, 1, page = 4, line = 1),
            ),
        )
        assertEquals(listOf(2 to 2), englishLeafVerseKeys(catalog.page(3)!!))
        assertEquals(listOf(2 to 3), englishLeafVerseKeys(catalog.page(4)!!))

        assertEquals(
            listOf(2),
            leafOf(catalog, 3) { _, ayah -> "verse $ayah" }.verses.map { it.ayah },
        )
        assertEquals(
            listOf(3),
            leafOf(catalog, 4) { _, ayah -> "verse $ayah" }.verses.map { it.ayah },
        )
    }

    @Test
    fun `verses keep the page's own order`() {
        val catalog = buildMushafCatalog(
            listOf(
                source(2, 5, 1, page = 3, line = 1),
                source(2, 6, 1, page = 3, line = 1),
                source(2, 7, 1, page = 3, line = 2),
            ),
        )
        assertEquals(
            listOf(5, 6, 7),
            leafOf(catalog, 3) { _, ayah -> "verse $ayah" }.verses.map { it.ayah },
        )
    }

    @Test
    fun `verses run on as one paragraph, chapters do not`() {
        val catalog = buildMushafCatalog(
            listOf(
                source(1, 7, 1, page = 1, line = 14),
                source(2, 1, 1, page = 1, line = 15),
            ),
        )
        // Two chapters on one leaf. The opening is a block of its own, so the
        // panel can never fall inside the paragraph above it.
        val leaf = leafOf(catalog, 1) { _, ayah -> "verse $ayah" }
        assertEquals(
            listOf("Prose", "ChapterOpening", "Prose"),
            leaf.blocks.map { it::class.simpleName },
        )
        val opening = leaf.blocks[1] as EnglishLeafBlock.ChapterOpening
        assertEquals(2, opening.surahId)
        assertTrue(opening.basmalah)
    }

    @Test
    fun `Fatihah and Tawbah open without a basmalah preface`() {
        listOf(1, 9).forEach { surahId ->
            val catalog = buildMushafCatalog(listOf(source(surahId, 1, 1, page = 4, line = 1)))
            val opening = leafOf(catalog, 4) { _, ayah -> "verse $ayah" }
                .blocks
                .filterIsInstance<EnglishLeafBlock.ChapterOpening>()
                .single()
            assertFalse(opening.basmalah)
        }
    }

    @Test
    fun `a verse with no text of ours is dropped, not set as a hole`() {
        val catalog = buildMushafCatalog(
            listOf(source(2, 5, 1, page = 3, line = 1), source(2, 6, 1, page = 3, line = 1)),
        )
        val leaf = leafOf(catalog, 3) { _, ayah ->
            if (ayah == 5) "" else "verse $ayah"
        }
        assertEquals(listOf(6), leaf.verses.map { it.ayah })
    }

    @Test
    fun `the source's own line breaks are closed up - a page does not break a sentence`() {
        val catalog = buildMushafCatalog(listOf(source(2, 5, 1, page = 3, line = 1)))
        val leaf = leafOf(catalog, 3) { _, _ -> "  Alif\nLam   Mim  " }
        assertEquals("Alif Lam Mim", leaf.verses.single().text)
    }

    @Test
    fun `parentheticals come off when the reader has asked for it`() {
        val catalog = buildMushafCatalog(listOf(source(2, 5, 1, page = 3, line = 1)))
        val leaf = leafOf(catalog, 3, hideParentheticals = true) { _, _ ->
            "Alif [Lam] (Mim) Sad"
        }
        assertEquals("Alif Sad", leaf.verses.single().text)
    }

    @Test
    fun `prose mass counts each verse and the mark that closes it`() {
        val catalog = buildMushafCatalog(
            listOf(source(2, 5, 1, page = 3, line = 1), source(2, 6, 1, page = 3, line = 1)),
        )
        val leaf = leafOf(catalog, 3) { _, _ -> "abcd" }
        assertEquals(2 * (4 + ENGLISH_LEAF_MARK_CHARS), leaf.prose)
    }

    @Test
    fun `a leaf may carry verses from either side of a page break`() {
        // The English book paginates itself, so a run of verses handed to the
        // leaf is set as one page whether or not the Arabic broke inside it.
        val leaf = englishLeaf(
            page = 3,
            runs = listOf(2 to 5, 2 to 6, 2 to 7).map { (s, a) ->
                EnglishVerseRun(s, a, from = 0, to = Int.MAX_VALUE)
            },
        ) { _, ayah -> "verse $ayah" }
        assertEquals(3, leaf.page)
        assertEquals(listOf(5, 6, 7), leaf.verses.map { it.ayah })
        assertEquals(listOf("Prose"), leaf.blocks.map { it::class.simpleName })
    }

    @Test
    fun `a carried verse is set in two halves, and numbered where it ends`() {
        val text = "one two three four five six seven eight nine ten"
        val head = englishLeaf(3, listOf(EnglishVerseRun(2, 5, 0, 18))) { _, _ -> text }
        val tail = englishLeaf(4, listOf(EnglishVerseRun(2, 5, 18, text.length))) { _, _ -> text }
        // The break lands on a word, and neither half loses or repeats one.
        assertEquals("one two three four", head.verses.single().text)
        assertEquals("five six seven eight nine ten", tail.verses.single().text)
        assertEquals(text, head.verses.single().text + " " + tail.verses.single().text)
        // Only the half that finishes the sentence carries its number.
        assertFalse(head.verses.single().endsVerse)
        assertTrue(tail.verses.single().endsVerse)
    }

    @Test
    fun `the break never falls inside the translator's brackets`() {
        // The reader may ask for the asides to come off, and they are stripped
        // per half; half a bracket on each leaf would strip from neither.
        val text = "And it was revealed to [O Muhammad, the Prophet] at that time"
        // An offset inside the aside is carried back before the opening bracket.
        assertEquals(text.indexOf(" [O"), englishLeafBreak(text, text.indexOf("Muhammad")))
        // The break only ever moves back, so an offset just past the aside stops
        // at the space after it and the aside stays whole on this leaf.
        assertEquals(text.indexOf(" at that"), englishLeafBreak(text, text.indexOf("at that")))
    }

    @Test
    fun `a carried fragment says where its own text begins`() {
        // The fragment starts on the space the leaf before it broke on; the
        // text it sets is that trimmed. Mapping a measured character back
        // through `from` rather than `textFrom` puts every offset one early,
        // and a break that should have stayed put walks back a whole word.
        val leaf = englishLeaf(
            page = 3,
            runs = listOf(EnglishVerseRun(2, 2, 10, 30)),
        ) { _, _ -> "0123456789 and the rest of it goes on here" }
        val verse = leaf.verses.single()
        assertEquals(10, verse.from)
        assertEquals(11, verse.textFrom)
        // And the far end is snapped back off the middle of "goes".
        assertEquals("and the rest of it", verse.text)
    }

    @Test
    fun `the break only ever moves back, so a leaf is never handed more`() {
        // The direction is the point. A leaf is measured before it is set, and
        // the offset that comes back is the end of a line the well has room
        // for. Moving forward would hand it words nobody measured, which wrap
        // to a line it does not have; moving back can only hand it less.
        val text = "one two three four five"
        for (at in text.indices) {
            assertTrue("break at $at moved forward", englishLeafBreak(text, at) <= at)
        }
    }

    @Test
    fun `the break is where both leaves meet`() {
        // The leaf that ends at an offset and the leaf that begins there have to
        // land on the same character without either knowing about the other,
        // which is what makes it a pure function of the text and the offset.
        val text = "And [make him] a messenger to the Children of Israel, who said"
        for (at in text.indices) {
            val once = englishLeafBreak(text, at)
            assertEquals("break at $at is not settled", once, englishLeafBreak(text, once))
        }
    }

    @Test
    fun `the ink finds the reciter inside the half the leaf is setting`() {
        val verse = EnglishLeafVerse(2, 5, "second half", from = 50, to = 100, verseLength = 100)
        // The voice is at the middle of the verse, which is the head of this half.
        assertEquals(0f, verse.fragmentProgress(0.50f), 0.001f)
        assertEquals(1f, verse.fragmentProgress(1.00f), 0.001f)
        assertEquals(0.5f, verse.fragmentProgress(0.75f), 0.001f)
        // Still in the half before this one: nothing of this one is read.
        assertEquals(0f, verse.fragmentProgress(0.20f), 0.001f)
    }

    @Test
    fun `a verse the leaf sets whole maps straight through`() {
        val verse = EnglishLeafVerse(2, 5, "all of it")
        assertEquals(0.4f, verse.fragmentProgress(0.4f), 0.001f)
        assertTrue(verse.endsVerse)
    }

    @Test
    fun `the reading page of a straddling verse is the leaf it began on`() {
        val catalog = buildMushafCatalog(
            listOf(
                source(2, 2, 1, page = 3, line = 1),
                source(2, 2, 2, page = 4, line = 1),
            ),
        )
        // The Arabic leaf follows the word: the second word is printed on 4.
        assertEquals(4, catalog.readingPageOf(2, 2, 2, wholeVerses = false))
        // The English leaf set the whole sentence on 3, so that is where the
        // reader is while it is being recited.
        assertEquals(3, catalog.readingPageOf(2, 2, 2, wholeVerses = true))
    }
}

private fun source(
    surahId: Int,
    ayah: Int,
    position: Int,
    page: Int,
    line: Int,
) = MushafSourceWord(
    surahId = surahId,
    ayah = ayah,
    word = Word(
        position = position,
        arabic = "و",
        translation = "",
        transliteration = "",
        qcfPage = page,
        qcfLine = line,
    ),
)
