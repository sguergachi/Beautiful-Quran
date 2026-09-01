package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A verse carried over a leaf break is cut at the end of a sentence, never in
 * the middle of one. See `buildEnglishBook`.
 */
class EnglishSentenceBreakTest {

    @Test
    fun `a sentence ends at the space after its terminator`() {
        val text = "One two. Three four! Five six? Seven."
        val ends = englishSentenceEnds(text)
        // The final terminator closes the string, so it opens nothing.
        assertEquals(3, ends.size)
        for (end in ends) {
            assertTrue("end $end is not whitespace", text[end].isWhitespace())
            assertTrue("no terminator before $end", text[end - 1] in ".!?")
        }
        assertEquals("One two.", text.substring(0, ends[0]))
        assertEquals("One two. Three four!", text.substring(0, ends[1]))
    }

    @Test
    fun `a terminator behind a quote or bracket still closes the sentence`() {
        val text = "He said, \"Go.\" Then he left. And [recall] that."
        val ends = englishSentenceEnds(text)
        assertEquals("He said, \"Go.\"", text.substring(0, ends[0]))
        assertEquals("He said, \"Go.\" Then he left.", text.substring(0, ends[1]))
    }

    @Test
    fun `a terminator inside brackets is not a place to break`() {
        // The reader may have asked for the asides to come off, and a cut
        // inside one would leave half a bracket on each leaf.
        val text = "Something [i.e. a thing] follows. And then more."
        val ends = englishSentenceEnds(text)
        assertEquals(1, ends.size)
        assertEquals("Something [i.e. a thing] follows.", text.substring(0, ends[0]))
    }

    @Test
    fun `the cut is the last sentence that fits the room left`() {
        // Sentences ending at 100, 200 and 300 of a 400-character verse.
        val ends = intArrayOf(100, 200, 300)
        assertEquals(200, englishSentenceCut(ends, from = 0, length = 400, room = 250))
        assertEquals(100, englishSentenceCut(ends, from = 0, length = 400, room = 150))
        assertEquals(300, englishSentenceCut(ends, from = 0, length = 400, room = 999))
    }

    @Test
    fun `a cut that would strand too little on either leaf is refused`() {
        val ends = intArrayOf(100, 200, 300)
        // Nothing fits: the first sentence alone is longer than the room.
        assertNull(englishSentenceCut(ends, from = 0, length = 400, room = 50))
        // The tail would be a sliver, so 300 is out and 200 is taken instead.
        assertEquals(
            200,
            englishSentenceCut(ends, from = 0, length = 300 + ENGLISH_LEAF_MIN_FRAGMENT_CHARS - 1, room = 999),
        )
        // A fragment shorter than the minimum is not worth a leaf of its own.
        assertNull(englishSentenceCut(intArrayOf(10), from = 0, length = 400, room = 999))
    }

    @Test
    fun `a verse with no sentence end in reach is not cut at all`() {
        // Null is the answer that this verse belongs whole on the next leaf.
        assertNull(englishSentenceCut(IntArray(0), from = 0, length = 400, room = 300))
    }

    @Test
    fun `the cut only ever moves forward through a verse`() {
        val ends = intArrayOf(100, 200, 300)
        assertEquals(300, englishSentenceCut(ends, from = 100, length = 400, room = 999))
        // Already past every sentence end: nothing left to cut at.
        assertNull(englishSentenceCut(ends, from = 300, length = 400, room = 999))
    }

    @Test
    fun `a leaf cut at a sentence end keeps the sentence whole`() {
        val text = "First sentence here. Second sentence here. Third one here."
        val end = englishSentenceEnds(text)[0]
        // englishLeafBreak must leave a sentence end where it found it, so the
        // leaf that ends there and the leaf that begins there agree.
        assertEquals(end, englishLeafBreak(text, end))
        assertEquals("First sentence here.", text.substring(0, end))
        assertEquals("Second sentence here. Third one here.", text.substring(end).trim())
    }
}
