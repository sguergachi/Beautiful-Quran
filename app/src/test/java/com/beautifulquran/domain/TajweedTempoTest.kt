package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** English follows the reciter's tajweed tempo without parking on a held letter. */
class TajweedTempoTest {
    private val dallin = "ٱلضَّآلِّينَ"
    private val letters = requireNotNull(TajweedPacing.curve(dallin))

    private fun slopes(curve: TajweedPacing.Curve, until: Float = 1f): List<Float> =
        (0 until 200).map { i ->
            val a = until * i / 200f
            val b = until * (i + 1) / 200f
            (curve.at(b) - curve.at(a)) / (b - a)
        }

    @Test
    fun `the letter map parks on the madd but the tempo only slows`() {
        assertTrue("fixture must hold a letter", slopes(letters).min() < 0.1f)
        val tempo = letters.tempo(0.6f, 1f)
        val speeds = slopes(tempo)
        assertTrue("never parks: ${speeds.min()}", speeds.min() >= 0.4f - 1e-3f)
        assertTrue("slows on the hold: ${speeds.min()}", speeds.min() < 0.6f)
        assertTrue("quickens elsewhere: ${speeds.max()}", speeds.max() > 1.1f)
    }

    @Test
    fun `tempo starts unread finishes at the voiced end and rests through the breath`() {
        for (spoken in listOf(1f, 0.8f, 0.5f)) {
            val tempo = requireNotNull(TajweedPacing.curve(dallin, spoken)).tempo(0.6f, spoken)
            assertEquals(0f, tempo.at(0f), 0f)
            assertEquals(1f, tempo.at(1f), 0f)
            assertTrue(tempo.at(spoken) > 0.99f)
            assertTrue("moving before the voice ends", tempo.at(spoken * 0.9f) < 0.99f)
            var previous = 0f
            for (i in 0..400) {
                val x = tempo.at(i / 400f)
                assertTrue(x >= previous)
                previous = x
            }
        }
    }

    @Test
    fun `strength zero is the steady clock and one is the letter map`() {
        val steady = letters.tempo(0f, 1f)
        val full = letters.tempo(1f, 1f)
        for (i in 0..50) {
            val t = i / 50f
            assertEquals(t, steady.at(t), 1e-4f)
            assertEquals(letters.atLetters(t), full.at(t), 0.03f)
        }
    }
}
