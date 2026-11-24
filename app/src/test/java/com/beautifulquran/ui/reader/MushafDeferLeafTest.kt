package com.beautifulquran.ui.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which leaves the pager composes on its own stay blank paper until needed. */
class MushafDeferLeafTest {

    private fun defers(
        pageIndex: Int,
        restPage: Int = 10,
        held: Int = MushafHeldNeighbours,
        targetPage: Int = restPage,
        nearView: Boolean = false,
    ) = mushafDefersLeaf(pageIndex, restPage, held, targetPage, nearView)

    @Test
    fun `the held leaves either side of the resting page are composed`() {
        for (page in 8..12) assertFalse("page $page", defers(page))
    }

    @Test
    fun `a leaf past the held ones waits - the one joining mid-turn, the one prefetched at rest`() {
        assertTrue(defers(13))
        assertTrue(defers(7))
    }

    @Test
    fun `while only the near pair is held, the outer leaves wait too`() {
        assertFalse(defers(11, held = 1))
        assertTrue(defers(12, held = 1))
    }

    @Test
    fun `a leaf in or beside the view is composed at once`() {
        // A second swipe begun before the first settled.
        assertFalse(defers(13, nearView = true))
    }

    @Test
    fun `a jump's destination is composed at once`() {
        assertFalse(defers(40, targetPage = 40))
        assertTrue(defers(39, targetPage = 40))
    }
}
