package com.beautifulquran.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.beautifulquran.ui.reader.InkEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

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
        val halfway = hue.next(0.67f, 2f, 15_000_000L)
        assertEquals(0.5f, halfway, 0.0001f)
        assertTrue(glintPulseColor(gold, halfway).blue in (gold.blue + 0.01f)..0.99f)
        assertEquals(1f, hue.next(0.67f, 2f, 30_000_000L), 0.0001f)
        assertEquals(0.5f, hue.next(0f, 2f, 45_000_000L), 0.0001f)
        assertEquals(0f, hue.next(0f, 2f, 60_000_000L), 0.0001f)
    }

    @Test
    fun `fast soft pulses retain a large visible swing over bright parchment ink`() {
        val parchment = Color(0xFFE8E2D5)
        for (rate in listOf(60, 120)) for (hz in listOf(6, 10)) {
            val hue = GlintColorTransition()
            val luminances = (0..rate).map { frame ->
                val pulse = InkEngine.glintResonance(true, sin(2 * PI * hz * frame / rate).toFloat(),
                    0.2f, depth = 1f, enabled = true, brightness = 2f)
                val white = hue.next(pulse.huePeak, 2f, frame * 1_000_000_000L / rate)
                val tint = glintContrastAlpha(InkEngine.glintColorAlpha(
                    InkEngine.Tuning().glintTintAlpha, pulse.peak, 2f), pulse.inkStrength)
                val coverage = glintContrastAlpha(pulse.layerMult, pulse.inkStrength) * tint
                glintPulseColor(gold, white, inkStrength = pulse.inkStrength)
                    .copy(alpha = coverage).compositeOver(parchment).luminance()
            }
            assertTrue("$hz Hz at $rate fps needs a white peak", luminances.max() > 0.9f)
            assertTrue("$hz Hz at $rate fps needs a dark valley", luminances.min() < 0.35f)
            assertTrue("$hz Hz at $rate fps must visibly breathe", luminances.max() - luminances.min() > 0.6f)
        }
    }

    @Test
    fun `ink contrast releases to ordinary sheen and respects disable depth and brightness`() {
        val idle = InkEngine.glintResonance(false, 1f, 1f, enabled = true)
        assertEquals(gold, glintPulseColor(gold, idle.whiteMix, inkStrength = idle.inkStrength))
        assertEquals(0.4f, glintContrastAlpha(0.4f, idle.inkStrength), 0f)
        for (pulse in listOf(
            InkEngine.glintResonance(true, -1f, 0f),
            InkEngine.glintResonance(true, -1f, 1f, enabled = false),
            InkEngine.glintResonance(true, -1f, 1f, depth = 0f),
            InkEngine.glintResonance(true, -1f, 1f, brightness = 0f),
        )) assertEquals(0f, pulse.inkStrength, 0f)
        val fading = InkEngine.glintResonance(true, -1f, 0.05f, depth = 1f, enabled = true, brightness = 2f)
        assertTrue(fading.inkStrength in 0f..0.25f)
        assertEquals(1f, glintPulseColor(gold, 0f, inkStrength = fading.inkStrength).alpha, 0f)
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
