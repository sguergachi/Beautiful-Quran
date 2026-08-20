package com.beautifulquran.domain

import com.beautifulquran.data.model.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MushafQcfLineTest {

    @Test
    fun `ayah-final QCF token keeps the circled mark out of the word range`() {
        val line = buildMushafQcfLine(
            listOf(
                token(1, "بِسۡمِ", "\uFC41"),
                token(2, "ٱللَّهِ", "\uFC42"),
                token(3, "ٱلرَّحۡمَٰنِ", "\uFC43"),
                token(4, "ٱلرَّحِيمِ", "\uFC44 \uFC45", endsAyah = true),
            ),
        )
        assertEquals("\uFC41\uFC42\uFC43\uFC44\uFC45", line.text)
        assertEquals(4, line.wordRanges.size)
        assertEquals("\uFC44", line.text.substring(line.wordRanges[3]))
        assertEquals("\uFC45", qcfTrailingMark("\uFC44 \uFC45", endsAyah = true))
        assertTrue(line.text.endsWith("\uFC45"))
        assertFalse(line.text.contains('﴿'))
    }

    @Test
    fun `a rub mark before a word is part of the word, not a verse number`() {
        // 195 words open a rubʿ with ۞, and the DB writes that as an extra
        // glyph run *before* the word — the same shape as a verse mark, on the
        // other side. Read as a mark it took the word's place and the word was
        // drawn in verse-mark gold, standing gold before it was ever recited.
        val rub = "\uFC50 \uFC51"
        assertEquals("\uFC50\uFC51", qcfWordGlyphs(rub, endsAyah = false))
        assertEquals("", qcfTrailingMark(rub, endsAyah = false))
        val line = buildMushafQcfLine(listOf(token(1, "۞إِنَّآ", rub)))
        assertEquals("\uFC50\uFC51", line.text)
        // The whole token is the word: tapping the ۞ must select it too.
        assertEquals("\uFC50\uFC51", line.text.substring(line.wordRanges[0]))
    }

    @Test
    fun `a word set in more than one run stays one word`() {
        // إِلۡ on 37:130 takes three runs. Only the last run of a verse-final
        // token is ever a mark; a mid-verse token has none at all.
        val split = "\uFC50 \uFC51 \uFC52"
        assertEquals("\uFC50\uFC51\uFC52", qcfWordGlyphs(split, endsAyah = false))
        assertEquals("", qcfTrailingMark(split, endsAyah = false))
        // Verse-final, the same string gives up only its final run.
        assertEquals("\uFC50\uFC51", qcfWordGlyphs(split, endsAyah = true))
        assertEquals("\uFC52", qcfTrailingMark(split, endsAyah = true))
    }

    @Test
    fun `falls back to Hafs arabic when a token has no QCF glyph`() {
        val line = buildMushafQcfLine(listOf(token(1, "بِسۡمِ", "")))
        assertEquals("بِسۡمِ", line.text)
    }
}

private fun token(
    position: Int,
    arabic: String,
    qcf: String,
    endsAyah: Boolean = false,
) = MushafToken(
    surahId = 1,
    ayah = 1,
    word = Word(position = position, arabic = arabic, translation = "", transliteration = "", qcfV2 = qcf),
    endsAyah = endsAyah,
)

private fun String.substring(range: IntRange): String =
    substring(range.first, range.last + 1)
