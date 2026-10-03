package com.beautifulquran.ui.theme

import androidx.compose.ui.graphics.Color
import com.beautifulquran.ui.reader.InkEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlintColorTest {
    private val gold = Color(0xFFF8E9BE)
    private val repeat = Color(0xFFE06A18)

    @Test
    fun `brightness can lift a moderate real crest to white without lifting troughs`() {
        for (base in listOf(gold, repeat)) {
            assertEquals(Color.White, glintPulseColor(base, 0.67f, brightness = 2f))
            assertEquals(base, glintPulseColor(base, 0f, brightness = 2f))
            assertEquals(base, glintPulseColor(base, 0.67f, brightness = 0f))
        }
    }

    @Test
    fun `accepted positive crest reaches white and falls back to the original hue`() {
        for (base in listOf(gold, repeat)) {
            val colours = listOf(-1f, 0f, 0.5f, 1f, 0.5f, 0f, -1f).map { pulse ->
                val resonance = InkEngine.glintResonance(true, pulse, 1f, depth = 1f)
                glintPulseColor(base, resonance.peak)
            }
            assertEquals(base, colours.first())
            assertEquals(base, colours[1])
            assertEquals(Color.White, colours[3])
            assertEquals(colours[2], colours[4])
            assertEquals(base, colours.last())
            assertTrue(colours[2].blue > base.blue)
            assertTrue(colours[2].blue < 1f)
        }
    }

    @Test
    fun `rejected and fading pulses do not pop to full white`() {
        assertEquals(gold, glintPulseColor(gold, InkEngine.glintResonance(false, 1f, 1f).peak))
        assertEquals(gold, glintPulseColor(gold, InkEngine.glintResonance(true, 1f, 0f).peak))
        val fade = glintPulseColor(gold, InkEngine.glintResonance(true, 1f, 0.5f, depth = 1f).peak)
        assertTrue(fade.blue > gold.blue && fade.blue < 1f)
    }
}
