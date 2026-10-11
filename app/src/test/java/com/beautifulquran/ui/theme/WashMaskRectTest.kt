package com.beautifulquran.ui.theme

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

/**
 * The directional wash is what holds back ink the sweep has not reached. It is
 * a DstIn pass, so anything it fails to cover keeps full alpha — and the glint
 * halo is blurred well past the line box the wash used to be clipped to. That
 * left a lit fringe hanging under a word whose letters were still dark, right
 * as its bloom began.
 */
class WashMaskRectTest {

    /** The layer the glow and tint are painted into, from `shapedWordBloom`. */
    private fun glowLayer(bounds: Rect, colorBleed: Float) = Rect(
        bounds.left - colorBleed,
        bounds.top - colorBleed,
        bounds.right + colorBleed,
        bounds.bottom + colorBleed,
    )

    private val lineBox = Rect(100f, 200f, 400f, 260f)

    @Test
    fun `halo size cannot delay the glint behind a mid-word ink wash`() {
        val line = Rect(0f, 0f, 100f, 40f)
        val ink = linePaperCoverBounds(line, 4f)
        val progress = 0.13f
        val feather = 1.1092f
        fun alpha(cover: Rect, rtl: Boolean): Float {
            val x = if (rtl) line.right else line.left
            val position = if (rtl) cover.right - x else x - cover.left
            val edge = cover.width * feather
            return inkSmootherstep((progress * (cover.width + edge) - position) / edge)
        }
        for (radius in listOf(3.5f, 10f, 30f)) {
            val glint = ShapedWordBloom.ColorReveal(0..3, progress, Color.White,
                glowAlpha = 0.78f, glowRadius = radius)
            val travel = glint.washBounds(line, 4f)
            for (rtl in listOf(false, true)) {
                assertEquals(alpha(ink, rtl), alpha(travel, rtl), 0f)
                assertTrue("the leading glint must be visible during the held note", alpha(travel, rtl) > 0.06f)
            }
            val bleed = radius * 3f
            val mask = washMaskRect(travel, bleed, openTop = true, openBottom = true)
            val halo = glowLayer(line, bleed)
            assertTrue(mask.left <= halo.left && mask.right >= halo.right &&
                mask.top <= halo.top && mask.bottom >= halo.bottom)
        }
    }

    @Test
    fun `the mask covers everything the glow layer can paint`() {
        val colorBleed = max(6f, 10f * 3f)
        val layer = glowLayer(lineBox, colorBleed)
        val mask = washMaskRect(lineBox, colorBleed, openTop = true, openBottom = true)
        assertTrue(
            "mask $mask must contain the glow layer $layer, or the halo survives " +
                "the sweep that is meant to be holding it back",
            mask.left <= layer.left && mask.top <= layer.top &&
                mask.right >= layer.right && mask.bottom >= layer.bottom,
        )
    }

    @Test
    fun `the bare line box does not, which is the bug`() {
        // The defect this guards: clipping the wash to the line box leaves the
        // halo's fringe unmasked, so it keeps full alpha however far along the
        // sweep is. Stated as the difference the helper makes.
        val colorBleed = 30f
        val layer = glowLayer(lineBox, colorBleed)
        val mask = washMaskRect(lineBox, colorBleed, openTop = true, openBottom = true)
        val boxCovers = lineBox.top <= layer.top && lineBox.bottom >= layer.bottom
        val maskCovers = mask.top <= layer.top && mask.bottom >= layer.bottom
        assertFalse("the line box must fall short, or there was no bug", boxCovers)
        assertTrue("the mask must not", maskCovers)
    }

    @Test
    fun `an inner line of a wrapped word keeps its vertical edges shut`() {
        // Opening both sides on every line would let one line's mask reach into
        // the next and erase light that line had already resolved.
        val mask = washMaskRect(lineBox, 30f, openTop = false, openBottom = false)
        assertEquals(lineBox.top, mask.top, 0f)
        assertEquals(lineBox.bottom, mask.bottom, 0f)
        assertTrue("horizontal reach is still opened", mask.left < lineBox.left)
        assertTrue("horizontal reach is still opened", mask.right > lineBox.right)
    }

    @Test
    fun `the first and last lines open only outward`() {
        val first = washMaskRect(lineBox, 30f, openTop = true, openBottom = false)
        assertTrue("the range's top is opened", first.top < lineBox.top)
        assertEquals("but not into the line below", lineBox.bottom, first.bottom, 0f)

        val last = washMaskRect(lineBox, 30f, openTop = false, openBottom = true)
        assertEquals("not into the line above", lineBox.top, last.top, 0f)
        assertTrue("the range's bottom is opened", last.bottom > lineBox.bottom)
    }

    @Test
    fun `the mask grows by the bleed and nothing else`() {
        // Travel is measured from the line box, never the mask, so the only
        // thing the helper may do is widen — by exactly the bleed, symmetrically.
        val bleed = 30f
        val mask = washMaskRect(lineBox, bleed, openTop = true, openBottom = true)
        assertEquals(lineBox.left - bleed, mask.left, 0f)
        assertEquals(lineBox.right + bleed, mask.right, 0f)
        assertEquals(lineBox.width + bleed * 2f, mask.width, 0f)
        assertEquals(lineBox.height + bleed * 2f, mask.height, 0f)
        assertEquals(
            "the mask stays centred on the box, so the sweep's midpoint holds",
            lineBox.center.x, mask.center.x, 0f,
        )
    }

    @Test
    fun `zero bleed is the identity`() {
        assertEquals(lineBox, washMaskRect(lineBox, 0f, openTop = true, openBottom = true))
    }

    @Test
    fun `a wrapped word's first line washes its spill where the next line is not`() {
        // 52:13 on the English leaf: "with a [violent] thrust, [its angels will"
        // ends one line and "say]" opens the next. The second line's mask spans
        // only "say]", so the first line's halo spilling into the leading had
        // nothing to wash it and lit a strip under unsaid words.
        val first = Rect(228f, 200f, 951f, 270f)
        val second = Rect(0f, 270f, 110f, 340f)
        val bleed = 40f
        val spill = washMaskSpill(first, bleed, second, below = true)
        assertEquals(1, spill.size)
        val strip = spill.single()
        assertEquals(first.bottom, strip.top, 0f)
        assertEquals(first.bottom + bleed, strip.bottom, 0f)
        // The next line's mask stops well short of this line, so the strip is
        // this line's whole reach.
        assertEquals(first.left - bleed, strip.left, 0f)
        assertEquals(first.right + bleed, strip.right, 0f)
        // And the second line's spill upward is everything right of its mask,
        // which never reaches into the first line's own columns twice.
        val up = washMaskSpill(second, bleed, first, below = false).single()
        assertEquals(first.bottom - bleed, up.top, 0f)
        assertEquals(first.bottom, up.bottom, 0f)
        assertTrue(up.right <= first.left - bleed)
    }

    @Test
    fun `spill strips never overlap the neighbour's mask`() {
        val line = Rect(100f, 200f, 900f, 260f)
        val bleed = 30f
        val neighbours = listOf(
            Rect(0f, 260f, 1000f, 320f), // wider: nothing to spill
            Rect(300f, 260f, 500f, 320f), // inside: both sides spill
            Rect(0f, 260f, 50f, 320f), // far left
            Rect(950f, 260f, 1000f, 320f), // far right
        )
        neighbours.forEach { n ->
            val nMask = washMaskRect(n, bleed, openTop = false, openBottom = false)
            washMaskSpill(line, bleed, n, below = true).forEach { strip ->
                val overlapX = minOf(strip.right, nMask.right) - maxOf(strip.left, nMask.left)
                assertTrue("strip $strip overlaps $nMask", overlapX <= 0f)
                assertTrue(strip.left >= line.left - bleed && strip.right <= line.right + bleed)
            }
        }
        assertTrue(washMaskSpill(line, bleed, neighbours[0], below = true).isEmpty())
        assertEquals(2, washMaskSpill(line, bleed, neighbours[1], below = true).size)
        assertTrue(washMaskSpill(line, 0f, neighbours[1], below = true).isEmpty())
    }
}
