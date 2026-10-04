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

    /** The reader's frame loop for a pure [hz] reverberation at [gain]. */
    private fun glows(hz: Int, fps: Int, gain: Float, seconds: Float = 1f): List<Float> {
        val flame = GlintFlame()
        return (0..(fps * seconds).toInt()).map { frame ->
            val pulse = InkEngine.glintResonance(true, sin(2 * PI * hz * frame / fps).toFloat(),
                gain, depth = 1f, enabled = true)
            flame.next(pulse.light, frame * 1_000_000_000L / fps)
        }
    }

    @Test
    fun `a word the voice is not moving keeps its ordinary sheen`() {
        for (base in listOf(gold, repeat)) {
            assertEquals(base, glintPulseColor(base, InkEngine.GlintResonance.Idle.light))
            assertEquals(base, glintPulseColor(base, InkEngine.glintResonance(false, 1f, 1f).light))
            assertEquals(0.4f, glintLitAlpha(0.4f, 0f), 0f)
            assertEquals(0.78f, glintHaloAlpha(0.78f, 0f), 0f)
        }
    }

    @Test
    fun `an accepted hold settles to candle-light so its flares have room to rise`() {
        val rest = InkEngine.glintResonance(true, 0f, 1f, depth = 1f).light
        assertEquals(-InkEngine.GLINT_RESONANCE_REST, rest, 1e-4f)
        val candle = glintPulseColor(gold, rest).luminance()
        // Gold to white alone is a swing the eye does not catch.
        assertTrue(Color.White.luminance() - gold.luminance() < 0.2f)
        assertTrue("candle-light $candle", candle in 0.3f..0.5f)
        assertTrue(glintEmber(gold).luminance() < candle - 0.2f)
        // It eases down with the detector's swell rather than stepping.
        val entering = (1..5).map { InkEngine.glintResonance(true, 0f, it * 0.05f, depth = 1f).light }
        assertEquals(listOf(-0.09f, -0.18f, -0.27f, -0.36f, -0.45f), entering.map { Math.round(it * 100) / 100f })
    }

    @Test
    fun `a crest flares to white and a trough sinks to a warm ember`() {
        for (base in listOf(gold, repeat)) {
            val lights = listOf(-1f, -0.5f, 0f, 0.5f, 1f).map {
                InkEngine.glintResonance(true, it, 1f, depth = 1f).light
            }
            assertEquals(-1f, lights.first(), 1e-4f)
            assertEquals(1f, lights.last(), 1e-4f)
            val colours = lights.map { glintPulseColor(base, it) }
            assertEquals(glintEmber(base), colours[0])
            assertEquals(Color.White, colours[4])
            assertTrue(colours.zipWithNext().all { (a, b) -> a.luminance() < b.luminance() })
            // An ember is dimmer and redder, never grey.
            val ember = glintEmber(base)
            assertTrue(ember.luminance() < base.luminance() * 0.3f)
            assertTrue(ember.blue / ember.red < base.blue / base.red)
        }
    }

    @Test
    fun `the flame is the voice's a quarter of the way into the detector's swell`() {
        assertEquals(0f, InkEngine.glintResonance(true, 1f, 0f).light, 0f)
        assertEquals(0.4f, InkEngine.glintResonance(true, 1f, 0.1f, depth = 1f).light, 1e-4f)
        assertEquals(1f, InkEngine.glintResonance(true, 1f, 0.25f, depth = 1f).light, 1e-4f)
        assertEquals(-1f, InkEngine.glintResonance(true, -1f, 0.6f, depth = 1f).light, 1e-4f)
        assertEquals(0.5f, InkEngine.glintResonance(true, 1f, 1f, depth = 0.5f).light, 1e-4f)
        assertEquals(0f, InkEngine.glintResonance(false, 1f, 1f).light, 0f)
        assertEquals(0f, InkEngine.glintResonance(true, 1f, 1f, enabled = false).light, 0f)
    }

    @Test
    fun `the flame catches a rising pulse within a frame and cools on the way down`() {
        val flame = GlintFlame()
        assertEquals(0f, flame.next(0f, 0L), 0f)
        // One 60 Hz frame takes rest to a full flare; one 120 Hz frame, most of it.
        assertEquals(1f, flame.next(1f, 16_666_667L), 1e-4f)
        val fast = GlintFlame().also { it.next(0f, 0L) }
        assertEquals(1f, fast.next(1f, 8_333_333L), 1e-4f)
        // Falling, it cools: still plainly lit after a frame, gone after a few.
        val cooling = flame.next(0f, 33_333_333L)
        assertTrue("one frame after the crest: $cooling", cooling in 0.4f..0.6f)
        var late = cooling
        for (i in 3..9) late = flame.next(0f, i * 16_666_667L)
        assertTrue("after 150 ms: $late", late < 0.05f)
        // And it sinks into an ember the same way.
        val sinking = GlintFlame().also { it.next(0f, 0L) }
        assertTrue(sinking.next(-1f, 16_666_667L) in -0.6f..-0.4f)
    }

    @Test
    fun `the flame travels the same at either display refresh rate`() {
        fun cooled(fps: Int): Float {
            val flame = GlintFlame().also { it.next(0f, 0L); it.next(1f, 50_000_000L) }
            return (1..fps / 10).map { flame.next(0f, 50_000_000L + it * 1_000_000_000L / fps) }.last()
        }
        assertEquals(cooled(60), cooled(120), 1e-3f)
    }

    @Test
    fun `fast soft pulses flicker from ember to white on the night page`() {
        val night = Color(0xFFE8E2D5) // the bright ink the tint must cover
        for (fps in listOf(60, 120)) for (hz in listOf(4, 6, 10)) {
            val lit = glows(hz, fps, gain = 0.25f).drop(fps / 4).map { glow ->
                val tint = glintLitAlpha(InkEngine.Tuning().glintTintAlpha, glow)
                glintPulseColor(gold, glow).copy(alpha = tint).compositeOver(night).luminance()
            }
            assertTrue("$hz Hz at $fps fps needs a white flare (${lit.max()})", lit.max() > 0.95f)
            assertTrue("$hz Hz at $fps fps needs a dark ember (${lit.min()})", lit.min() < 0.3f)
        }
    }

    @Test
    fun `the halo swings harder than the glyphs`() {
        assertEquals(1f, glintHaloAlpha(0.78f, 1f), 0f)
        assertEquals(0.078f, glintHaloAlpha(0.78f, -1f), 1e-4f)
        assertEquals(1f, glintLitAlpha(0.88f, 1f), 0f)
        assertEquals(1f, glintLitAlpha(0.88f, -1f), 0f)
    }

    @Test
    fun `brightness scales the pulse's reach and never where it rests`() {
        for (base in listOf(gold, repeat)) {
            assertEquals(Color.White, glintPulseColor(base, 0.5f, brightness = 2f))
            assertEquals(glintEmber(base), glintPulseColor(base, -0.5f, brightness = 2f))
            assertEquals(base, glintPulseColor(base, 0f, brightness = 2f))
            assertEquals(base, glintPulseColor(base, 1f, brightness = 0f))
            assertEquals(base, glintPulseColor(base, -1f, brightness = 0f))
        }
        assertEquals(0.4f, glintLitAlpha(0.4f, 1f, brightness = 0f), 0f)
        assertEquals(0.4f, glintHaloAlpha(0.4f, -1f, brightness = 0f), 0f)
    }

    @Test
    fun `a rejected hold and a fading one never pop`() {
        assertEquals(gold, glintPulseColor(gold, InkEngine.glintResonance(false, 1f, 1f).light))
        assertEquals(gold, glintPulseColor(gold, InkEngine.glintResonance(true, 1f, 0f).light))
        val fading = glintPulseColor(gold, InkEngine.glintResonance(true, 1f, 0.05f, depth = 1f).light)
        assertTrue(fading.blue > gold.blue && fading.blue < 1f)
    }
}
