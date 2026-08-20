package com.beautifulquran.playback

import androidx.media3.datasource.cache.CacheSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File

class RecitationCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun underBudgetRemovesNothing() {
        val spans = listOf(span("a", length = 80, touched = 1))
        assertTrue(RecitationCache.spansToEvict(spans, usedBytes = 80, maxBytes = 256).isEmpty())
    }

    @Test
    fun evictsOldestFirstUntilUnderBudget() {
        val old = span("old", length = 80, touched = 1)
        val mid = span("mid", length = 80, touched = 2)
        val newest = span("new", length = 80, touched = 3)
        val removed = RecitationCache.spansToEvict(
            listOf(newest, old, mid),
            usedBytes = 240,
            maxBytes = 100,
        )
        assertEquals(listOf(old, mid), removed)
    }

    @Test
    fun stopsOnceTheFreedBytesCoverTheOverflow() {
        val old = span("old", length = 50, touched = 1)
        val keep = span("keep", length = 50, touched = 2)
        val removed = RecitationCache.spansToEvict(
            listOf(old, keep),
            usedBytes = 100,
            maxBytes = 50,
        )
        assertEquals(listOf(old), removed)
    }

    @Test
    fun oneGigabyteIsTheShippedCap() {
        assertEquals(1024L * 1024 * 1024, RecitationCache.MAX_BYTES)
    }

    @Test
    fun formatDownloadedBytesUsesWholeMegabytes() {
        assertEquals("None downloaded", formatDownloadedBytes(0))
        assertEquals("< 1 MB downloaded", formatDownloadedBytes(800_000))
        assertEquals("247 MB downloaded", formatDownloadedBytes(247L * 1024 * 1024))
    }

    @Test
    fun cachedBytesPreferKeepSoListenCopyIsNotDoubleCounted() {
        assertEquals(4L, cachedBytesForKey(listenBytes = 4L, keepBytes = 0L))
        assertEquals(4L, cachedBytesForKey(listenBytes = 4L, keepBytes = 4L))
        assertEquals(3L, cachedBytesForKey(listenBytes = 4L, keepBytes = 3L))
        assertEquals(0L, cachedBytesForKey(listenBytes = 0L, keepBytes = 0L))
    }

    @Test
    fun formatUsageIsOneTotal() {
        assertEquals("None downloaded", formatUsage(RecitationUsage()))
        assertEquals(
            "12 MB downloaded",
            formatUsage(RecitationUsage(listenBytes = 12L * 1024 * 1024)),
        )
        assertEquals(
            "3 MB downloaded",
            formatUsage(RecitationUsage(keepBytes = 3L * 1024 * 1024)),
        )
        assertEquals(
            "4 MB downloaded",
            formatUsage(RecitationUsage(listenBytes = 800_000, keepBytes = 4L * 1024 * 1024)),
        )
    }

    @Test
    fun directorySizeSumsFilesOnly() {
        val dir = tmp.newFolder("audio")
        File(dir, "a.bin").writeBytes(ByteArray(100))
        File(dir, "nested").mkdir()
        File(dir, "nested/b.bin").writeBytes(ByteArray(50))
        assertEquals(150L, directorySize(dir))
        assertEquals(0L, directorySize(File(tmp.root, "missing")))
    }

    @Test
    fun relocatesFilesDirAudioOntoEmptyCacheDir() {
        val from = tmp.newFolder("files-audio")
        File(from, "ayah.bin").writeText("mp3")
        val to = File(tmp.root, "cache-audio")
        relocateAudioDir(from, to)
        assertFalse(from.exists())
        assertEquals("mp3", File(to, "ayah.bin").readText())
    }

    @Test
    fun replacesEmptyCacheDirSoTheMoveStillLandsInCache() {
        val from = tmp.newFolder("files-audio")
        File(from, "ayah.bin").writeText("mp3")
        val to = tmp.newFolder("cache-audio")
        relocateAudioDir(from, to)
        assertFalse(from.exists())
        assertEquals("mp3", File(to, "ayah.bin").readText())
    }

    @Test
    fun keepsOtherDirWhenDestAlreadyHasAudio() {
        val from = tmp.newFolder("files-audio")
        File(from, "old.bin").writeText("stale")
        val to = tmp.newFolder("cache-audio")
        File(to, "kept.bin").writeText("live")
        relocateAudioDir(from, to)
        assertTrue(from.exists())
        assertEquals("stale", File(from, "old.bin").readText())
        assertEquals("live", File(to, "kept.bin").readText())
    }

    @Test
    fun replacesUidStubSoLeftoverAudioIsNotWiped() {
        val from = tmp.newFolder("files-audio")
        File(from, "ayah.bin").writeText("mp3")
        val to = tmp.newFolder("cache-audio")
        File(to, "123.uid").writeText("1")
        File(to, ".lock").writeText("")
        relocateAudioDir(from, to)
        assertFalse(from.exists())
        assertEquals("mp3", File(to, "ayah.bin").readText())
        assertFalse(File(to, "123.uid").exists())
    }

    @Test
    fun dropsStubOnlySource() {
        val from = tmp.newFolder("files-audio")
        File(from, "9.uid").writeText("9")
        val to = tmp.newFolder("cache-audio")
        File(to, "ayah.bin").writeText("mp3")
        relocateAudioDir(from, to)
        assertFalse(from.exists())
        assertEquals("mp3", File(to, "ayah.bin").readText())
    }

    @Test
    fun legacyRelocationNeverMovesNewPermanentAudioOnLaterStarts() {
        val marker = File(tmp.root, "legacy-moved")
        val from = tmp.newFolder("files-audio")
        File(from, "old.bin").writeText("old-listen")
        val to = File(tmp.root, "cache-audio")

        relocateLegacyAudioOnce(marker, from, to)
        assertFalse(from.exists())
        assertEquals("old-listen", File(to, "old.bin").readText())

        from.mkdirs()
        File(from, "download.bin").writeText("permanent")
        to.deleteRecursively()
        relocateLegacyAudioOnce(marker, from, to)

        assertEquals("permanent", File(from, "download.bin").readText())
        assertFalse(to.exists())
    }

    private fun span(key: String, length: Long, touched: Long): CacheSpan =
        CacheSpan(key, /* position = */ 0, length, touched, File(key))
}
