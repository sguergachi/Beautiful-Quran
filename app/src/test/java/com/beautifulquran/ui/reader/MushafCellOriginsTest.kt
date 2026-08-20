package com.beautifulquran.ui.reader

import com.beautifulquran.domain.MushafLineFit
import org.junit.Assert.assertArrayEquals
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
}
