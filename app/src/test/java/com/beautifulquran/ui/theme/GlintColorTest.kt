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
    fun `a soft accepted crest can reach white while its gain still controls strength`() {
        val pulse = InkEngine.glintResonance(true, 1f, 0.2f, depth = 1f, enabled = true)
        assertEquals(0.2f, pulse.peak, 0.0001f)
        assertEquals(1f, pulse.huePeak, 0.0001f)
        val hue = GlintColorTransition()
        hue.next(pulse.huePeak, 2f, 0L)
        assertEquals(Color.White, glintPulseColor(gold, hue.next(pulse.huePeak, 2f, 120_000_000L)))
        assertEquals(0f, InkEngine.glintResonance(false, 1f, 0.2f).huePeak, 0f)
        assertEquals(0f, InkEngine.glintResonance(true, 1f, 0f).huePeak, 0f)
        assertEquals(0f, InkEngine.glintResonance(true, -1f, 0.2f).huePeak, 0f)
    }

    @Test
    fun `a stepped crest travels through colour before reaching white`() {
        val hue = GlintColorTransition()
        assertEquals(0f, hue.next(0.67f, 2f, 0L), 0f)
        val halfway = hue.next(0.67f, 2f, 60_000_000L)
        assertEquals(0.5f, halfway, 0.0001f)
        assertTrue(glintPulseColor(gold, halfway).blue in (gold.blue + 0.01f)..0.99f)
        assertEquals(1f, hue.next(0.67f, 2f, 120_000_000L), 0.0001f)
        assertEquals(0.5f, hue.next(0f, 2f, 180_000_000L), 0.0001f)
        assertEquals(0f, hue.next(0f, 2f, 240_000_000L), 0.0001f)
    }

    @Test
    fun `hue travel follows elapsed time at either display refresh rate`() {
        fun at(rate: Int): Float {
            val hue = GlintColorTransition()
            return (0..rate / 10).map { i -> hue.next(0.67f, 2f, i * 1_000_000_000L / rate) }.last()
        }
        assertEquals(at(60), at(120), 0.0001f)
    }

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
