package com.beautifulquran.data

import com.beautifulquran.domain.EnglishVerseRun
import com.beautifulquran.domain.englishBookOf
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EnglishBookCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun writeIsAtomicAndReadableBack() {
        val cache = EnglishBookCache(tmp.root)
        cache.write("a", book(from = 0, to = 4))
        val read = cache.read("a", { _, _ -> 1 }, { _, _ -> "abcd" })
        assertNotNull(read)
        assertEquals(1, read!!.leafCount)
        assertEquals(listOf(EnglishVerseRun(1, 1, 0, 4)), read.leaf(0)!!.runs)
        assertNull(cache.read("missing", { _, _ -> 1 }, { _, _ -> "abcd" }))
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".writing") })
    }

    @Test
    fun retainsARecencyBoundedSetAndReusesAKeptKey() {
        val cache = EnglishBookCache(tmp.root)
        val t0 = 1_000L
        for (i in 0 until EnglishBookCache.RETAINED_BOOKS) {
            cache.write("k$i", book())
            File(tmp.root, "k$i").setLastModified(t0 + i)
        }
        cache.read("k0", { _, _ -> 1 }, { _, _ -> "x" })
        File(tmp.root, "k0").setLastModified(t0 + 10)
        cache.write("k4", book())
        File(tmp.root, "k4").setLastModified(t0 + 11)

        val names = tmp.root.listFiles()!!.map { it.name }.toSet()
        assertEquals(EnglishBookCache.RETAINED_BOOKS, names.size)
        assertTrue("most recently read config must be kept", "k0" in names)
        assertTrue("k4" in names)
        assertFalse("oldest unread config is the one to forget", "k1" in names)
        assertNotNull(cache.read("k0", { _, _ -> 1 }, { _, _ -> "x" }))
    }

    @Test
    fun pruneNeverDeletesInProgressTempFiles() {
        val cache = EnglishBookCache(tmp.root)
        val stray = tmp.newFile("other.1.writing")
        stray.writeBytes(byteArrayOf(1, 2, 3))
        for (i in 0 until EnglishBookCache.RETAINED_BOOKS + 1) {
            cache.write("k$i", book())
        }
        assertTrue("a concurrent builder's temp must survive prune", stray.exists())
        assertTrue(tmp.root.listFiles()!!.any { it.name.endsWith(".writing") })
        assertEquals(
            EnglishBookCache.RETAINED_BOOKS,
            tmp.root.listFiles()!!.count { it.isFile && !it.name.endsWith(".writing") },
        )
    }

    @Test
    fun failedPublishCleansItsTemporaryFile() {
        val cache = EnglishBookCache(tmp.root)
        tmp.newFolder("blocked")
        cache.write("blocked", book())
        assertTrue(File(tmp.root, "blocked").isDirectory)
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".writing") })
    }

    @Test
    fun abandonedTemporaryFilesAreReclaimed() {
        val stale = tmp.newFile("old.writing")
        assertTrue(stale.setLastModified(1_000L))
        EnglishBookCache(tmp.root).write("current", book())
        assertFalse(stale.exists())
        assertTrue(File(tmp.root, "current").isFile)
    }

    @Test
    fun newlyWrittenBookSurvivesEvenIfTheClockMovedBackwards() {
        val cache = EnglishBookCache(tmp.root)
        repeat(EnglishBookCache.RETAINED_BOOKS) {
            cache.write("k$it", book())
            File(tmp.root, "k$it").setLastModified(System.currentTimeMillis() + 60_000)
        }
        cache.write("new", book())
        assertNotNull(cache.read("new", { _, _ -> 1 }, { _, _ -> "x" }))
        assertEquals(EnglishBookCache.RETAINED_BOOKS, tmp.root.listFiles()!!.size)
    }

    private fun book(from: Int = 0, to: Int = 1) = englishBookOf(
        listOf(listOf(EnglishVerseRun(1, 1, from, to))),
        pageOf = { _, _ -> 1 },
        text = { _, _ -> "x" },
    )
}
