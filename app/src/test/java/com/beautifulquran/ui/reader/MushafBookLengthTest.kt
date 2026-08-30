package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Word
import com.beautifulquran.domain.ENGLISH_LEAF_CAPACITY_CHARS
import com.beautifulquran.domain.MushafSourceWord
import com.beautifulquran.domain.buildEnglishBook
import com.beautifulquran.domain.buildMushafCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule is as long as the thing it measures.
 *
 * The pager, the folio and the page dial all number the same book, and the
 * dial did not: it was handed leaf numbers against a 604-page track, so the
 * chapter comb bunched into the middle of the rule and the end of the book
 * could not be reached. Every number [mushafLeafNumber] gives out has to land
 * inside [mushafBookLength].
 */
class MushafBookLengthTest {

    // A page whose English needs several leaves, so the two counts differ.
    private val catalog = buildMushafCatalog(
        (1..12).map { ayah -> source(2, ayah, page = 3) },
    )
    private val book = buildEnglishBook(catalog) { _, _ -> ENGLISH_LEAF_CAPACITY_CHARS / 2 }

    @Test
    fun `the Arabic rule is the mushaf, the English rule is the book`() {
        assertEquals(604, mushafBookLength(null, catalog.pageCount))
        assertEquals(book.leafCount, mushafBookLength(book, catalog.pageCount))
        // The point of the test: they are not the same number.
        assertTrue(book.leafCount != catalog.pageCount)
    }

    @Test
    fun `a verseless moment lands on a leaf, not on a Madinah page`() {
        // The basmalah has no verse of its own, and answering the raw page
        // handed a page number back as a leaf number. The two are the same
        // scale only on the Arabic leaf; on the English book the voice landed
        // on an unrelated leaf and the one carrying the preface never lit.
        val length = mushafBookLength(book, catalog.pageCount)
        val leaf = mushafLeafNumber(book, surahId = 2, ayah = null, page = 3)
        assertTrue("no verse landed on leaf $leaf of $length", leaf in 1..length)
        assertEquals(book.firstLeafOf(3) + 1, leaf)
    }

    @Test
    fun `the reciter's place inside a verse picks the leaf`() {
        // What the page turn reads. A verse the book carried stands on two
        // leaves; following its opening one for the whole verse left the reader
        // on the first half until the next verse began.
        val page = 3
        val opening = mushafLeafNumber(book, 2, 1, page, through = 0f)
        val late = mushafLeafNumber(book, 2, 1, page, through = 1f)
        assertTrue(late >= opening)
    }

    // A page whose second verse is too long to finish on the leaf, so the book
    // carries it over and there is a cut to lead the turn from.
    private val carriedBook = buildEnglishBook(
        buildMushafCatalog(listOf(source(2, 2, page = 3), source(2, 3, page = 3))),
    ) { _, a -> if (a == 2) ENGLISH_LEAF_CAPACITY_CHARS / 2 else ENGLISH_LEAF_CAPACITY_CHARS }

    @Test
    fun `the turn is led from the last word before the book's cut`() {
        // A page turned exactly when the first word overleaf is spoken is
        // late, so the turn starts inside the word before it. On the English
        // leaf that word is the one before the carry cut, which is the only
        // place the leaf really stops mid-sentence.
        val words = 10
        val page = 3
        val here = mushafLeafNumber(carriedBook, 2, 3, page, through = 0f)
        val cutWord = (1..words).first {
            mushafLeafNumber(carriedBook, 2, 3, page, through = it.toFloat() / words) > here
        }
        // On the word before the cut the rule leads onto the next leaf.
        assertEquals(
            here + 1,
            mushafLeadCarriedTurn(
                book = carriedBook,
                surahId = 2,
                ayah = 3,
                onPage = page,
                word = activeWord(cutWord),
                words = words,
                leafNow = here,
            ),
        )
        // Anywhere earlier there is no turn to lead.
        (1 until cutWord).forEach { earlier ->
            assertNull(
                "word $earlier led a turn",
                mushafLeadCarriedTurn(
                    book = carriedBook,
                    surahId = 2,
                    ayah = 3,
                    onPage = page,
                    word = activeWord(earlier),
                    words = words,
                    leafNow = here,
                ),
            )
        }
    }

    @Test
    fun `the voice's place in a carried verse is read on the ink's own map`() {
        // The book cut the verse at a character, so where the voice stands has
        // to be measured the way the wash measures it. Word 4's English begins
        // three tenths of the way in, not three fifths.
        val ends = floatArrayOf(0.1f, 0.2f, 0.3f, 0.9f, 1f)
        assertEquals(0.3f, mushafVerseThrough(activeWord(4), 5, ends), 1e-6f)
        assertEquals(0f, mushafVerseThrough(activeWord(1), 5, ends), 1e-6f)
        // No map, or one for a different verse: even shares, as before.
        assertEquals(0.6f, mushafVerseThrough(activeWord(4), 5), 1e-6f)
        assertEquals(0.6f, mushafVerseThrough(activeWord(4), 5, floatArrayOf(1f)), 1e-6f)
    }

    @Test
    fun `the lead onto the cut fires where the English says the cut is`() {
        // A verse whose first word already carries the whole sentence: one word
        // from now the voice is past every cut, so the turn is led immediately
        // — where the even-share reading would have waited for word 10.
        val words = 10
        val page = 3
        val here = mushafLeafNumber(carriedBook, 2, 3, page, through = 0f)
        assertEquals(
            here + 1,
            mushafLeadCarriedTurn(
                book = carriedBook,
                surahId = 2,
                ayah = 3,
                onPage = page,
                word = activeWord(1),
                words = words,
                leafNow = here,
                wordEnds = FloatArray(words) { 1f },
            ),
        )
        assertNull(
            mushafLeadCarriedTurn(
                book = carriedBook,
                surahId = 2,
                ayah = 3,
                onPage = page,
                word = activeWord(1),
                words = words,
                leafNow = here,
            ),
        )
    }

    @Test
    fun `a verse set whole never leads a turn`() {
        assertNull(
            mushafLeadCarriedTurn(
                book = carriedBook,
                surahId = 2,
                ayah = 2,
                onPage = 3,
                word = activeWord(5),
                words = 10,
                leafNow = mushafLeafNumber(carriedBook, 2, 2, 3),
            ),
        )
    }

    @Test
    fun `nothing is led on the Arabic leaf, which has no cuts of its own`() {
        assertNull(
            mushafLeadCarriedTurn(
                book = null,
                surahId = 2,
                ayah = 1,
                onPage = 3,
                word = activeWord(1),
                words = 10,
                leafNow = 3,
            ),
        )
    }

    @Test
    fun `every leaf the reader can be sent to lands on the rule`() {
        val length = mushafBookLength(book, catalog.pageCount)
        (1..12).forEach { ayah ->
            val leaf = mushafLeafNumber(book, surahId = 2, ayah = ayah, page = 3)
            assertTrue("verse $ayah landed on leaf $leaf of $length", leaf in 1..length)
        }
    }

    @Test
    fun `every chapter's opening leaf lands on the rule too`() {
        // What the dial's coarse comb is built from.
        val length = mushafBookLength(book, catalog.pageCount)
        val opening = mushafLeafNumber(book, surahId = 2, ayah = 1, page = 3)
        assertEquals(1, opening)
        // The comb walks forward through the book and never off the end of the
        // rule. (The last leaf may carry only the tail of a verse that began on
        // the one before, so nothing need *open* on it.)
        val reached = (1..12).map { mushafLeafNumber(book, surahId = 2, ayah = it, page = 3) }
        assertTrue(reached.zipWithNext().all { (a, z) -> z >= a })
        assertTrue(reached.max() <= length)
    }
}

private fun activeWord(position: Int) = ActiveWord(
    ayah = 1,
    wordPosition = position,
    durationMs = 500L,
)

private fun source(surahId: Int, ayah: Int, page: Int) = MushafSourceWord(
    surahId = surahId,
    ayah = ayah,
    word = Word(
        position = 1,
        arabic = "و",
        translation = "",
        transliteration = "",
        qcfPage = page,
        qcfLine = 1,
    ),
)
