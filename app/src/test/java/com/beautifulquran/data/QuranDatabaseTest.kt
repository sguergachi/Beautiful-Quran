package com.beautifulquran.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `missing copy is extracted`() {
        assertTrue(needsReextract(fileExists = false, fileLength = 0L, assetLength = 25_989_120L))
    }

    @Test
    fun `empty copy is dropped and extracted again`() {
        assertTrue(needsReextract(fileExists = true, fileLength = 0L, assetLength = 25_989_120L))
    }

    @Test
    fun `truncated copy is dropped and extracted again`() {
        assertTrue(needsReextract(fileExists = true, fileLength = 3_6864L, assetLength = 25_989_120L))
    }

    @Test
    fun `intact copy is kept`() {
        assertFalse(needsReextract(fileExists = true, fileLength = 25_989_120L, assetLength = 25_989_120L))
    }

    @Test
    fun `non-empty copy is kept when the asset reports no length`() {
        assertFalse(needsReextract(fileExists = true, fileLength = 25_989_120L, assetLength = null))
    }
}
