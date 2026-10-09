package com.beautifulquran.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.beautifulquran.ui.reader.InkEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
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
        assertEquals(0.6f, glintGlowAlpha(0.6f, 1f, shipped.tarjiGlowGain), 1e-6f)
        // Rest sits a breath under the colour: the headroom a crest rises into.
        for (base in listOf(gold, repeat)) {
            val rest = glintLightColor(base, 1f).luminance() / base.luminance()
            assertEquals(GLINT_REST_LIGHT, rest, 0.01f)
        }
    }

    @Test
    fun `the light lifts a little with the voice and dips less`() {
        // Full scale, which the long smoothing never lets a real pulse reach.
        assertEquals(1.3f, level(1f), 1e-4f)
        assertEquals(0.8899f, level(-1f), 1e-4f)
        assertTrue(shipped.tarjiLightFall < shipped.tarjiLightRise)
        // The glyphs stop at their own colour: the rest of a crest is the glow's.
        val rest = glintLightColor(gold, 1f).luminance()
        val up = glintLightColor(gold, level(1f)).luminance() / rest
        assertEquals(1f / shipped.glintRestLight, up, 0.02f)
        assertEquals(1.15f, level(0.5f), 1e-4f)
        // A reciter's brightness scales the swing and nothing else, and past
        // shipped it counts half: 200 % is one and a half times, not twice.
        assertEquals(1.45f, level(1f, brightness = 2f), 1e-4f)
        assertEquals(1.15f, level(1f, brightness = 0.5f), 1e-4f)
    }

    @Test
    fun `brightness changes and the hue does not`() {
        for (base in listOf(gold, repeat)) {
            val rest = chroma(base)
            // At every level, past the headroom too: no channel is scaled
            // beyond the colour itself, so a crest never whitens.
            for (l in listOf(0.84f, 0.92f, 0.97f, 1f, 1.04f, 1.06f, 1.28f)) {
                val c = chroma(glintLightColor(base, l))
                assertEquals(rest.first, c.first, 2e-3f)
                assertEquals(rest.second, c.second, 2e-3f)
            }
            val levels = listOf(0.84f, 0.92f, 1f, 1.06f).map { glintLightColor(base, it).luminance() }
            assertTrue(levels.zipWithNext().all { (a, b) -> a < b })
            assertEquals(base, glintLightColor(base, 1.28f))
        }
    }

    /** A glow layer's light: its colour's, through its alpha at display gamma. */
    private fun glowLight(resting: Float, l: Float, gain: Float = shipped.tarjiGlowGain): Float =
        glintLetterLight(l) / GLINT_REST_LIGHT * (glintGlowAlpha(resting, l, gain) / resting).pow(2.2f)

    @Test
    fun `the glow swings further than the glyphs, by the stated amount of light`() {
        // The glow's light is `gain` times the glyphs' swing — light, not
        // alpha: scaling alpha directly made a "ten percent" pulse of sixty.
        val gain = shipped.tarjiGlowGain
        for (resting in listOf(0.25f, 0.4f)) for (l in listOf(level(0.2f), level(-0.2f), level(1f), level(-1f))) {
            assertEquals(1f + gain * (l - 1f), glowLight(resting, l), 0.01f)
        }
        // Past the glyphs' headroom the glow still carries the crest.
        assertEquals(1f + gain * 0.45f, glowLight(0.25f, level(1f, brightness = 2f)), 0.02f)
        // It can run out of headroom but never past it, and never below dark.
        assertEquals(1f, glintGlowAlpha(0.9f, 1.28f, 6f), 0f)
        assertEquals(0f, glintGlowAlpha(0.5f, 0.5f, 6f), 0f)
        assertEquals(0f, glintGlowAlpha(0f, 1.28f, 6f), 0f)
    }

    @Test
    fun `the veil's grain breaks its steps without moving its light`() {
        // A slow ramp like the veil's own: long runs of one value are the
        // contours a dark screen shows.
        val ramp = ByteArray(4096) { (it / 512).toByte() }
        val grained = ramp.copyOf().also { ditherAlphaMask(it, 4f) }
        // Where there is no light there is still none: the glow does not grow.
        assertTrue((0 until 512).all { grained[it].toInt() == 0 })
        for (step in 1 until 8) {
            val run = grained.slice(step * 512 until (step + 1) * 512).map { it.toInt() and 0xFF }
            assertTrue("step $step is still flat", run.distinct().size >= 3)
            assertEquals(step.toDouble(), run.average(), 0.25)
            assertTrue(run.all { abs(it - step) <= minOf(4, step) })
        }
        // The same grain every time, so a cached mask does not shimmer.
        assertTrue(grained.contentEquals(ramp.copyOf().also { ditherAlphaMask(it, 4f) }))
        assertTrue(ramp.contentEquals(ramp.copyOf().also { ditherAlphaMask(it, 0f) }))
    }

    @Test
    fun `a lower rest leaves the glyphs more room to rise`() {
        val roomy = glintLightColor(gold, 1.2f, rest = 0.8f).luminance() / glintLightColor(gold, 1f, rest = 0.8f).luminance()
        val tight = glintLightColor(gold, 1.2f, rest = 0.96f).luminance() / glintLightColor(gold, 1f, rest = 0.96f).luminance()
        assertEquals(1.2f, roomy, 0.02f)
        assertEquals(1f / 0.96f, tight, 0.02f)
        // Whatever the glyphs cannot carry, the glow does: its light is the same.
        fun glow(rest: Float) = glintLetterLight(1.2f, rest) / rest * (glintGlowAlpha(0.4f, 1.2f, 2f, rest) / 0.4f).pow(2.2f)
        assertEquals(glow(0.8f), glow(0.96f), 2e-3f)
        assertEquals(gold, glintLightColor(gold, 1f, rest = 1f))
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
                glintLightColor(gold, level(light.next(pulse.light, frame * 1_000_000_000L / fps,
                    shipped.tarjiLightSmoothMs))).luminance()
            }.drop(fps / 4)
            val step = lit.zipWithNext().maxOf { (a, b) -> abs(a - b) }
            assertTrue("$hz Hz at $fps fps steps $step a frame", step < 0.04f)
            assertTrue("$hz Hz at $fps fps still moves", lit.max() - lit.min() > 0.005f)
        }
    }

    @Test
    fun `the read-ahead is what the smoothing delays a pulse by, not its time constant`() {
        // Measured: where the smoothed crest falls behind the voice's.
        fun measuredLagMs(smoothMs: Float, hz: Double): Double {
            val light = GlintLight()
            var best = -2f
            var bestAt = 0.0
            val periodMs = 1000.0 / hz
            for (step in 0..(periodMs * 12).toInt() * 10) {
                val ms = step / 10.0
                val out = light.next(sin(2 * PI * hz * ms / 1000).toFloat(), (ms * 1_000_000).toLong(), smoothMs)
                if (ms >= periodMs * 11 && out > best) { best = out; bestAt = ms }
            }
            return (bestAt - periodMs * 11.25 + periodMs) % periodMs
        }
        for (smooth in listOf(60f, 139f)) for (hz in listOf(4.0, 6.0, 9.0)) {
            assertEquals("$smooth ms at $hz Hz", measuredLagMs(smooth, hz), glintLightLagMs(smooth, hz.toFloat()).toDouble(), 1.5)
        }
        // 139 ms of smoothing sets a 6 Hz pulse back 37 ms: reading 139 ahead
        // put the light a tenth of a second before the voice.
        assertEquals(36.6f, glintLightLagMs(139f, 6f), 0.5f)
        // A slow drift is delayed by the whole constant; no smoothing, by nothing.
        assertEquals(139f, glintLightLagMs(139f, 0.01f), 0.5f)
        assertEquals(0f, glintLightLagMs(0f, 6f), 0f)
        // Before a rate is measured, a typical one is assumed.
        assertEquals(glintLightLagMs(139f, 6f), glintLightLagMs(139f, 0f), 0f)
    }

    @Test
    fun `smoothing compensation keeps the crest on the voice at every playback speed`() {
        for (speed in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)) for (rate in listOf(2.7f, 4f, 6f, 9f)) {
            val light = GlintLight()
            val lead = glintLightLagMs(139f, rate, speed)
            val periodMs = 1000.0 / (rate * speed)
            var best = -2f
            var bestAt = 0.0
            for (step in 0..(periodMs * 120).toInt()) {
                val ms = step / 10.0
                val pulse = sin(2 * PI * rate * (speed * ms + lead) / 1000).toFloat()
                val out = light.next(pulse, (ms * 1_000_000).toLong(), 139f)
                if (ms >= periodMs * 11 && out > best) { best = out; bestAt = ms }
            }
            assertEquals("$rate Hz at $speed x", periodMs * 11.25, bestAt, 0.2)
        }
        assertEquals(0f, glintLightLagMs(139f, 6f, 0f), 0f)
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
