package com.beautifulquran.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GildingTiltTest {
    @Test
    fun levelPhoneRestsMidway() {
        assertEquals(GILDING_REST, sheenForRoll(0f), 1e-6f)
    }

    @Test
    fun rollingEitherWayMovesTheLightTheOtherWayAndStopsAtTheEnds() {
        assertTrue(sheenForRoll(0.2f) > GILDING_REST)
        assertTrue(sheenForRoll(-0.2f) < GILDING_REST)
        assertEquals(1f, sheenForRoll(1f), 1e-6f)
        assertEquals(0f, sheenForRoll(-1f), 1e-6f)
    }

    @Test
    fun rollIsSymmetricAboutLevel() {
        assertEquals(1f, sheenForRoll(0.13f) + sheenForRoll(-0.13f), 1e-6f)
    }
}
