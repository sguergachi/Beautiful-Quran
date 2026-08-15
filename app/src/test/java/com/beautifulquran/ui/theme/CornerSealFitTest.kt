package com.beautifulquran.ui.theme

import com.beautifulquran.ui.entrance.ScreenCornerRadiiPx
import com.beautifulquran.ui.entrance.coverFrameGeometry
import com.beautifulquran.ui.theme.ornament.generateCoverOrnament
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The corner khatam is drawn from a normalised 0..1 box, but its bezel tips
 * sit at `tipRadius` (0.58–0.66) rather than 0.5 — so a seal drawn at
 * `starRadiusPx * 2` reaches ~30% past the radius the frame reserved for it
 * and breaks the outer gilt rule. These pin the seal to the border band for
 * every generated ornament and every phone silhouette.
 */
class CornerSealFitTest {

    private val density = 3f

    @Test
    fun `seal box compensates for the bezel so drawn extent is the reserved radius`() {
        val geometry = coverFrameGeometry(ScreenCornerRadiiPx(90f, 90f, 90f, 90f), density)
        for (seed in 1..200) {
            val seal = generateCoverOrnament(seed).cornerSeal
            val box = sealBoxPx(geometry, seal)
            val extent = max(0.5f, seal.tipRadius.toFloat()) * box
            assertEquals("seed $seed", geometry.starRadiusPx, extent, 0.01f)
        }
    }

    /**
     * Signed distance to a rounded rect inset by [inset] with corner radius
     * [radius] in a [w] x [h] canvas: negative inside, positive outside.
     */
    private fun sdRoundRect(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        inset: Float,
        radius: Float,
    ): Float {
        val halfW = w / 2f - inset
        val halfH = h / 2f - inset
        val qx = abs(x - w / 2f) - halfW + radius
        val qy = abs(y - h / 2f) - halfH + radius
        return min(max(qx, qy), 0f) + hypot(max(qx, 0f), max(qy, 0f)) - radius
    }

    @Test
    fun `seal is inscribed in the band at the corners, not just along the sides`() {
        // The rules are concentric *rounded* rects, so the corner the two
        // straight centrelines cross at lies outside the band's own arc. A
        // seal seated there leaves through the outer rule by 0.41x its
        // distance to the arc centre — invisible on a square emulator,
        // glaring on a phone with a generous radius.
        val w = 1080f
        val h = 2400f
        for (r in listOf(0f, 30f, 60f, 90f, 120f, 150f, 220f, 400f)) {
            val g = coverFrameGeometry(ScreenCornerRadiiPx(r, r, r, r), density)
            val centre = sealCenterPx(g, g.outerCorners.topLeft + g.outerInsetPx)
            for (i in 0 until 128) {
                val a = i * 2.0 * Math.PI / 128
                val px = centre + (g.starRadiusPx * cos(a)).toFloat()
                val py = centre + (g.starRadiusPx * sin(a)).toFloat()
                val outer = sdRoundRect(px, py, w, h, g.outerInsetPx, g.outerCorners.topLeft)
                val inner = sdRoundRect(px, py, w, h, g.innerInsetPx, g.innerCorners.topLeft)
                assertTrue("r=$r: seal crosses the outer rule (sd=$outer)", outer <= 0.01f)
                assertTrue("r=$r: seal crosses the inner rule (sd=$inner)", inner >= -0.01f)
            }
        }
    }

    @Test
    fun `generated seals never cross either gilt rule on any silhouette`() {
        for (r in listOf(0f, 30f, 60f, 90f, 150f, 400f)) {
            val geometry = coverFrameGeometry(ScreenCornerRadiiPx(r, r, r, r), density)
            val bandCenter = (geometry.outerInsetPx + geometry.innerInsetPx) / 2f
            for (seed in 1..200) {
                val seal = generateCoverOrnament(seed).cornerSeal
                val box = sealBoxPx(geometry, seal)
                // Widest feature of the seal, measured across the band.
                val extent = seal.strokes
                    .flatMap { it.points }
                    .flatMap { listOf(it.x, it.y) }
                    .maxOf { max(it - 0.5, 0.5 - it) }
                    .toFloat() * box
                assertTrue(
                    "seed $seed at r=$r broke the outer rule",
                    bandCenter - extent >= geometry.outerInsetPx - 0.01f,
                )
                assertTrue(
                    "seed $seed at r=$r broke the inner rule",
                    bandCenter + extent <= geometry.innerInsetPx + 0.01f,
                )
            }
        }
    }
}
