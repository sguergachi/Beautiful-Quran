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
}
