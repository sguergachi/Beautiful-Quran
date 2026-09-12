package com.beautifulquran.ui.theme

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
        assertTrue(VellumSpotShader.contains("mix(fullHalf * 0.36, grown, progress)"))
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
        val short = verseSoakHalfSize(width = 1000f, height = 400f, progress = 1f, rimInset = rim)
        val tall = verseSoakHalfSize(width = 1000f, height = 2600f, progress = 1f, rimInset = rim)
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
    fun `verse soak opens small and grows into the block`() {
        val seed = verseSoakHalfSize(1000f, 2600f, progress = 0f, rimInset = 25f)
        val full = verseSoakHalfSize(1000f, 2600f, progress = 1f, rimInset = 25f)
        assertEquals(2600f * 0.5f * 0.36f, seed.height, 0.01f)
        assertTrue(full.height > seed.height)
        val mid = verseSoakHalfSize(1000f, 2600f, progress = 0.5f, rimInset = 25f)
        assertTrue(mid.height > seed.height && mid.height < full.height)
    }

    @Test
    fun `a one-line verse cannot invert under the rim inset`() {
        // Rim wider than the block itself: keep at least half, never a
        // negative or zero-height wash.
        val tiny = verseSoakHalfSize(width = 300f, height = 40f, progress = 1f, rimInset = 60f)
        assertEquals(20f * 0.5f, tiny.height, 0.01f)
        assertTrue(tiny.width > 0f)
    }

    @Test
    fun `spot pigment interpolates with progress so deselect fades out`() {
        assertEquals(0f, inkSpotAppear(0f), 0f)
        assertEquals(0.5f, inkSpotAppear(0.5f), 0f)
        assertEquals(1f, inkSpotAppear(1f), 0f)
        assertTrue(VellumSpotShader.contains("appear = progress"))
    }
}
