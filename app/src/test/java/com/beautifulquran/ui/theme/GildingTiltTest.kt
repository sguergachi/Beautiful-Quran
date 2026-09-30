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

class GildingGlideTest {
    @Test
    fun glidesToTheAimFrameByFrameWithoutOvershooting() {
        val tilt = GildingTilt()
        tilt.aimAt(0.9f)
        var last = tilt.value
        var frames = 0
        while (!tilt.glide(1f / 60f)) {
            assertTrue(tilt.value > last && tilt.value < 0.9f)
            last = tilt.value
            frames++
            assertTrue("never arrives", frames < 120)
        }
        assertEquals(0.9f, tilt.value, 0f)
        // ~0.14 s constant: settles in well under a second, but not in one frame.
        assertTrue(frames in 10..60)
    }

    @Test
    fun aFirstSampleSnapsAndATinyOneIsIgnored() {
        val tilt = GildingTilt()
        tilt.snapTo(0.8f)
        assertEquals(0.8f, tilt.value, 0f)
        tilt.aimAt(0.8f + 0.001f)
        assertEquals(0.8f, tilt.aim.floatValue, 0f)
    }
}

class ScreenRollTest {
    @Test
    fun rollFollowsTheScreensWidthInEveryRotation() {
        // A phone tipped so the screen's right edge is lower reads the same roll
        // however the screen is turned.
        assertEquals(3f, screenRoll(3f, 9f, android.view.Surface.ROTATION_0), 0f)
        assertEquals(9f, screenRoll(3f, 9f, android.view.Surface.ROTATION_90), 0f)
        assertEquals(-3f, screenRoll(3f, 9f, android.view.Surface.ROTATION_180), 0f)
        assertEquals(-9f, screenRoll(3f, 9f, android.view.Surface.ROTATION_270), 0f)
    }
}
