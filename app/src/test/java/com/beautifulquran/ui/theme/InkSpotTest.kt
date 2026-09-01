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
    fun `verse soak uses the guide field diffusion not a box oval`() {
        assertTrue(VellumSpotShader.contains("1.0 / (1.0 + exp(-absorbed / diffusion))"))
        assertTrue(VellumSpotShader.contains("noise(origin * float2(0.006, 0.008))"))
        assertTrue(VellumSpotShader.contains("noise(origin * float2(0.055, 0.071))"))
        assertTrue(VellumFieldShader.contains("noise(fragCoord * float2(0.006, 0.008))"))
        assertTrue(VellumFieldShader.contains("1.0 / (1.0 + exp(-absorbed / diffusion))"))
        assertFalse(VellumSpotShader.contains("mix(0.56, 0.84, fill)"))
    }
}
