package com.beautifulquran.ui.entrance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverFrameGeometryTest {

    private val density = 3f // xxhdpi-ish

    @Test
    fun `outer corners are a square 3 dp fillet`() {
        val r = COVER_OUTER_CORNER_RADIUS_DP * density
        for (screen in listOf(
            ScreenCornerRadiiPx(180f, 180f, 180f, 180f),
            ScreenCornerRadiiPx(120f, 80f, 80f, 120f),
            ScreenCornerRadiiPx.Zero,
        )) {
            val g = coverFrameGeometry(screen, density)
            assertEquals(r, g.outerCorners.topLeft, 0.01f)
            assertEquals(r, g.outerCorners.topRight, 0.01f)
            assertEquals(r, g.outerCorners.bottomRight, 0.01f)
            assertEquals(r, g.outerCorners.bottomLeft, 0.01f)
        }
    }

    @Test
    fun `inner corners stay concentric with the screen`() {
        // Large enough that the full rule gap fits inside the curve.
        val screen = ScreenCornerRadiiPx(180f, 180f, 180f, 180f)
        val g = coverFrameGeometry(screen, density)
        assertEquals(screen.topLeft - g.innerInsetPx, g.innerCorners.topLeft, 0.01f)
        assertTrue(g.innerInsetPx > g.outerInsetPx)
    }

    @Test
    fun `per-corner screen radii stay concentric on the inner rule`() {
        // Radii large enough that the inner inset does not consume the curve.
        val screen = ScreenCornerRadiiPx(240f, 200f, 200f, 240f)
        val g = coverFrameGeometry(screen, density)
        assertEquals(screen.topLeft - g.innerInsetPx, g.innerCorners.topLeft, 0.01f)
        assertEquals(screen.topRight - g.innerInsetPx, g.innerCorners.topRight, 0.01f)
        assertEquals(screen.bottomLeft - g.innerInsetPx, g.innerCorners.bottomLeft, 0.01f)
    }

    @Test
    fun `zero screen radii invent a designed fallback frame`() {
        val g = coverFrameGeometry(ScreenCornerRadiiPx.Zero, density)
        assertTrue(g.outerInsetPx > 0f)
        assertTrue(g.outerCorners.topLeft > 0f)
        assertTrue(g.innerCorners.topLeft >= 0f)
        assertTrue(g.innerInsetPx > g.outerInsetPx)
        assertTrue(g.starRadiusPx > 0f)
        assertEquals(g.innerInsetPx - g.outerInsetPx, g.starRadiusPx * 2f, 0.01f)
    }

    @Test
    fun `outer inset stays within the readable clamp on large radii`() {
        val huge = coverFrameGeometry(ScreenCornerRadiiPx(400f, 400f, 400f, 400f), density)
        assertTrue(huge.outerInsetPx <= 40f * density * 1.01f)
        assertTrue(huge.outerCorners.topLeft > 0f)
        // A wide gilt margin must not grow the seal past the band it sits in.
        assertEquals(huge.innerInsetPx - huge.outerInsetPx, huge.starRadiusPx * 2f, 0.01f)
    }

    @Test
    fun `tiny screen radius never insets past the concentric limit`() {
        val tiny = coverFrameGeometry(ScreenCornerRadiiPx(30f, 30f, 30f, 30f), density)
        // Prefer a visible curve over the nominal min inset when R is small.
        assertTrue(tiny.outerInsetPx <= 30f * 0.55f + 0.01f)
        assertTrue(tiny.outerCorners.topLeft > 0f)
    }

    @Test
    fun `typical phone gets a generous margin and band-sized corner seals`() {
        // ~50 dp corner at xxhdpi — common modern phone silhouette.
        val screen = ScreenCornerRadiiPx(150f, 150f, 150f, 150f)
        val g = coverFrameGeometry(screen, density)
        assertTrue(g.outerInsetPx >= 22f * density * 0.99f)
        assertSealInsideBand(g)
    }

    @Test
    fun `corner seal never breaks the outer gilt rule`() {
        // Pixel-10-ish silhouette: the margin is narrower than the band, so
        // a seal sized from the outer inset used to overhang the outer rule.
        for (r in listOf(0f, 30f, 90f, 150f, 400f)) {
            val g = coverFrameGeometry(ScreenCornerRadiiPx(r, r, r, r), density)
            assertSealInsideBand(g)
        }
    }

    /** The seal is seated on the band's centreline; its outer extent must
     *  land on the outer rule and its inner extent on the inner rule. */
    private fun assertSealInsideBand(g: CoverFrameGeometry) {
        val bandCenter = (g.outerInsetPx + g.innerInsetPx) / 2f
        assertEquals(g.outerInsetPx, bandCenter - g.starRadiusPx, 0.01f)
        assertEquals(g.innerInsetPx, bandCenter + g.starRadiusPx, 0.01f)
    }

    @Test
    fun `frame margins reserve ignoring-visibility status bar from the first frame`() {
        val g = coverFrameGeometry(ScreenCornerRadiiPx.Zero, density)
        val statusTop = (48f * density).toInt()
        val (hDp, vDp) = coverFrameMarginsDp(
            density = density,
            safe = CoverSafeInsetsPx(0, statusTop, 0, 0),
            screenWidthPx = 360f * density,
            duaWidthPx = 120f * density,
            innerInsetPx = g.innerInsetPx,
        )
        // Vertical: status + 8 dp breathing beats the 44 dp base.
        assertEquals(48f + 8f, vDp, 0.01f)
        // Horizontal stays at the book base when cutouts are absent.
        assertEquals(16f, hDp, 0.01f)
    }

    @Test
    fun `frame margins without safe insets keep the book base`() {
        val g = coverFrameGeometry(ScreenCornerRadiiPx.Zero, density)
        val (hDp, vDp) = coverFrameMarginsDp(
            density = density,
            safe = CoverSafeInsetsPx.Zero,
            screenWidthPx = 360f * density,
            duaWidthPx = 200f * density,
            innerInsetPx = g.innerInsetPx,
        )
        assertEquals(16f, hDp, 0.01f)
        assertEquals(44f, vDp, 0.01f)
    }
}
