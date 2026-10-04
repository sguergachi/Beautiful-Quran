package com.beautifulquran.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.beautifulquran.ui.reader.InkEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class GlintColorTest {
    private val gold = Color(0xFFFFF0C7)
    private val repeat = Color(0xFFFF8948)
    private val shipped = InkEngine.Tuning()

    private fun level(glow: Float, brightness: Float = 1f) =
        glintLightLevel(glow, brightness, shipped.tarjiLightRise, shipped.tarjiLightFall)

    /** Chromaticity in linear light: what "the hue never changes" means. */
    private fun chroma(c: Color): Pair<Float, Float> {
        val l = c.convert(androidx.compose.ui.graphics.colorspace.ColorSpaces.LinearSrgb)
        val sum = l.red + l.green + l.blue
        return l.red / sum to l.green / sum
    }

    @Test
    fun `a word the voice is not moving keeps its ordinary light`() {
        assertEquals(1f, level(0f), 0f)
        assertEquals(1f, level(InkEngine.GlintResonance.Idle.glow), 0f)
        assertEquals(1f, level(1f, brightness = 0f), 0f)
        for (base in listOf(gold, repeat)) assertEquals(base, glintLightColor(base, 1f))
        assertEquals(0.6f, glintGlowAlpha(0.6f, 1f, shipped.tarjiGlowGain), 0f)
    }

    @Test
    fun `the light lifts a little with the voice and dips less`() {
        assertEquals(1.10f, level(1f), 1e-4f)
        assertEquals(0.94f, level(-1f), 1e-4f)
        assertTrue(shipped.tarjiLightFall < shipped.tarjiLightRise)
        // The eye catches well under 1 % at these rates; a tenth is plenty,
        // and the whole swing stays a small part of the page's range.
        val swing = glintLightColor(gold, level(1f)).luminance() - glintLightColor(gold, level(-1f)).luminance()
        assertTrue("swing $swing", swing in 0.05f..0.2f)
        // The per-reciter brightness scales the swing and nothing else.
        assertEquals(1.20f, level(1f, brightness = 2f), 1e-4f)
        assertEquals(1.05f, level(0.5f), 1e-4f)
    }

    @Test
    fun `brightness changes and the hue does not`() {
        for (base in listOf(gold, repeat)) {
            val rest = chroma(base)
            // Dimming is exact; brightening holds until a channel is full.
            for (l in listOf(0.84f, 0.92f, 0.97f)) {
                val c = chroma(glintLightColor(base, l))
                assertEquals(rest.first, c.first, 2e-3f)
                assertEquals(rest.second, c.second, 2e-3f)
            }
            val levels = listOf(0.84f, 0.92f, 1f, 1.07f, 1.14f, 1.28f).map { glintLightColor(base, it).luminance() }
            assertTrue(levels.zipWithNext().all { (a, b) -> a < b })
            assertTrue(glintLightColor(base, 1.28f).alpha == base.alpha)
        }
    }

    @Test
    fun `the glow swings further than the glyphs`() {
        val up = glintGlowAlpha(0.6f, level(1f), shipped.tarjiGlowGain)
        val down = glintGlowAlpha(0.6f, level(-1f), shipped.tarjiGlowGain)
        assertEquals(0.6f * 1.2f, up, 1e-4f)
        assertEquals(0.6f * 0.88f, down, 1e-4f)
        // It can run out of headroom but never past it, and never below dark.
        assertEquals(1f, glintGlowAlpha(0.9f, 1.28f, 6f), 0f)
        assertEquals(0f, glintGlowAlpha(0.5f, 0.5f, 6f), 0f)
        assertEquals(0f, glintGlowAlpha(0f, 1.28f, 6f), 0f)
    }

    @Test
    fun `the veil is the same light, warmed`() {
        assertEquals(gold, glintVeilColor(gold, 0f))
        val warm = glintVeilColor(gold, 0.5f)
        assertTrue(warm.blue < gold.blue && warm.red == gold.red)
    }

    @Test
    fun `the light moves as a smooth swell, the same up as down`() {
        val light = GlintLight()
        assertEquals(0f, light.next(0f, 0L, 60f), 0f)
        // A step is never taken in one frame…
        val first = light.next(1f, 16_666_667L, 60f)
        assertTrue("first frame $first", first in 0.2f..0.3f)
        // …and coming back down retraces it.
        var up = first
        for (i in 2..30) up = light.next(1f, i * 16_666_667L, 60f)
        assertEquals(1f, up, 1e-3f)
        val back = light.next(0f, 31 * 16_666_667L, 60f)
        assertEquals(1f - first, back, 1e-3f)
        // No smoothing passes the voice straight through.
        assertEquals(-0.4f, GlintLight().also { it.next(0f, 0L, 0f) }.next(-0.4f, 8_000_000L, 0f), 0f)
    }

    @Test
    fun `no frame-to-frame step is abrupt at either refresh rate`() {
        for (fps in listOf(60, 120)) for (hz in listOf(4, 6, 10)) {
            val light = GlintLight()
            val lit = (0..fps).map { frame ->
                val pulse = InkEngine.glintResonance(true, sin(2 * PI * hz * frame / fps).toFloat(),
                    0.5f, depth = 1f, enabled = true)
                glintLightColor(gold, level(light.next(pulse.light, frame * 1_000_000_000L / fps, 60f))).luminance()
            }.drop(fps / 4)
            val step = lit.zipWithNext().maxOf { (a, b) -> abs(a - b) }
            assertTrue("$hz Hz at $fps fps steps $step a frame", step < 0.03f)
            assertTrue("$hz Hz at $fps fps still moves", lit.max() - lit.min() > 0.02f)
        }
    }

    @Test
    fun `the same swell at either refresh rate`() {
        fun at(fps: Int): Float {
            val light = GlintLight().also { it.next(0f, 0L, 60f) }
            return (1..fps / 10).map { light.next(1f, it * 1_000_000_000L / fps, 60f) }.last()
        }
        assertEquals(at(60), at(120), 1e-3f)
    }

    @Test
    fun `a rejected hold and a fading one never pop`() {
        assertEquals(0f, InkEngine.glintResonance(false, 1f, 1f).light, 0f)
        assertEquals(0f, InkEngine.glintResonance(true, 1f, 0f).light, 0f)
        assertTrue(InkEngine.glintResonance(true, 1f, 0.05f, depth = 1f).light in 0.1f..0.3f)
        assertEquals(0f, InkEngine.glintRestAlpha(0.88f, 0f), 0f)
        assertEquals(0.88f, InkEngine.glintRestAlpha(0.88f, 2f), 0f)
        assertEquals(0.44f, InkEngine.glintRestAlpha(0.88f, 0.5f), 0f)
    }
}
