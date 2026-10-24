package com.beautifulquran.ui.reader

import org.junit.Assert.*
import org.junit.Test

class PaperInkSequenceTest {
    @Test fun repeatsOnlyTheMiddleVerseOnce() {
        val sequence = PaperInkSequence()
        assertFalse(sequence.observe(1, 10))
        assertTrue(sequence.observe(2, 11))
        assertTrue(sequence.observe(2, 11))
        assertFalse(sequence.repeatedMiddle)
        assertFalse(sequence.observe(2, 12))
        assertTrue(sequence.repeatedMiddle)
        assertFalse(sequence.observe(3, 13))
    }

    @Test fun restartingTheStudyRearmsItsOneRepeat() {
        val sequence = PaperInkSequence()
        sequence.observe(2, 5)
        sequence.observe(2, 6)
        sequence.reset()
        assertFalse(sequence.repeatedMiddle)
        assertFalse(sequence.observe(1, 7))
        assertTrue(sequence.observe(2, 8))
    }
}
