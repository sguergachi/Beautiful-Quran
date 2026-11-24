package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/** The leaf's measures, shared by the leaf and the warm-up that sets leaves ahead of it. */
class MushafLeafFrameTest {

    private val frame = MushafLeafFrame(
        widthPx = 1000f,
        heightPx = 1800f,
        edgeGutterPx = 20f,
        fitSlackPx = 4f,
        unitPx = 100f,
    )

    @Test
    fun `lines are set inside the gutters, the type fitted inside the slack too`() {
        assertEquals(960f, frame.lineMeasurePx, 0f)
        assertEquals(952f, frame.fitWidthPx, 0f)
    }

    @Test
    fun `the well gives up only rounding slack`() {
        assertEquals(1796f, frame.wellHeightPx, 0f)
    }

    @Test
    fun `measures never fall to nothing`() {
        val tiny = MushafLeafFrame(widthPx = 10f, heightPx = 2f, edgeGutterPx = 20f, fitSlackPx = 4f, unitPx = 1f)
        assertEquals(1f, tiny.lineMeasurePx, 0f)
        assertEquals(1f, tiny.fitWidthPx, 0f)
        assertEquals(1f, tiny.wellHeightPx, 0f)
    }
}
