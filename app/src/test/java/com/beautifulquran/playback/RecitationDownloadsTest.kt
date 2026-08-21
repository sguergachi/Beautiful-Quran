package com.beautifulquran.playback

import com.beautifulquran.data.model.Reciter
import com.beautifulquran.data.model.Surah
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecitationDownloadsTest {

    private val fatiha = Surah(1, "الفاتحة", "Al-Fatihah", "The Opening", "Makkah", 7)
    private val baqarah = Surah(2, "البقرة", "Al-Baqarah", "The Cow", "Madinah", 286)
    private val imran = Surah(3, "آل عمران", "Ali 'Imran", "Family of Imran", "Madinah", 200)
    private val ikhlas = Surah(112, "الإخلاص", "Al-Ikhlas", "Sincerity", "Makkah", 4)
    private val alafasy = Reciter(1, "Alafasy_128kbps", "Mishary Alafasy", "murattal", true)
    private val husary = Reciter(2, "Husary_128kbps", "Mahmoud Khalil Al-Husary", "murattal", true)

    @Test
    fun countCachedAyahsCountsOnlyHits() {
        assertEquals(0, countCachedAyahs(7) { false })
        assertEquals(7, countCachedAyahs(7) { true })
        assertEquals(2, countCachedAyahs(7) { it == 1 || it == 7 })
    }

    @Test
    fun chapterStateLabelIsDownloadOrSizeOrProgress() {
        val empty = ChapterDownload(fatiha, 0)
        val partial = ChapterDownload(fatiha, 3, bytes = 4L * 1024 * 1024)
        val done = ChapterDownload(fatiha, 7, bytes = 12L * 1024 * 1024)
        assertEquals("80/206 verses", chapterDownloadCountLabel(completed = 80, total = 206))
        assertEquals("0/1 verse", chapterDownloadCountLabel(completed = -1, total = 1))
        assertEquals("7/7 verses", chapterDownloadCountLabel(completed = 9, total = 7))
        assertEquals("80/206 verses · 38%", chapterProgressFactLine(80, 206, percent = 38))
        assertEquals("80/206 verses", chapterProgressFactLine(80, 206, percent = null))
        assertEquals("7 verses", chapterFactLine(empty, downloading = false, waiting = false, paused = false))
        assertEquals("Download", chapterActionLabel(empty, downloading = false, waiting = false, paused = false))
        assertEquals(
            "7 verses · 3 of 7 · 4 MB",
            chapterFactLine(partial, downloading = false, waiting = false, paused = false),
        )
        assertEquals("Resume", chapterActionLabel(partial, downloading = false, waiting = false, paused = false))
        assertEquals(
            "7 verses · 12 MB",
            chapterFactLine(done, downloading = false, waiting = false, paused = false),
        )
        assertEquals("Delete", chapterActionLabel(done, downloading = false, waiting = false, paused = false))
        assertEquals(
            "7 verses · 4%",
            chapterFactLine(empty, downloading = true, waiting = false, paused = false, percent = 4),
        )
        assertEquals("Pause", chapterActionLabel(empty, downloading = true, waiting = false, paused = false))
        assertEquals(
            "7 verses · Waiting",
            chapterFactLine(empty, downloading = false, waiting = true, paused = false),
        )
        assertEquals("Pause", chapterActionLabel(empty, downloading = false, waiting = true, paused = false))
        assertEquals(
            "7 verses · 3 of 7 · 4 MB",
            chapterFactLine(partial, downloading = false, waiting = false, paused = true),
        )
        assertEquals("Resume", chapterActionLabel(partial, downloading = false, waiting = false, paused = true))
        assertTrue(chapterOffersDelete(partial, downloading = false, waiting = false, paused = true))
        assertEquals(listOf("Delete", "Resume"), chapterTrailingLabels("Resume", alsoDelete = true))
        assertEquals(listOf("Pause"), chapterTrailingLabels("Pause", alsoDelete = false))
        assertEquals(listOf("Download"), chapterTrailingLabels("Download", alsoDelete = false))
        assertTrue(chapterOffersDelete(partial, downloading = false, waiting = false, paused = false))
        assertFalse(chapterOffersDelete(empty, downloading = false, waiting = false, paused = false))
        assertFalse(chapterOffersDelete(done, downloading = false, waiting = false, paused = false))
        assertFalse(chapterOffersDelete(empty, downloading = true, waiting = false, paused = false))
        assertEquals(3, downloadPercent(ayah = 10, ayahCount = 286))
        assertEquals(1, downloadPercent(ayah = 1, ayahCount = 286))
        assertEquals(42, downloadPercent(ayah = 3, ayahCount = 7))
        assertEquals(100, downloadPercent(ayah = 7, ayahCount = 7))
        assertEquals(0, downloadPercent(ayah = 0, ayahCount = 7))
        assertNull(downloadPercent(ayah = 1, ayahCount = 0))
        assertEquals(
            0f,
            chapterProgressFraction(
                downloading = true, paused = false, percent = null, complete = false,
            ),
        )
        assertEquals(
            0.42f,
            chapterProgressFraction(
                downloading = true, paused = false, percent = 42, complete = false,
            ),
        )
        assertEquals(
            1f,
            chapterProgressFraction(
                downloading = true, paused = false, percent = 120, complete = false,
            ),
        )
        assertEquals(
            1f,
            chapterProgressFraction(
                downloading = false, paused = false, percent = null, complete = true,
            ),
        )
        assertEquals(
            0.42f,
            chapterProgressFraction(
                downloading = false, paused = true, percent = 42, complete = false,
            ),
        )
        assertNull(
            chapterProgressFraction(
                downloading = false, paused = false, percent = 42, complete = false,
            ),
        )
        val live = DownloadProgress(
            running = true,
            reciterId = 1,
            surahId = 2,
            ayah = 10,
            ayahCount = 286,
        )
        assertEquals(10 to 286, retainedDownloadClock(1, 2, live, ayah = null, ayahCount = null))
        assertEquals(0 to 0, retainedDownloadClock(1, 3, live, ayah = null, ayahCount = null))
        assertEquals(12 to 286, retainedDownloadClock(1, 2, live, ayah = 12, ayahCount = 286))
        assertTrue(chapterStateIsLive(downloading = true, waiting = false))
        assertFalse(chapterStateIsLive(downloading = false, waiting = false))
        assertTrue(chapterStateIsAction(ChapterDownload(fatiha, 0), downloading = false, waiting = false))
        assertFalse(chapterStateIsAction(ChapterDownload(fatiha, 7), downloading = false, waiting = false))
        assertTrue(chapterStateIsAction(ChapterDownload(fatiha, 3), downloading = false, waiting = false))
        assertTrue(ChapterDownload(fatiha, 7).complete)
        assertFalse(ChapterDownload(fatiha, 3).complete)
        assertTrue(ChapterDownload(fatiha, 0).empty)
        assertFalse(ChapterDownload(fatiha, 0, bytes = 1).empty)
    }

    @Test
    fun pausingActiveChapterLeavesEveryWaitingChapterRunnable() {
        val first = DownloadRequest(alafasy, baqarah)
        val second = DownloadRequest(alafasy, imran)
        val otherReciter = DownloadRequest(husary, fatiha)
        val queue = DownloadQueue().apply { enqueue(listOf(first, second, otherReciter)) }

        assertEquals(first, queue.takeNext())
        assertTrue(queue.pauseChapter(first.ref, DownloadClock(12, 286)))
        assertTrue(queue.cancellingActive)
        assertEquals(listOf(first.ref), queue.pausedDownloads.map { it.request.ref })
        assertEquals(listOf(second, otherReciter), queue.waitingRequests)
        val pausedProgress = downloadProgress(queue)
        assertTrue(pausedProgress.running)
        assertNull(pausedProgress.active)
        assertEquals(setOf(first.ref), pausedProgress.pausedChapters)
        assertEquals(DownloadClock(12, 286), pausedProgress.pausedClocks[first.ref])
        assertTrue(isChapterPaused(pausedProgress, first.reciter.id, first.surah.id))
        assertTrue(isChapterWaiting(pausedProgress, second.reciter.id, second.surah.id))

        queue.finish(first)
        assertEquals(second, queue.takeNext())
        val continued = downloadProgress(queue, ayah = 4, ayahCount = 200)
        assertEquals(second.ref, continued.active)
        assertEquals(setOf(first.ref), continued.pausedChapters)
        assertEquals(listOf(otherReciter.ref), continued.queued)
    }

    @Test
    fun pausingWaitingChapterDoesNotTouchActiveOrNeighbors() {
        val first = DownloadRequest(alafasy, fatiha)
        val paused = DownloadRequest(alafasy, baqarah)
        val last = DownloadRequest(husary, ikhlas)
        val queue = DownloadQueue().apply {
            enqueue(listOf(first, paused, last))
            takeNext()
        }

        assertFalse(queue.pauseChapter(paused.ref))
        assertEquals(first, queue.active)
        assertFalse(queue.cancellingActive)
        assertEquals(listOf(last), queue.waitingRequests)
        assertEquals(listOf(paused.ref), queue.pausedDownloads.map { it.request.ref })
        assertFalse(queue.pauseChapter(ChapterRef(7, 7)))
    }

    @Test
    fun pausingReciterMovesOnlyThatRecitersWorkOutOfTheQueue() {
        val active = DownloadRequest(alafasy, fatiha)
        val sameReciter = DownloadRequest(alafasy, baqarah)
        val otherReciter = DownloadRequest(husary, ikhlas)
        val queue = DownloadQueue().apply {
            enqueue(listOf(active, sameReciter, otherReciter))
            takeNext()
        }

        assertTrue(queue.pauseReciter(alafasy.id, DownloadClock(3, 7)))
        assertEquals(listOf(active, sameReciter), queue.pausedDownloads.map { it.request })
        assertEquals(listOf(otherReciter), queue.waitingRequests)
        queue.finish(active)
        assertEquals(otherReciter, queue.takeNext())
    }

    @Test
    fun pausingQueuedReciterDoesNotInterruptAnotherRecitersActiveDownload() {
        val active = DownloadRequest(husary, fatiha)
        val alafasyFirst = DownloadRequest(alafasy, baqarah)
        val alafasySecond = DownloadRequest(alafasy, imran)
        val queue = DownloadQueue().apply {
            enqueue(listOf(active, alafasyFirst, alafasySecond))
            takeNext()
        }

        assertFalse(queue.pauseReciter(alafasy.id))
        assertEquals(active, queue.active)
        assertFalse(queue.cancellingActive)
        assertTrue(queue.waitingRequests.isEmpty())
        assertEquals(
            listOf(alafasyFirst, alafasySecond),
            queue.pausedDownloads.map { it.request },
        )
    }

    @Test
    fun resumingOneReciterLeavesOtherPausedWorkAlone() {
        val alafasyFirst = DownloadRequest(alafasy, fatiha)
        val alafasySecond = DownloadRequest(alafasy, baqarah)
        val husaryRequest = DownloadRequest(husary, ikhlas)
        val queue = DownloadQueue().apply {
            enqueue(listOf(alafasyFirst, alafasySecond, husaryRequest))
            pauseReciter(alafasy.id)
            pauseReciter(husary.id)
        }

        queue.resumeReciter(alafasy.id)
        assertEquals(listOf(alafasyFirst, alafasySecond), queue.waitingRequests)
        assertEquals(listOf(husaryRequest), queue.pausedDownloads.map { it.request })
    }

    @Test
    fun resumingOneChapterLeavesOtherPausedChaptersAlone() {
        val first = DownloadRequest(alafasy, fatiha)
        val second = DownloadRequest(husary, ikhlas)
        val queue = DownloadQueue().apply {
            enqueue(listOf(first, second))
            pauseChapter(first.ref)
            pauseChapter(second.ref)
        }

        queue.enqueue(listOf(first))
        assertEquals(listOf(first), queue.waitingRequests)
        assertEquals(listOf(second), queue.pausedDownloads.map { it.request })
    }

    @Test
    fun rapidResumeQueuesCancelledActiveForAFullRetry() {
        val first = DownloadRequest(alafasy, fatiha)
        val second = DownloadRequest(husary, ikhlas)
        val queue = DownloadQueue().apply {
            enqueue(listOf(first, second))
            takeNext()
            pauseChapter(first.ref, DownloadClock(3, 7))
        }

        queue.enqueue(listOf(first))
        assertTrue(queue.cancellingActive)
        assertEquals(listOf(second, first), queue.waitingRequests)
        assertTrue(queue.pausedDownloads.isEmpty())
        queue.finish(first)
        assertEquals(second, queue.takeNext())
        queue.finish(second)
        assertEquals(first, queue.takeNext())
    }

    @Test
    fun deletionRemovesPausedAndWaitingWorkWithoutStoppingNeighbors() {
        val active = DownloadRequest(alafasy, fatiha)
        val paused = DownloadRequest(alafasy, baqarah)
        val waiting = DownloadRequest(husary, ikhlas)
        val queue = DownloadQueue().apply {
            enqueue(listOf(active, paused, waiting))
            takeNext()
            pauseChapter(paused.ref)
        }

        assertFalse(queue.removeChapter(paused.ref))
        assertTrue(queue.pausedDownloads.isEmpty())
        assertEquals(listOf(waiting), queue.waitingRequests)
        assertTrue(queue.removeChapter(active.ref))
        assertTrue(queue.cancellingActive)
        assertEquals(listOf(waiting), queue.waitingRequests)
    }

    @Test
    fun deletingReciterPreservesOtherRecitersActiveAndWaitingWork() {
        val active = DownloadRequest(husary, fatiha)
        val alafasyFirst = DownloadRequest(alafasy, baqarah)
        val alafasySecond = DownloadRequest(alafasy, imran)
        val queue = DownloadQueue().apply {
            enqueue(listOf(active, alafasyFirst, alafasySecond))
            takeNext()
            pauseChapter(alafasyFirst.ref)
        }

        assertFalse(queue.removeReciter(alafasy.id))
        assertEquals(active, queue.active)
        assertFalse(queue.cancellingActive)
        assertTrue(queue.waitingRequests.isEmpty())
        assertTrue(queue.pausedDownloads.isEmpty())
    }

    @Test
    fun staleWorkerFinishCannotClearNewSameChapterRequest() {
        val old = DownloadRequest(alafasy, fatiha)
        val fresh = DownloadRequest(alafasy, fatiha)
        val queue = DownloadQueue().apply {
            enqueue(listOf(old))
            takeNext()
            clear()
            enqueue(listOf(fresh))
            takeNext()
        }

        queue.finish(old)
        assertTrue(queue.active === fresh)
        assertNull(queue.takeNext())
        queue.finish(fresh)
        assertNull(queue.active)
    }

    @Test
    fun clearingQueueDropsWaitingAndPausedWork() {
        val active = DownloadRequest(alafasy, fatiha)
        val paused = DownloadRequest(alafasy, baqarah)
        val waiting = DownloadRequest(husary, ikhlas)
        val queue = DownloadQueue().apply {
            enqueue(listOf(active, paused, waiting))
            takeNext()
            pauseChapter(paused.ref)
        }

        queue.clear()
        queue.clear()
        assertNull(queue.active)
        assertTrue(queue.waitingRequests.isEmpty())
        assertTrue(queue.pausedDownloads.isEmpty())
        assertTrue(queue.cancellingActive)
    }

    @Test
    fun collapsedResumeDoesNotEnqueueEmptyChapters() {
        val empty = ChapterDownload(fatiha, 0)
        val partial = ChapterDownload(ikhlas, 2, bytes = 1_000)
        assertEquals(emptyList<Surah>(), reciterResumeSurahs(listOf(empty, partial), paused = true))
        assertEquals(listOf(ikhlas), reciterResumeSurahs(listOf(empty, partial), paused = false))
    }

    @Test
    fun mergeDownloadQueueAppendsNewChaptersWithoutDuplicates() {
        val first = DownloadRequest(alafasy, fatiha)
        val second = DownloadRequest(alafasy, ikhlas)
        val husaryFatiha = DownloadRequest(husary, fatiha)
        val merged = mergeDownloadQueue(
            pending = listOf(first),
            incoming = listOf(first, second, husaryFatiha),
            active = first,
        )
        assertEquals(listOf(first, second, husaryFatiha), merged)
        val afterActive = mergeDownloadQueue(
            pending = listOf(second),
            incoming = listOf(first),
            active = first,
        )
        assertEquals(listOf(second), afterActive)
    }

    @Test
    fun queueProcessesUniqueRequestsOnceInFifoOrder() {
        val first = DownloadRequest(alafasy, fatiha)
        val second = DownloadRequest(alafasy, baqarah)
        val third = DownloadRequest(husary, ikhlas)
        val queue = DownloadQueue().apply {
            enqueue(listOf(first, first, second))
        }

        assertEquals(first, queue.takeNext())
        queue.enqueue(listOf(first, second, third, third))
        assertEquals(listOf(second, third), queue.waitingRequests)
        queue.finish(first)
        assertEquals(second, queue.takeNext())
        queue.finish(second)
        assertEquals(third, queue.takeNext())
        queue.finish(third)
        assertNull(queue.takeNext())
    }

    @Test
    fun chapterBusyFlagsFollowActiveAndQueue() {
        val progress = DownloadProgress(
            running = true,
            active = ChapterRef(1, 1),
            reciterId = 1,
            surahId = 1,
            queued = listOf(ChapterRef(1, 112), ChapterRef(2, 1)),
        )
        assertTrue(isChapterDownloading(progress, 1, 1))
        assertFalse(isChapterDownloading(progress, 1, 112))
        assertTrue(isChapterWaiting(progress, 1, 112))
        assertTrue(isChapterWaiting(progress, 2, 1))
        assertFalse(isChapterWaiting(progress, 1, 1))
        val paused = DownloadProgress(
            running = true,
            active = ChapterRef(2, 1),
            pausedChapters = setOf(ChapterRef(1, 1)),
            pausedClocks = mapOf(ChapterRef(1, 1) to DownloadClock(3, 7)),
            reciterName = husary.name,
            surahName = fatiha.nameTransliteration,
            reciterId = 2,
            surahId = 1,
            queued = listOf(ChapterRef(1, 112)),
        )
        assertTrue(isChapterPaused(paused, 1, 1))
        assertFalse(isChapterPaused(paused, 1, 112))
        assertFalse(isChapterPaused(paused, 2, 1))
        assertTrue(isChapterWaiting(paused, 1, 112))
        assertFalse(isChapterWaiting(paused, 2, 1))
        val reconciling = paused.copy(
            reconciling = mapOf(ChapterRef(1, 112) to 4L, ChapterRef(2, 1) to 5L),
        )
        assertTrue(isChapterReconciling(reconciling, 1, 112))
        assertFalse(isChapterReconciling(reconciling, 1, 1))
        assertTrue(isReciterReconciling(reconciling, 1))
        assertTrue(isReciterReconciling(reconciling, 2))
        assertFalse(isReciterReconciling(reconciling, 3))
        assertFalse(isChapterActionSettling(reconciling, 1, 112))
        assertFalse(isChapterActionSettling(reconciling, 1, 1))
        assertFalse(isReciterActionSettling(reconciling, 1))
        val settled = DownloadProgress(
            reconciling = mapOf(ChapterRef(1, 112) to 4L),
        )
        assertTrue(isChapterActionSettling(settled, 1, 112))
        assertTrue(isReciterActionSettling(settled, 1))
        assertEquals(
            "Resume",
            chapterActionLabel(
                ChapterDownload(ikhlas, 0),
                downloading = false,
                waiting = false,
                paused = true,
            ),
        )
        assertTrue(
            chapterOffersDelete(
                ChapterDownload(ikhlas, 0),
                downloading = false,
                waiting = false,
                paused = true,
            ),
        )
        assertTrue(isReciterDownloading(progress, 1))
        assertFalse(isReciterDownloading(progress, 2))
        assertTrue(isReciterBusy(progress, 1))
        assertTrue(isReciterBusy(progress, 2))
        assertEquals(
            "1 waiting",
            reciterProgressLabel(
                DownloadProgress(running = true, queued = listOf(ChapterRef(1, 2))),
                reciterId = 1,
            ),
        )
        val live = progress.copy(
            reciterName = "Mishary Alafasy",
            surahName = "Al-Fatihah",
            ayah = 3,
            ayahCount = 7,
        )
        assertEquals("Al-Fatihah · 42% · 1 waiting", reciterProgressLabel(live, reciterId = 1))
        assertEquals(
            "Al-Fatihah · 42% · 1 waiting",
            reciterProgressLabel(
                DownloadProgress(
                    pausedChapters = setOf(ChapterRef(1, 1)),
                    pausedClocks = mapOf(ChapterRef(1, 1) to DownloadClock(3, 7)),
                    reciterName = alafasy.name,
                    surahName = fatiha.nameTransliteration,
                    reciterId = 1,
                    surahId = 1,
                    ayah = 3,
                    ayahCount = 7,
                    queued = listOf(ChapterRef(1, 112)),
                ),
                reciterId = 1,
            ),
        )
        assertEquals("1 waiting", reciterProgressLabel(live, reciterId = 2))
        assertEquals("", reciterProgressLabel(DownloadProgress(), reciterId = 1))
        assertEquals(
            "Al-Fatihah · 42% · 1 waiting",
            reciterHeaderSubtitle(
                expanded = false,
                liveLabel = "Al-Fatihah · 42% · 1 waiting",
                catalogLabel = "1 of 114 chapters",
            ),
        )
        assertEquals(
            "1 of 114 chapters",
            reciterHeaderSubtitle(
                expanded = true,
                liveLabel = "Al-Fatihah · 42% · 1 waiting",
                catalogLabel = "1 of 114 chapters",
            ),
        )
        assertEquals(
            "Pause",
            reciterHeaderAction(
                expanded = false,
                busy = true,
                paused = false,
                hasDownloadable = true,
                hasBytes = true,
                confirming = false,
            ),
        )
        assertEquals(
            "Pause",
            reciterHeaderAction(
                expanded = false,
                busy = true,
                paused = true,
                hasDownloadable = true,
                hasBytes = true,
                confirming = false,
            ),
        )
        assertEquals(
            "Resume",
            reciterHeaderAction(
                expanded = false,
                busy = false,
                paused = true,
                hasDownloadable = true,
                hasBytes = true,
                confirming = false,
            ),
        )
        assertEquals(
            "Resume",
            reciterHeaderAction(
                expanded = false,
                busy = false,
                paused = false,
                hasDownloadable = true,
                hasBytes = true,
                confirming = false,
                hasResumable = true,
            ),
        )
        assertEquals(
            "Delete",
            reciterHeaderAction(
                expanded = false,
                busy = false,
                paused = false,
                hasDownloadable = true,
                hasBytes = true,
                confirming = false,
            ),
        )
        assertEquals(
            "Download all",
            reciterHeaderAction(
                expanded = true,
                busy = true,
                paused = false,
                hasDownloadable = true,
                hasBytes = true,
                confirming = false,
            ),
        )
        assertEquals(
            null,
            reciterHeaderAction(
                expanded = true,
                busy = true,
                paused = false,
                hasDownloadable = false,
                hasBytes = true,
                confirming = false,
            ),
        )
        assertTrue(chapterActionIsFetch("Download"))
        assertTrue(chapterActionIsFetch("Pause"))
        assertTrue(chapterActionIsFetch("Resume"))
        assertFalse(chapterActionIsFetch("Delete"))
        assertTrue(reciterHeaderActionIsFetch("Download all"))
        assertTrue(reciterHeaderActionIsFetch("Pause"))
        assertTrue(reciterHeaderActionIsFetch("Resume"))
        assertFalse(reciterHeaderActionIsFetch("Delete"))
    }

    @Test
    fun staleScanCannotAcknowledgeANewerStorageTransition() {
        val fatiha = ChapterRef(1, 1)
        val ikhlas = ChapterRef(1, 112)
        assertEquals(
            mapOf(fatiha to 3L),
            remainingReconciliations(
                current = mapOf(fatiha to 3L, ikhlas to 2L),
                acknowledged = mapOf(fatiha to 2L, ikhlas to 2L),
            ),
        )
    }

    @Test
    fun parseEveryayahUriReadsSlugAndAyah() {
        assertEquals(
            EveryayahRef("Alafasy_128kbps", 1, 1),
            parseEveryayahUri("https://everyayah.com/data/Alafasy_128kbps/001001.mp3"),
        )
        assertEquals(
            EveryayahRef("Husary_128kbps", 2, 286),
            parseEveryayahUri("https://everyayah.com/data/Husary_128kbps/002286.mp3"),
        )
        assertEquals(
            EveryayahRef("Alafasy_128kbps", 1, 1),
            parseEveryayahUri("http://everyayah.com/data/Alafasy_128kbps/001001.mp3?x=1"),
        )
        assertNull(parseEveryayahUri("https://example.com/001001.mp3"))
        assertNull(parseEveryayahUri("not-a-uri"))
    }

    @Test
    fun hitsFromCacheKeysReadMediaIds() {
        val keys = listOf("1:1:1", "1:2:1", "112:1:2", "junk")
        val hits = hitsFromCacheKeys(
            keys = keys,
            reciters = listOf(alafasy, husary),
            cachedBytes = { 1_024L },
            fullyCached = { true },
        )
        assertEquals(
            setOf(
                EveryayahRef("Alafasy_128kbps", 1, 1),
                EveryayahRef("Alafasy_128kbps", 1, 2),
                EveryayahRef("Husary_128kbps", 112, 1),
            ),
            hits.keys,
        )
        assertEquals(
            CachedAyah(bytes = 1_024L, complete = true),
            hits[EveryayahRef("Alafasy_128kbps", 1, 1)],
        )
    }

    @Test
    fun hitsFromCacheKeysIgnoreUnsetLengthAndEmptySpans() {
        val keys = listOf(
            "https://everyayah.com/data/Alafasy_128kbps/001001.mp3",
            "https://everyayah.com/data/Alafasy_128kbps/001002.mp3",
            "https://everyayah.com/data/Husary_128kbps/112001.mp3",
            "orphan-key",
        )
        val hits = hitsFromCacheKeys(
            keys = keys,
            cachedBytes = { key -> if (key.endsWith("001002.mp3")) 0L else 1_024L },
            fullyCached = { true },
        )
        assertEquals(
            setOf(
                EveryayahRef("Alafasy_128kbps", 1, 1),
                EveryayahRef("Husary_128kbps", 112, 1),
            ),
            hits.keys,
        )
    }

    @Test
    fun reciterDownloadsSplitsHitsAcrossReciters() {
        val hits = setOf(
            EveryayahRef("Alafasy_128kbps", 1, 1),
            EveryayahRef("Alafasy_128kbps", 1, 2),
            EveryayahRef("Alafasy_128kbps", 1, 3),
            EveryayahRef("Alafasy_128kbps", 1, 4),
            EveryayahRef("Alafasy_128kbps", 1, 5),
            EveryayahRef("Alafasy_128kbps", 1, 6),
            EveryayahRef("Alafasy_128kbps", 1, 7),
            EveryayahRef("Husary_128kbps", 112, 1),
        )
        val bytes = hits.associateWith { CachedAyah(bytes = 1_024L, complete = true) }
        val rows = reciterDownloads(listOf(alafasy, husary), listOf(fatiha, ikhlas), bytes)
        assertEquals(
            "All chapters · < 1 MB",
            reciterDownloadLabel(rows[0].copy(chapters = listOf(rows[0].chapters[0]))),
        )
        assertEquals(
            "7 verses · < 1 MB",
            chapterFactLine(rows[0].chapters[0], downloading = false, waiting = false, paused = false),
        )
        assertEquals(
            "4 verses",
            chapterFactLine(rows[0].chapters[1], downloading = false, waiting = false, paused = false),
        )
        assertEquals(
            "4 verses · 1 of 4 · < 1 MB",
            chapterFactLine(rows[1].chapters[1], downloading = false, waiting = false, paused = false),
        )
        assertEquals("0 of 2 · < 1 MB", reciterDownloadLabel(rows[1]))
        assertEquals(7_168L, rows[0].chapters[0].bytes)
    }

    @Test
    fun partialFinalAyahKeepsChapterResumable() {
        val ayahs = (1..7).associate { ayah ->
            EveryayahRef(alafasy.slug, 1, ayah) to CachedAyah(
                bytes = 1_024L,
                complete = ayah < 7,
            )
        }
        val chapter = reciterDownloads(listOf(alafasy), listOf(fatiha), ayahs)
            .single().chapters.single()

        assertEquals(6, chapter.cached)
        assertFalse(chapter.complete)
        assertFalse(chapter.empty)
        assertEquals(
            "0 of 1 · < 1 MB",
            reciterDownloadLabel(ReciterDownloads(alafasy, listOf(chapter))),
        )
        assertEquals(
            "Resume",
            chapterActionLabel(chapter, downloading = false, waiting = false, paused = false),
        )
    }

    @Test
    fun chapterDownloadIncludesItsPlaybackBasmalah() {
        val ikhlasRequests = chapterAudioRequests(alafasy, ikhlas)
        assertEquals(0, ikhlasRequests.first().first)
        assertEquals(alafasy.basmalahAudioUrl(), ikhlasRequests.first().second)
        assertEquals(ikhlas.ayahCount + 1, ikhlasRequests.size)

        assertEquals(fatiha.ayahCount, chapterAudioRequests(alafasy, fatiha).size)
        val tawbah = Surah(9, "التوبة", "At-Tawbah", "Repentance", "Madinah", 129)
        assertEquals(tawbah.ayahCount, chapterAudioRequests(alafasy, tawbah).size)
    }

    @Test
    fun cacheKeysForChapterKeepsOnlyThatSurah() {
        val keys = listOf(
            "https://everyayah.com/data/Alafasy_128kbps/001001.mp3",
            "https://everyayah.com/data/Alafasy_128kbps/112001.mp3",
            "1:2:1",
            "112:1:1",
            "1:1:2",
        )
        assertEquals(
            listOf(
                "https://everyayah.com/data/Alafasy_128kbps/001001.mp3",
                "1:2:1",
            ),
            cacheKeysForChapter(keys, alafasy, surahId = 1),
        )
    }
}
