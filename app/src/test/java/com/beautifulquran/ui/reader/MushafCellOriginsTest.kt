package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Word
import com.beautifulquran.domain.MushafLine
import com.beautifulquran.domain.MushafLineFit
import com.beautifulquran.domain.MushafToken
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MushafCellOriginsTest {
    @Test
    fun `flush line places equal paper between ink bounds`() {
        val origins = mushafCellOrigins(
            cells = listOf(
                MushafCell(advance = 40f, inkLeft = 8f, inkRight = 28f),
                MushafCell(advance = 36f, inkLeft = -4f, inkRight = 16f),
            ),
            count = 2,
            width = 100f,
            fit = MushafLineFit(scale = 1f, gapPx = 12f, flush = true),
        )

        assertArrayEquals(floatArrayOf(72f, 4f), origins, 0.001f)
    }

    @Test
    fun `short line centres ink instead of advance boxes`() {
        val origins = mushafCellOrigins(
            cells = listOf(
                MushafCell(advance = 40f, inkLeft = 10f, inkRight = 30f),
                MushafCell(advance = 40f, inkLeft = 0f, inkRight = 20f),
            ),
            count = 2,
            width = 100f,
            fit = MushafLineFit(scale = 1f, gapPx = 10f, flush = false),
        )

        assertArrayEquals(floatArrayOf(45f, 25f), origins, 0.001f)
    }

    @Test
    fun `geometry content key follows the words, not the row number`() {
        // Display reflow rebuilds row 6 from a different token list once the
        // page face lands. A key that only names "page 46 line 6" would keep
        // the previous row's flush fit and open a river between three words.
        val before = MushafLine(
            number = 6,
            tokens = listOf(token(2, 271, 20), token(2, 272, 1), token(2, 272, 2)),
        )
        val after = MushafLine(
            number = 6,
            tokens = listOf(token(2, 272, 6), token(2, 272, 7), token(2, 272, 8)),
        )
        assertNotEquals(mushafLineContentKey(before), mushafLineContentKey(after))
        assertNotEquals(
            mushafLineContentKey(before),
            mushafLineContentKey(before.copy(tokens = before.tokens + token(2, 272, 3))),
        )
    }
}

private fun token(surahId: Int, ayah: Int, position: Int) = MushafToken(
    surahId = surahId,
    ayah = ayah,
    word = Word(
        position = position,
        arabic = "و",
        translation = "",
        transliteration = "",
        qcfPage = 46,
        qcfLine = 6,
    ),
    endsAyah = false,
)
