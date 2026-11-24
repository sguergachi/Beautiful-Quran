package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Word
import com.beautifulquran.domain.MUSHAF_MAX_LINE_SCALE
import com.beautifulquran.domain.MushafLine
import com.beautifulquran.domain.MushafLineFit
import com.beautifulquran.domain.MushafToken
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MushafCellOriginsTest {
    @Test
    fun `a sparse full row stays flush and only a known ending may stand short`() {
        val joins = List(3) { MushafInkJoin(closest = 0.10f, tight = 0.15f, paper = 0.25f) }
        val full = mushafInkLineFit(300f, joins, 900f, 100f)
        val ending = mushafInkLineFit(300f, joins, 900f, 100f, allowShort = true)
        val cell = MushafCell(advance = 75f, inkLeft = 0f, inkRight = 75f).scaled(full.scale)
        val origins = mushafCellOrigins(List(4) { cell }, 4, 900f, full, joins, 100f * full.scale)

        assertTrue(full.flush)
        assertTrue(!ending.flush)
        assertEquals(900f, origins.first() + cell.inkRight, 0.01f)
        assertEquals(0f, origins.last(), 0.01f)
    }

    @Test
    fun `a chapter ending that stands short is spaced like a full row`() {
        val joins = List(3) { MushafInkJoin(closest = 0.10f, tight = 0.15f, paper = 0.25f) }
        val ending = mushafInkLineFit(300f, joins, 900f, 100f, allowShort = true)
        val cell = MushafCell(advance = 75f, inkLeft = 0f, inkRight = 75f).scaled(ending.scale)
        val origins = mushafCellOrigins(List(4) { cell }, 4, 900f, ending, joins, 100f * ending.scale)

        assertTrue(!ending.flush)
        // In the same hand as an ordinary row: its letters at the row stretch,
        // each join opened to the full-row white, centred on the measure.
        assertEquals(MUSHAF_MAX_LINE_SCALE, ending.scale, 0.0001f)
        val white = joins.first().whiteAt(MUSHAF_MAX_WHITE_LEVEL_EM) - joins.first().paper
        for (i in 0 until 3) {
            assertEquals(white * 100f * ending.scale, origins[i] - origins[i + 1] - cell.inkWidth, 0.5f)
        }
        val left = origins.last()
        val right = 900f - (origins.first() + cell.inkRight)
        assertEquals(left, right, 0.5f)
    }

    @Test
    fun `a chapter ending close to full is justified, not left standing short`() {
        val joins = List(8) { MushafInkJoin(closest = 0.10f, tight = 0.15f, paper = 0.25f) }
        // Its white opens a little past a full row's (about 0.73 em): justified.
        assertTrue(mushafInkLineFit(400f, joins, 900f, 100f, allowShort = true).flush)
        // About 0.98 em, which used to be justified too (up to 1.25): now short.
        assertTrue(!mushafInkLineFit(200f, joins, 900f, 100f, allowShort = true).flush)
        // A row that is not a chapter's last never stands short.
        assertTrue(mushafInkLineFit(200f, joins, 900f, 100f).flush)
    }

    @Test
    fun `a loose full line widens its letters before opening large word gaps`() {
        val joins = List(4) { MushafInkJoin(closest = 0.10f, tight = 0.15f, paper = 0.25f) }
        val fit = mushafInkLineFit(600f, joins, measureWidthPx = 900f, fontPx = 100f)
        val cell = MushafCell(advance = 120f, inkLeft = 0f, inkRight = 120f).scaled(fit.scale)
        val origins = mushafCellOrigins(List(5) { cell }, 5, 900f, fit, joins, 100f * fit.scale)

        assertTrue(fit.flush)
        assertTrue(fit.scale > 1.10f)
        assertEquals(900f, origins.first() + cell.inkRight, 0.01f)
        assertEquals(0f, origins.last(), 0.01f)
        joins.indices.forEach { i ->
            val gap = origins[i] - origins[i + 1] - cell.inkWidth
            val gapEm = gap / (100f * fit.scale)
            assertTrue(gapEm + joins[i].closest >= MUSHAF_HARD_WHITE_EM)
            assertTrue(gapEm + joins[i].paper < 0.75f)
        }
    }

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
