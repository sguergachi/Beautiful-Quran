package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dial is the book's edge seen side-on, so it runs the way the book turns:
 * a mushaf is bound on the right, a book of the translation on the left.
 */
class MushafDialDirectionTest {

    @Test
    fun `the mushaf seats its first leaf at the right of the rule`() {
        assertEquals(1f, mushafDialAlong(0f, rightToLeft = true), 0f)
        assertEquals(0f, mushafDialAlong(1f, rightToLeft = true), 0f)
    }

    @Test
    fun `the English book seats its first leaf at the left`() {
        assertEquals(0f, mushafDialAlong(0f, rightToLeft = false), 0f)
        assertEquals(1f, mushafDialAlong(1f, rightToLeft = false), 0f)
    }

    @Test
    fun `the middle of the book is the middle of the rule either way`() {
        assertEquals(0.5f, mushafDialAlong(0.5f, rightToLeft = true), 1e-6f)
        assertEquals(0.5f, mushafDialAlong(0.5f, rightToLeft = false), 1e-6f)
    }

    @Test
    fun `the trough hands back the leaf under the finger, in the book's order`() {
        val run = 1..101
        // The rule's left end: leaf 101 in a mushaf, leaf 1 in an English book.
        assertTrue(mushafDialTroughPage(0f, run, rightToLeft = true) > 100f)
        assertTrue(mushafDialTroughPage(0f, run, rightToLeft = false) < 2f)
        // And the right end is the other one.
        assertTrue(mushafDialTroughPage(1f, run, rightToLeft = true) < 2f)
        assertTrue(mushafDialTroughPage(1f, run, rightToLeft = false) > 100f)
    }

    @Test
    fun `the seats climb the rule in a book bound on the left`() {
        // The failure this exists for: mirroring where marks *draw* while the
        // walk that relaxes them apart still assumes the mushaf's direction.
        // It does not mirror the comb — it forces every seat past its neighbour
        // and piles the whole book into half the rule, which no test of a
        // single fraction can see.
        val marks = IntArray(114) { it * 8 + 1 }
        val seats = mushafDialCombCellSeats(
            marks = marks,
            pageCount = 914,
            insetPx = 10f,
            widthPx = 1000f,
            rulePx = 1f,
            rightToLeft = false,
        )
        for (i in 1 until seats.size) {
            assertTrue(
                "seat $i at ${seats[i]} must sit right of ${i - 1} at ${seats[i - 1]}",
                seats[i] > seats[i - 1],
            )
        }
        assertTrue("the first chapter sits at the left", seats.first() < 60f)
        assertTrue("the last chapter sits at the right", seats.last() > 940f)
    }

    @Test
    fun `the seats descend the rule in a mushaf`() {
        val marks = IntArray(114) { it * 5 + 1 }
        val seats = mushafDialCombCellSeats(
            marks = marks,
            pageCount = 604,
            insetPx = 10f,
            widthPx = 1000f,
            rulePx = 1f,
            rightToLeft = true,
        )
        for (i in 1 until seats.size) {
            assertTrue(
                "seat $i at ${seats[i]} must sit left of ${i - 1} at ${seats[i - 1]}",
                seats[i] < seats[i - 1],
            )
        }
        assertTrue("the first chapter sits at the right", seats.first() > 940f)
    }

    @Test
    fun `a leaf lands at the same distance from its own end either way`() {
        val run = 1..101
        val fromRight = mushafDialTroughPage(0.25f, run, rightToLeft = true)
        val fromLeft = mushafDialTroughPage(0.75f, run, rightToLeft = false)
        assertEquals(fromRight, fromLeft, 1e-3f)
    }
}
