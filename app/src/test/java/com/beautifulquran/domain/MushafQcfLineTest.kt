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
                token(4, "ٱلرَّحِيمِ", "\uFC44 \uFC45"),
            ),
        )
        assertEquals("\uFC41\uFC42\uFC43\uFC44\uFC45", line.text)
        assertEquals(4, line.wordRanges.size)
        assertEquals("\uFC44", line.text.substring(line.wordRanges[3]))
        assertEquals("\uFC45", qcfTrailingMark("\uFC44 \uFC45"))
        assertTrue(line.text.endsWith("\uFC45"))
        assertFalse(line.text.contains('﴿'))
    }

    @Test
    fun `falls back to Hafs arabic when a token has no QCF glyph`() {
        val line = buildMushafQcfLine(listOf(token(1, "بِسۡمِ", "")))
        assertEquals("بِسۡمِ", line.text)
    }
}

private fun token(position: Int, arabic: String, qcf: String) = MushafToken(
    surahId = 1,
    ayah = 1,
    word = Word(position = position, arabic = arabic, translation = "", transliteration = "", qcfV2 = qcf),
    endsAyah = qcf.contains(' '),
)

private fun String.substring(range: IntRange): String =
    substring(range.first, range.last + 1)
