package com.beautifulquran.ui.theme

import androidx.compose.ui.geometry.Offset
import com.beautifulquran.ui.entrance.ScreenCornerRadiiPx
import com.beautifulquran.ui.entrance.coverFrameGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * The border frieze carries its pattern round each corner arc into the corner
 * seal. These pin the run to the seal at both ends and to the band's own
 * centreline everywhere between, so the pattern can never stop short of a
 * seal or cut a chord across a corner.
 */
class BandRunTest {

    private val density = 3f
    private val width = 1080f
    private val height = 2424f

    private fun dist(a: Offset, b: Offset) = hypot(a.x - b.x, a.y - b.y)

    @Test
    fun `run starts and ends on the corner seals`() {
        for (radius in listOf(0f, 30f, 90f, 150f, 220f)) {
            val geometry = coverFrameGeometry(ScreenCornerRadiiPx(radius, radius, radius, radius), density)
            val c = (geometry.outerInsetPx + geometry.innerInsetPx) / 2f
            val r = geometry.outerCorners.topLeft + geometry.outerInsetPx
            val run = BandRun(width, c, r, r)
            val seal = sealCenterPx(geometry, r)
            val start = run.point(0f, 0f)
            val end = run.point(run.length, 0f)
            assertEquals("radius $radius start x", seal, start.x, 0.01f)
            assertEquals("radius $radius start y", seal, start.y, 0.01f)
            assertEquals("radius $radius end x", width - seal, end.x, 0.01f)
            assertEquals("radius $radius end y", seal, end.y, 0.01f)
        }
    }

    @Test
    fun `run is continuous with unit speed across arc and straight joins`() {
        val geometry = coverFrameGeometry(ScreenCornerRadiiPx(150f, 150f, 150f, 150f), density)
        val c = (geometry.outerInsetPx + geometry.innerInsetPx) / 2f
        val r = geometry.outerCorners.topLeft + geometry.outerInsetPx
        val run = BandRun(width, c, r, r)
        val step = 0.5f
        var u = 0f
        var prev = run.point(0f, 0f)
        while (u < run.length) {
            val advance = minOf(step, run.length - u)
            u += advance
            val p = run.point(u, 0f)
            // Chord of a unit-speed centreline: never longer than the arc
            // length walked, never a visible jump (arc chords shorten slightly).
            assertTrue("jump at u=$u", dist(prev, p) in (advance * 0.97f)..(advance + 0.001f))
            prev = p
        }
    }

    @Test
    fun `inward offset stays at that distance from the centreline round the corner`() {
        val geometry = coverFrameGeometry(ScreenCornerRadiiPx(150f, 150f, 150f, 150f), density)
        val c = (geometry.outerInsetPx + geometry.innerInsetPx) / 2f
        val r = geometry.outerCorners.topLeft + geometry.outerInsetPx
        val run = BandRun(width, c, r, r)
        val half = 9f
        var u = 0f
        while (u <= run.length) {
            val mid = run.point(u, 0f)
            for (side in floatArrayOf(-half, half)) {
                assertEquals("u=$u offset $side", half, dist(mid, run.point(u, side)), 0.01f)
            }
            u += 3f
        }
    }

    @Test
    fun `different radii at the two ends each meet their own seal`() {
        val geometry = coverFrameGeometry(ScreenCornerRadiiPx(60f, 200f, 0f, 0f), density)
        val c = (geometry.outerInsetPx + geometry.innerInsetPx) / 2f
        val rl = geometry.outerCorners.topLeft + geometry.outerInsetPx
        val rr = geometry.outerCorners.topRight + geometry.outerInsetPx
        val run = BandRun(width, c, rl, rr)
        assertEquals(sealCenterPx(geometry, rl), run.point(0f, 0f).x, 0.01f)
        assertEquals(width - sealCenterPx(geometry, rr), run.point(run.length, 0f).x, 0.01f)
    }
}
