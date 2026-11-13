package com.beautifulquran.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinReorderTest {
    @Test
    fun carryingARowMovesItToItsNewSeat() {
        val band = listOf(1, 7, 3, 9)
        assertEquals(listOf(7, 3, 1, 9), movePinned(band, 1, 2))
        assertEquals(listOf(3, 1, 7, 9), movePinned(band, 3, 0))
        assertEquals(listOf(1, 3, 9, 7), movePinned(band, 7, 3))
    }

    @Test
    fun aSeatPastTheEndsStopsAtTheEnd() {
        val band = listOf(1, 7, 3)
        assertEquals(listOf(7, 3, 1), movePinned(band, 1, 40))
        assertEquals(listOf(3, 1, 7), movePinned(band, 3, -5))
    }

    @Test
    fun aRowLeftWhereItWasOrNotPinnedChangesNothing() {
        val band = listOf(1, 7, 3)
        assertEquals(band, movePinned(band, 7, 1))
        assertEquals(band, movePinned(band, 55, 0))
        assertEquals(emptyList<Int>(), movePinned(emptyList(), 1, 0))
    }

    private val seats = listOf(PinSeat(0, 100, 50), PinSeat(1, 150, 50), PinSeat(2, 200, 50))

    @Test
    fun aRowCarriedOverASeatTakesIt() {
        assertEquals(0, seatAt(120f, seats))
        assertEquals(1, seatAt(150f, seats))
        assertEquals(1, seatAt(199.9f, seats))
        assertEquals(2, seatAt(230f, seats))
    }

    @Test
    fun aRowCarriedBeyondTheBandTakesTheNearestEnd() {
        assertEquals(0, seatAt(-30f, seats))
        assertEquals(2, seatAt(900f, seats))
    }

    @Test
    fun withNoBandRowInViewThereIsNoSeat() {
        assertNull(seatAt(120f, emptyList()))
    }

    @Test
    fun seatsAreReadFromWhatIsOnScreenInAnyOrder() {
        // The band scrolled partly out of view: only two seats are laid out.
        val visible = listOf(PinSeat(3, 60, 50), PinSeat(2, 10, 50))
        assertEquals(2, seatAt(0f, visible))
        assertEquals(3, seatAt(400f, visible))
    }

    @Test
    fun onlyARearrangementOfThePinsIsKept() {
        val stored = listOf(1, 7, 3)
        assertEquals(listOf(3, 1, 7), acceptPinOrder(stored, listOf(3, 1, 7)))
        // A chapter unpinned elsewhere meanwhile, one added, or a repeat.
        assertEquals(stored, acceptPinOrder(stored, listOf(3, 1)))
        assertEquals(stored, acceptPinOrder(stored, listOf(3, 1, 7, 9)))
        assertEquals(stored, acceptPinOrder(stored, listOf(1, 1, 7)))
    }
}
