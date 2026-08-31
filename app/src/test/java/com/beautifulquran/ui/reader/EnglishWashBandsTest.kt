package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The English leaf blooms one word at a time, as the scrolling reader does. The
 * three bands are how a sentence says which word that is — see
 * `englishVerseBlooms`.
 */
class EnglishWashBandsTest {

    /** A sentence occupying characters 10..109 of the paragraph. */
    private val sentence = 10..109

    @Test
    fun `the bands tile the sentence with no gap and no overlap`() {
        val bands = englishWashBands(sentence, from = 0.3f, to = 0.4f)
        assertEquals(10 until 40, bands.read)
        assertEquals(40 until 50, bands.saying)
        assertEquals(50..109, bands.ahead)
        // Every character of the sentence is in exactly one band.
        val covered = (bands.read.toList() + bands.saying.toList() + bands.ahead.toList())
        assertEquals(sentence.toList(), covered)
    }

    @Test
    fun `the first word opens the sentence and the last closes it`() {
        val opening = englishWashBands(sentence, from = 0f, to = 0.1f)
        assertTrue(opening.read.isEmpty())
        assertEquals(10 until 20, opening.saying)

        val closing = englishWashBands(sentence, from = 0.9f, to = 1f)
        assertEquals(100..109, closing.saying)
        assertTrue(closing.ahead.isEmpty())
    }

    @Test
    fun `a word with no English of its own blooms nothing`() {
        // The alignment collapses two Arabic words onto one English span where
        // the translation has no separate words for them. The second gets an
        // empty middle band — nothing to bloom — and the split stays put.
        val bands = englishWashBands(sentence, from = 0.5f, to = 0.5f)
        assertTrue(bands.saying.isEmpty())
        assertEquals(10 until 60, bands.read)
        assertEquals(60..109, bands.ahead)
    }

    @Test
    fun `the half of a carried verse the voice is not on needs no special case`() {
        // Fragment progress clamps, so a voice before this leaf's half comes
        // out all ahead, and a voice past it comes out all read.
        val before = englishWashBands(sentence, from = 0f, to = 0f)
        assertTrue(before.read.isEmpty())
        assertTrue(before.saying.isEmpty())
        assertEquals(sentence, before.ahead)

        val after = englishWashBands(sentence, from = 1f, to = 1f)
        assertEquals(sentence, after.read)
        assertTrue(after.saying.isEmpty())
        assertTrue(after.ahead.isEmpty())
    }

    @Test
    fun `bands never run backwards, whatever they are handed`() {
        val bands = englishWashBands(sentence, from = 0.8f, to = 0.2f)
        assertTrue("saying must not start before it ends", bands.saying.isEmpty())
        assertEquals(10 until 90, bands.read)
        assertEquals(90..109, bands.ahead)
        // Out of range fractions are clamped rather than read off the end.
        val wild = englishWashBands(sentence, from = -3f, to = 9f)
        assertTrue(wild.read.isEmpty())
        assertEquals(sentence, wild.saying)
        assertTrue(wild.ahead.isEmpty())
    }

    @Test
    fun `an empty sentence has no bands to draw`() {
        val bands = englishWashBands(IntRange.EMPTY, from = 0f, to = 1f)
        assertTrue(bands.read.isEmpty())
        assertTrue(bands.saying.isEmpty())
        assertTrue(bands.ahead.isEmpty())
    }
}
