package com.beautifulquran.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.junit.Assert.assertEquals
import org.junit.Test

class MushafDialHudTest {

    private val ink = Color(0xFF2B2622)
    private val orange = Color(0xFFC8642A)

    @Test
    fun aLabelThatFitsKeepsTheSlotWidthWhateverTheClamp() {
        // Each 16 px clamp step along the wall must not change what the label
        // is measured against while the label is narrower than the clamp.
        val widths = (400..1000 step 16).map { clamp ->
            mushafDialHudMeasureWidth(clampPx = clamp, slotPx = 1080, naturalPx = 380)
        }
        assertEquals(setOf(1080), widths.toSet())
    }

    @Test
    fun aLabelTheClampCutsFollowsTheClamp() {
        assertEquals(400, mushafDialHudMeasureWidth(clampPx = 400, slotPx = 1080, naturalPx = 401))
        assertEquals(1080, mushafDialHudMeasureWidth(clampPx = 400, slotPx = 1080, naturalPx = 400))
        // The clamp never reaches past the slot.
        assertEquals(500, mushafDialHudMeasureWidth(clampPx = 900, slotPx = 500, naturalPx = 600))
    }

    @Test
    fun drawnInkMatchesTheInkTheLabelWasComposedWith() {
        var pulse = 0f
        var ripe = 0f
        val hud = MushafDialHudInk(ink, orange, { pulse }, { ripe })
        for ((p, r) in listOf(0f to 0f, 1f to 0f, 0.4f to 0.75f, 0f to 1f)) {
            pulse = p
            ripe = r
            val o = maxOf(p, r)
            assertEquals(lerp(ink, orange, o).copy(alpha = 0.72f + 0.18f * p), hud.head())
            assertEquals(
                lerp(ink.copy(alpha = 0.48f), orange, o).copy(alpha = 0.48f + 0.22f * o),
                hud.foot(),
            )
        }
    }
}
