package com.beautifulquran.ui.theme.paperink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperInkPoolingTest {
    @Test fun smallTypeGetsGentlyLessWeightRelativeToItsStrokes() {
        fun relative(dp: Float) = paperInkPoolingWeight(.25f, dp * 2f, 2f) / (dp * 2f)
        val large = relative(48f)
        assertTrue(relative(12f) / large in .7f.. .75f)
        assertTrue(relative(24f) / large in .8f.. .9f)
        assertTrue(relative(36f) / large in .9f..1f)
        assertEquals(large, relative(72f), 1e-6f)
    }

    @Test fun physicalFontSizeAndPoolingAmountSurviveDensityChanges() {
        for (fontDp in listOf(12f, 24f, 48f, 72f)) {
            val atOne = paperInkPoolingWeight(.25f, fontDp, 1f)
            for (density in listOf(1.5f, 2f, 3f)) {
                assertEquals(atOne, paperInkPoolingWeight(.25f, fontDp * density, density) / density, 1e-6f)
            }
            assertEquals(0f, paperInkPoolingWeight(0f, fontDp, 1f), 0f)
            assertEquals(atOne * 2f, paperInkPoolingWeight(.5f, fontDp, 1f), 1e-6f)
        }
    }
}
