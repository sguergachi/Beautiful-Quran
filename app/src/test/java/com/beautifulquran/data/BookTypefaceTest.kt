package com.beautifulquran.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BookTypefaceTest {
    @Test
    fun storedCodesReadBackAndTimelessReadsAsTheShippedFace() {
        assertEquals(BookTypeface.CLASSIC, bookTypefaceFor(storedCode = 1, legacyTimeless = null))
        assertEquals(BookTypeface.JUNICODE, bookTypefaceFor(storedCode = 2, legacyTimeless = null))
        // 0 was Timeless, since removed; an unknown code is a later version's.
        assertEquals(BookTypeface.JUNICODE, bookTypefaceFor(storedCode = 0, legacyTimeless = null))
        assertEquals(BookTypeface.JUNICODE, bookTypefaceFor(storedCode = 99, legacyTimeless = false))
    }

    @Test
    fun theOldTimelessSwitchMigrates() {
        assertEquals(BookTypeface.JUNICODE, bookTypefaceFor(storedCode = null, legacyTimeless = true))
        assertEquals(BookTypeface.CLASSIC, bookTypefaceFor(storedCode = null, legacyTimeless = false))
        assertEquals(BookTypeface.JUNICODE, bookTypefaceFor(storedCode = null, legacyTimeless = null))
    }

    @Test
    fun codesAreStableAndDistinct() {
        assertEquals(listOf(1, 2), BookTypeface.entries.map { it.code })
    }
}
