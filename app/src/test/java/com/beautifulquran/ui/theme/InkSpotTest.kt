package com.beautifulquran.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class InkSpotTest {

    @Test
    fun `outline is a circular drop`() {
        val outline = inkSpotOutline(width = 80f, height = 80f, seed = 2, pad = 6f)
        assertEquals(16, outline.size)
        val cx = outline.map { it.x }.average()
        val cy = outline.map { it.y }.average()
        val radii = outline.map { hypot(it.x - cx, it.y - cy) }
        val spread = (radii.max() - radii.min()) / radii.average()
        assertTrue("ink drop must stay circular, spread=$spread", spread < 0.12)
    }

    @Test
    fun `row indexes keep a stable unique stain per slot`() {
        val a = inkSpotOutline(80f, 64f, seed = 0, pad = 6f)
        val b = inkSpotOutline(80f, 64f, seed = 1, pad = 6f)
        val again = inkSpotOutline(80f, 64f, seed = 0, pad = 6f)
        assertEquals(a, again)
        val drift = a.zip(b).maxOf { (p, q) -> hypot(p.x - q.x, p.y - q.y) }
        assertTrue(drift > 2f)
    }

    @Test
    fun `different seeds splash differently`() {
        val hold = inkSpotOutline(80f, 64f, seed = 1, pad = 6f)
        val shape = inkSpotOutline(80f, 64f, seed = 2, pad = 6f)
        val drift = hold.zip(shape).maxOf { (a, b) -> hypot(a.x - b.x, a.y - b.y) }
        assertTrue("seeds must not stamp the same blot, drift=$drift", drift > 0.8f)
    }

    @Test
    fun `spot shader is the guide pigment pooled in a drop`() {
        assertTrue(VellumSpotShader.contains(VellumPigmentFunctions))
        assertTrue(VellumSpotShader.contains("brushedPigment"))
        assertTrue(VellumSpotShader.contains("vellumCoverage"))
        assertTrue(VellumSpotShader.contains("uniform float seed"))
        assertTrue(VellumSpotShader.contains("float soak"))
        assertTrue(VellumSpotShader.contains("float halo"))
        assertTrue(VellumSpotShader.contains("rimWobble"))
        assertTrue(VellumSpotShader.contains("uniform float fill"))
    }

    @Test
    fun `verse soak is a fibre-warped rounded rectangle not an oval`() {
        assertTrue(VellumSpotShader.contains("abs(p) - halfSize"))
        assertTrue(VellumSpotShader.contains("min(max(q.x, q.y), 0.0)"))
        assertTrue(VellumSpotShader.contains("1.0 / (1.0 + exp(sdf / diffusion))"))
        assertTrue(VellumSpotShader.contains("rimGate"))
        assertTrue(VellumSpotShader.contains("sourcePool * (1.0 - fill)"))
        assertTrue(VellumSpotShader.contains("uniform float rimInset"))
        assertFalse(
            "a proportional rim inset grows with the verse's height",
            VellumSpotShader.contains("0.5 * res * mix("),
        )
        assertFalse(VellumSpotShader.contains("midpoint - r + warp"))
        assertFalse(
            "verse soak opacity must track progress, not snap on in the first fifth",
            VellumSpotShader.contains("progress * 5.0"),
        )
    }

    @Test
    fun `verse soak reaches the whole block at any text size`() {
        // Same verse, set small and set large: the block grows taller, and
        // the wash must still reach within one fixed rim of its edges.
        val rim = 25f
        val short = verseSoakHalfSize(width = 1000f, height = 400f, rimInset = rim)
        val tall = verseSoakHalfSize(width = 1000f, height = 2600f, rimInset = rim)
        assertEquals(400f / 2f - rim, short.height, 0.01f)
        assertEquals(2600f / 2f - rim, tall.height, 0.01f)
        assertEquals(1000f / 2f - rim, tall.width, 0.01f)
        // The uncovered band at the top is the same either way — it does
        // not scale, which is the whole point.
        val shortGap = 400f / 2f - short.height
        val tallGap = 2600f / 2f - tall.height
        assertEquals(shortGap, tallGap, 0.01f)
    }

    @Test
    fun `a one-line verse cannot invert under the rim inset`() {
        // Rim wider than the block itself: keep at least half, never a
        // negative or zero-height wash.
        val tiny = verseSoakHalfSize(width = 300f, height = 40f, rimInset = 60f)
        assertEquals(20f * 0.5f, tiny.height, 0.01f)
        assertTrue(tiny.width > 0f)
    }

    @Test
    fun `ink starts at the finger, not the middle of the verse`() {
        val size = Size(1000f, 2600f)
        val tap = Offset(880f, 210f)
        assertEquals(tap, verseSoakSource(tap, size))
        // No tap recorded (restored selection): fall back to the middle.
        assertEquals(
            Offset(500f, 1300f),
            verseSoakSource(Offset.Unspecified, size),
        )
        // A tap that arrived slightly outside the block stays on it.
        assertEquals(Offset(1000f, 0f), verseSoakSource(Offset(1400f, -80f), size))
    }

    @Test
    fun `the front clears the farthest corner wherever the finger lands`() {
        val size = Size(1000f, 2600f)
        val half = verseSoakHalfSize(size.width, size.height, rimInset = 25f)
        val center = Offset(size.width * 0.5f, size.height * 0.5f)
        // Corner taps are the hard case: the opposite corner is farthest.
        val taps = listOf(
            Offset(0f, 0f),
            Offset(size.width, 0f),
            Offset(0f, size.height),
            Offset(size.width, size.height),
            center,
        )
        for (tap in taps) {
            val source = verseSoakSource(tap, size)
            val reach = verseSoakReach(half, source, center)
            val feather = verseSoakFeather(reach)
            val front = verseSoakFront(reach, progress = 1f)
            // The front wanders by up to 0.8 of a feather against the
            // direction of travel; even then it must pass the corner.
            assertTrue(
                "tap $tap leaves the far corner dry: front=$front reach=$reach",
                front - 0.8f * feather >= reach,
            )
            // Every corner of the soak rect is inside that front.
            val corners = listOf(
                Offset(center.x - half.width, center.y - half.height),
                Offset(center.x + half.width, center.y - half.height),
                Offset(center.x - half.width, center.y + half.height),
                Offset(center.x + half.width, center.y + half.height),
            )
            for (corner in corners) {
                val travelled = hypot(corner.x - source.x, corner.y - source.y)
                assertTrue("corner $corner dry from $tap", travelled <= front)
            }
        }
    }

    @Test
    fun `the front runs out from the finger over the animation`() {
        val size = Size(1000f, 2600f)
        val half = verseSoakHalfSize(size.width, size.height, rimInset = 25f)
        val center = Offset(size.width * 0.5f, size.height * 0.5f)
        val source = verseSoakSource(Offset(880f, 210f), size)
        val reach = verseSoakReach(half, source, center)
        assertEquals(0f, verseSoakFront(reach, progress = 0f), 0.01f)
        val quarter = verseSoakFront(reach, progress = 0.25f)
        val half2 = verseSoakFront(reach, progress = 0.5f)
        val full = verseSoakFront(reach, progress = 1f)
        assertTrue(quarter < half2 && half2 < full)
        // Early on, the far side of a long verse is still dry — that is
        // the spread reading as a spread and not as a fade.
        val farCorner = Offset(center.x - half.width, center.y + half.height)
        assertTrue(hypot(farCorner.x - source.x, farCorner.y - source.y) > quarter)
    }

    @Test
    fun `the soak silhouette does not scale with progress`() {
        // Only the front animates. A rectangle growing from the middle is
        // the zoom this replaced.
        assertTrue(VellumSpotShader.contains("float2 halfSize = max(fullHalf"))
        assertFalse(VellumSpotShader.contains("halfSize = mix("))
        assertTrue(VellumSpotShader.contains("uniform float2 spreadSource"))
        assertTrue(VellumSpotShader.contains("float travelled = length(fromSource2)"))
        assertTrue(VellumSpotShader.contains("density * wet"))
    }

    @Test
    fun `spot pigment interpolates with progress so deselect fades out`() {
        assertEquals(0f, inkSpotAppear(0f), 0f)
        assertEquals(0.5f, inkSpotAppear(0.5f), 0f)
        assertEquals(1f, inkSpotAppear(1f), 0f)
        assertTrue(VellumSpotShader.contains("appear = progress"))
    }
}
