package com.beautifulquran.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class NudgeFontScaleTest {
    @Test
    fun decreasesByOneStop() {
        assertEquals(0.9f, nudgeFontScale(1.0f, -1), 0.001f)
    }

    @Test
    fun increasesByOneStop() {
        assertEquals(1.1f, nudgeFontScale(1.0f, +1), 0.001f)
    }

    @Test
    fun clampsAtMin() {
        assertEquals(0.8f, nudgeFontScale(0.8f, -1), 0.001f)
    }

    @Test
    fun clampsAtMax() {
        assertEquals(1.6f, nudgeFontScale(1.6f, +1), 0.001f)
    }

    @Test
    fun snapsOffStopBeforeNudging() {
        // 1.04 is nearer 1.0 than 1.1; decrease goes to 0.9.
        assertEquals(0.9f, nudgeFontScale(1.04f, -1), 0.001f)
    }

    @Test
    fun `pinch crosses the same scale stops`() {
        assertEquals(1.1f, pinchFontScale(1f, 1.06f), 0.001f)
        assertEquals(0.9f, pinchFontScale(1f, 0.94f), 0.001f)
        assertEquals(1.3f, pinchFontScale(1f, 1.26f), 0.001f)
    }

    @Test
    fun `small pinch stays at the current scale`() {
        assertEquals(1f, pinchFontScale(1f, 1.04f), 0.001f)
        assertEquals(1.04f, pinchFontScale(1.04f, 1f), 0.001f)
    }

    @Test
    fun `pinch clamps at the reader limits`() {
        assertEquals(1.6f, pinchFontScale(1.5f, 2f), 0.001f)
        assertEquals(0.8f, pinchFontScale(0.9f, 0.1f), 0.001f)
    }
}
