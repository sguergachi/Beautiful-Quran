package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page dial's laws, away from Compose. What a dp of finger buys, how fast
 * that answer changes, and what the comb is therefore allowed to show.
 */
class MushafPageDialTest {

    @Test
    fun `a resting finger buys the finest gain`() {
        assertEquals(MUSHAF_DIAL_FINE_GAIN, mushafDialGain(0f), 1e-5f)
        // Anything under the slow edge is still the floor: a hand that has
        // stopped must not creep.
        assertEquals(MUSHAF_DIAL_FINE_GAIN, mushafDialGain(MUSHAF_DIAL_SLOW_DP_S), 1e-5f)
        assertEquals(MUSHAF_DIAL_FINE_GAIN, mushafDialGain(-10f), 1e-5f)
    }

    @Test
    fun `a fast finger buys the coarsest gain, and no more`() {
        assertEquals(MUSHAF_DIAL_COARSE_GAIN, mushafDialGain(MUSHAF_DIAL_FAST_DP_S), 1e-4f)
        assertEquals(MUSHAF_DIAL_COARSE_GAIN, mushafDialGain(9_000f), 1e-4f)
    }

    @Test
    fun `gain never falls as the finger speeds up`() {
        var previous = 0f
        var speed = 0f
        while (speed <= 2_000f) {
            val gain = mushafDialGain(speed)
            assertTrue("gain fell at $speed dp/s", gain >= previous - 1e-6f)
            previous = gain
            speed += 5f
        }
    }

    @Test
    fun `the ramp is geometric, so its middle is the geometric mean`() {
        // Smoothstep is 0.5 at the midpoint of its edges; in log space that
        // puts the gain exactly halfway between the two ends. A linear ramp
        // would sit at 2.04 pages per dp here — most of the travel already
        // spent — and the dial would read as two settings with a cliff.
        val mid = (MUSHAF_DIAL_SLOW_DP_S + MUSHAF_DIAL_FAST_DP_S) / 2f
        val geometric = Math.sqrt(
            (MUSHAF_DIAL_FINE_GAIN * MUSHAF_DIAL_COARSE_GAIN).toDouble(),
        ).toFloat()
        assertEquals(geometric, mushafDialGain(mid), 1e-4f)
    }

    @Test
    fun `one stroke crosses the whole book`() {
        // A phone's width less the leaf's two 14dp margins. At the coarse end
        // that stroke must reach page 604 from page 1 with room to spare, or
        // the dial cannot get the reader to the back of the mushaf at all.
        val strokeDp = 360f - 28f
        assertTrue(MUSHAF_DIAL_COARSE_GAIN * strokeDp >= 604f)
    }

    @Test
    fun `the finest gain spends fourteen dp on a leaf`() {
        assertEquals(14f, mushafDialPitchDp(MUSHAF_DIAL_FINE_GAIN), 1e-3f)
    }

    @Test
    fun `the speed estimate converges on what the finger is doing`() {
        var speed = 0f
        repeat(40) { speed = mushafDialSpeed(speed, 800f, 0.016f) }
        assertEquals(800f, speed, 5f)
        // And lets go of it again when the hand slows.
        repeat(40) { speed = mushafDialSpeed(speed, 0f, 0.016f) }
        assertEquals(0f, speed, 5f)
    }

    @Test
    fun `the speed estimate weighs a sample by its own elapsed time`() {
        // Frame-rate independence: one 32ms sample must move the estimate as
        // far as two 16ms samples of the same speed, or the gain would depend
        // on how often the pointer happened to report.
        val oneStep = mushafDialSpeed(0f, 600f, 0.032f)
        var twoSteps = mushafDialSpeed(0f, 600f, 0.016f)
        twoSteps = mushafDialSpeed(twoSteps, 600f, 0.016f)
        assertEquals(oneStep, twoSteps, 0.01f)
        // A sample with no time behind it changes nothing.
        assertEquals(123f, mushafDialSpeed(123f, 999f, 0f), 1e-5f)
    }

    @Test
    fun `the lens is full under the thumb and gone at its edge`() {
        assertEquals(1f, mushafDialLensEnvelope(0f, 64f), 1e-5f)
        assertEquals(0f, mushafDialLensEnvelope(64f, 64f), 1e-5f)
        assertEquals(0f, mushafDialLensEnvelope(200f, 64f), 1e-5f)
        // Symmetric, and falling the whole way: no seam either side.
        assertEquals(
            mushafDialLensEnvelope(20f, 64f),
            mushafDialLensEnvelope(-20f, 64f),
            1e-5f,
        )
        var previous = 1f
        var d = 0f
        while (d <= 64f) {
            val env = mushafDialLensEnvelope(d, 64f)
            assertTrue("envelope rose at $d dp", env <= previous + 1e-6f)
            previous = env
            d += 1f
        }
    }

    @Test
    fun `the comb closes up once a leaf is under a pixel`() {
        assertTrue(mushafDialTicksVisible(mushafDialPitchDp(MUSHAF_DIAL_FINE_GAIN)))
        assertFalse(mushafDialTicksVisible(mushafDialPitchDp(MUSHAF_DIAL_COARSE_GAIN)))
        assertTrue(mushafDialTicksVisible(MUSHAF_DIAL_MIN_PITCH_DP))
        assertFalse(mushafDialTicksVisible(MUSHAF_DIAL_MIN_PITCH_DP - 0.01f))
    }

    @Test
    fun `the haptic stride keeps one cadence whatever the gain`() {
        // Every leaf while they are far apart...
        assertEquals(1, mushafDialHapticStride(14f))
        assertEquals(1, mushafDialHapticStride(MUSHAF_DIAL_HAPTIC_PITCH_DP))
        // ...and widening as they close, so the hand feels ~4dp per tick
        // instead of a buzz.
        assertEquals(2, mushafDialHapticStride(2f))
        assertEquals(16, mushafDialHapticStride(0.25f))
        var pitch = 0.05f
        var previous = Int.MAX_VALUE
        while (pitch <= 20f) {
            val stride = mushafDialHapticStride(pitch)
            assertTrue("stride grew at pitch $pitch", stride <= previous)
            assertTrue(stride >= 1)
            previous = stride
            pitch += 0.05f
        }
    }

    @Test
    fun `the book runs right to left, end to end`() {
        assertEquals(0f, mushafDialFraction(1f, 604), 1e-6f)
        assertEquals(1f, mushafDialFraction(604f, 604), 1e-6f)
        assertEquals(0.5f, mushafDialFraction(302.5f, 604), 1e-4f)
        // Rubber band overshoot is clamped before it reaches the paper.
        assertEquals(0f, mushafDialFraction(-4f, 604), 1e-6f)
        assertEquals(1f, mushafDialFraction(700f, 604), 1e-6f)
        // A one-leaf book has nowhere to go.
        assertEquals(0f, mushafDialFraction(1f, 1), 1e-6f)
    }

    @Test
    fun `the dial bands past either end of the book rather than stopping dead`() {
        assertEquals(302f, rubberBandDialPosition(302f, 1f, 604f), 1e-4f)
        assertTrue(rubberBandDialPosition(-9f, 1f, 604f) < 1f)
        assertTrue(rubberBandDialPosition(-9f, 1f, 604f) > -9f)
        assertTrue(rubberBandDialPosition(700f, 1f, 604f) > 604f)
        assertTrue(rubberBandDialPosition(700f, 1f, 604f) < 700f)
    }

    @Test
    fun `a comb mark stands at the gain's own pitch, not the rule's`() {
        // 14 dp per leaf at 2x density is 28 px per leaf on screen, whatever
        // the rule's width happens to be.
        val pitchPx = mushafDialPitchDp(MUSHAF_DIAL_FINE_GAIN) * 2f
        val here = mushafDialTickX(thumbXPx = 500f, page = 100, at = 100f, pitchPx = pitchPx)
        val next = mushafDialTickX(thumbXPx = 500f, page = 101, at = 100f, pitchPx = pitchPx)
        assertEquals(500f, here, 1e-3f)
        assertEquals(pitchPx, here - next, 1e-3f)
    }

    @Test
    fun `later leaves stand to the left, as the book runs`() {
        val ahead = mushafDialTickX(400f, page = 300, at = 290f, pitchPx = 10f)
        val behind = mushafDialTickX(400f, page = 280, at = 290f, pitchPx = 10f)
        assertTrue("later leaf must sit left of the thumb", ahead < 400f)
        assertTrue("earlier leaf must sit right of the thumb", behind > 400f)
    }

    @Test
    fun `a fractional leaf slides the comb rather than jumping it`() {
        val pitchPx = 20f
        val at = mushafDialTickX(500f, page = 100, at = 100f, pitchPx = pitchPx)
        val halfway = mushafDialTickX(500f, page = 100, at = 100.5f, pitchPx = pitchPx)
        assertEquals(pitchPx / 2f, halfway - at, 1e-3f)
    }

    @Test
    fun `the comb spans the lens at fine gain and closes at coarse`() {
        // Four leaves either side at the rail's own spacing: few enough to
        // count, wide enough to aim at. At coarse gain there is nothing
        // honest to draw and the rule stands alone.
        val fine = mushafDialPitchDp(MUSHAF_DIAL_FINE_GAIN)
        assertTrue(mushafDialTicksVisible(fine))
        assertEquals(4, (64f / fine).toInt())
        assertTrue(!mushafDialTicksVisible(mushafDialPitchDp(MUSHAF_DIAL_COARSE_GAIN)))
    }

    @Test
    fun `the fourth tooth out is still legible`() {
        // The comb has to read as a row of markers, not as three marks beside
        // a hairline: at the rail's own 14 dp pitch the fourth leaf either
        // side must keep a tenth of its height, which a squared cosine does
        // not (it leaves 1%).
        val pitch = mushafDialPitchDp(MUSHAF_DIAL_FINE_GAIN)
        val fourth = mushafDialLensEnvelope(pitch * 4f, 64f)
        assertTrue("fourth tooth stood at $fourth", fourth > 0.09f)
    }

    @Test
    fun `the thumb's travel stops clear of both screen edges`() {
        // Either end of the rule lies inside the system's back-gesture strip.
        // The first and last leaves must still be grabbable.
        val width = 1000f
        val inset = 40f
        assertEquals(inset, mushafDialTrackX(0f, width, inset), 1e-3f)
        assertEquals(width - inset, mushafDialTrackX(1f, width, inset), 1e-3f)
        assertEquals(width / 2f, mushafDialTrackX(0.5f, width, inset), 1e-3f)
    }

    @Test
    fun `the track survives a rule narrower than its own insets`() {
        val x = mushafDialTrackX(0.5f, widthPx = 20f, insetPx = 40f)
        assertEquals(10f, x, 1e-3f)
    }

    @Test
    fun `the track clamps a fraction that has run past either end`() {
        assertEquals(30f, mushafDialTrackX(-0.4f, 1000f, 30f), 1e-3f)
        assertEquals(970f, mushafDialTrackX(1.6f, 1000f, 30f), 1e-3f)
    }
}
