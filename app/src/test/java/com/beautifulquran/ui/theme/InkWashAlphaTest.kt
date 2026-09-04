package com.beautifulquran.ui.theme

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkWashAlphaTest {

    @Test
    fun paperCoverBleed_staysInsideItsTextLine() {
        val line = Rect(left = 20f, top = 40f, right = 120f, bottom = 80f)

        val cover = linePaperCoverBounds(line, horizontalPad = 4f)

        assertEquals(16f, cover.left, 0f)
        assertEquals(124f, cover.right, 0f)
        assertEquals(line.top, cover.top, 0f)
        assertEquals(line.bottom, cover.bottom, 0f)
    }

    @Test
    fun aheadOfWash_restsAtUpcomingFloor() {
        // pos past the wash head → still at resting ink.
        val alpha = inkWashAlpha(pos = 0.9f, progress = 0.1f, restingAlpha = 0.22f)
        assertEquals(0.22f, alpha, 1e-4f)
    }

    @Test
    fun behindWash_reachesFullInk() {
        val alpha = inkWashAlpha(pos = 0.1f, progress = 1f, restingAlpha = 0.22f)
        assertEquals(1f, alpha, 1e-4f)
    }

    @Test
    fun midFeather_isBetweenRestingAndFull() {
        // At progress 0.5 the wash head is mid-span; pos 0.5 sits in the feather.
        val alpha = inkWashAlpha(pos = 0.5f, progress = 0.5f, restingAlpha = 0.22f)
        assertTrue(alpha in 0.22f..1f)
        assertTrue(alpha > 0.22f)
        assertTrue(alpha < 1f)
    }

    @Test
    fun revealLeadsFromFirstLetter() {
        // Same progress: the first-revealed letter (pos 0) is always further
        // along the bloom than the last letter (pos 1).
        val first = inkWashAlpha(pos = 0f, progress = 0.4f, restingAlpha = 0.22f)
        val last = inkWashAlpha(pos = 1f, progress = 0.4f, restingAlpha = 0.22f)
        assertTrue(first > last)
    }
}
