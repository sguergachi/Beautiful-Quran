package com.beautifulquran

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperStackDragPolicyTest {
    @Test
    fun `pinch or child claim interrupts a page drag`() {
        assertFalse(stackDragInterrupted(pressedPointers = 1, primaryConsumed = false))
        assertTrue(stackDragInterrupted(pressedPointers = 2, primaryConsumed = false))
        assertTrue(stackDragInterrupted(pressedPointers = 1, primaryConsumed = true))
    }
}
