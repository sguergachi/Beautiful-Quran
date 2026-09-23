package com.beautifulquran.ui.theme

import androidx.compose.ui.geometry.Rect
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
}
