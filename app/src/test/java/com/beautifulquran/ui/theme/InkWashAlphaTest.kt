package com.beautifulquran.ui.theme

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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

    @Test
    fun washProfile_isSoftAtEnds() {
        assertEquals(0f, inkWashProfile(0f), 1e-5f)
        assertEquals(1f, inkWashProfile(1f), 1e-5f)
    }

    @Test
    fun washProfile_frontLoadsDensity_vsSymmetricSmootherstep() {
        // R2: mid-feather is denser than the old symmetric S-curve (arrival event).
        val mid = inkWashProfile(0.5f)
        assertTrue("expected mid density > 0.5, got $mid", mid > 0.5f)
        assertTrue(mid > inkSmootherstep(0.5f))
        // Steep toe: at t=0.25, profile is well above linear-ish smootherstep.
        assertTrue(inkWashProfile(0.25f) > inkSmootherstep(0.25f))
        // Long shoulder: late half still climbing (not already flat at 1).
        assertTrue(inkWashProfile(0.75f) < 1f)
        assertTrue(inkWashProfile(0.75f) > inkWashProfile(0.5f))
    }

    @Test
    fun washProfile_tenToNinetySpansAboutHalfFeather() {
        // Keep 10–90% band ≥ ~0.4 of feather so letter-scaled edges don't peel.
        fun findT(target: Float): Float {
            var lo = 0f
            var hi = 1f
            repeat(40) {
                val mid = (lo + hi) / 2f
                if (inkWashProfile(mid) < target) lo = mid else hi = mid
            }
            return (lo + hi) / 2f
        }
        val t10 = findT(0.1f)
        val t90 = findT(0.9f)
        assertTrue("10–90 span too narrow: ${t90 - t10}", t90 - t10 >= 0.4f)
        // Median (50%) lands in the first half — steep toe, long shoulder.
        val t50 = findT(0.5f)
        assertTrue("median should be early (toe), got $t50", t50 < 0.5f)
        assertTrue(abs(inkWashProfile(t50) - 0.5f) < 0.02f)
    }
}
