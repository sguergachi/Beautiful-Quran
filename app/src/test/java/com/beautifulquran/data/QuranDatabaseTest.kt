package com.beautifulquran.data

import org.junit.Assert.assertEquals
import org.junit.Test

class QuranDatabaseTest {
    @Test
    fun `native cache covers the database plus working space`() {
        assertEquals(1_024L, quranDatabaseCacheKiB(0))
        assertEquals(1_025L, quranDatabaseCacheKiB(1))
        assertEquals(1_025L, quranDatabaseCacheKiB(1_024))
        assertEquals(1_026L, quranDatabaseCacheKiB(1_025))
        assertEquals(26_168L, quranDatabaseCacheKiB(25_747_456L))
    }
}
