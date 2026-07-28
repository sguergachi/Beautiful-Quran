package com.beautifulquran.timingslab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingOverridesTest {

    @Test
    fun `only legacy or unversioned rows need whole row clock migration`() {
        assertTrue(needsTimingClockMigration(null))
        assertTrue(needsTimingClockMigration(1))
        assertFalse(needsTimingClockMigration(2))
    }
}
