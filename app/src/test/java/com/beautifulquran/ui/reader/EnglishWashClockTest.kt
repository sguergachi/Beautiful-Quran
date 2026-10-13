package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.EnglishWordAlignment
import com.beautifulquran.domain.EnglishAlignment
import com.beautifulquran.domain.EnglishWordSpan
import com.beautifulquran.domain.EnglishVerseRun
import com.beautifulquran.domain.englishBookOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishWashClockTest {
    private val tail = listOf(
        Segment(22, 25530, 26800), Segment(23, 26800, 28490),
        Segment(24, 28490, 32560), Segment(25, 32560, 32930), Segment(26, 32930, 36250),
    )

    @Test
    fun `An Nur punishment wash continues until the group of believers finishes`() {
        val (_, text, glosses) = javaClass.getResourceAsStream("/english-quran-ai-opening-owners.tsv")!!
            .bufferedReader().use { it.readLines().first { row -> row.startsWith("24:2\t") } }.split('\t')
        val alignment = EnglishWordAlignment.align(text, glosses.split('|'))!!
        val window = englishWashWindows(alignment.washSpans.map { it.position }, tail).getValue(23)
        assertEquals(EnglishWashWindow(26800, 36250), window)
        assertTrue("punishment ending must not finish the verse", window.progress(28490) < 1f)
        assertTrue("held group keeps moving", window.progress(32000) > window.progress(29000))
        assertTrue("last Arabic word is still being said", window.progress(36249) < 1f)
        assertEquals(1f, window.progress(36250), 0f)
    }

    @Test
    fun `adjacent portions meet at the spoken boundary with no skipped ink`() {
        val windows = englishWashWindows(listOf(22, 23), tail)
        assertEquals(EnglishWashWindow(25530, 26800), windows.getValue(22))
        assertEquals(1f, windows.getValue(22).progress(26800), 0f)
        assertEquals(0f, windows.getValue(23).progress(26800), 0f)
    }

    @Test
    fun `pausing does not advance and speed changes follow media time`() {
        val window = EnglishWashWindow(1000, 13000)
        val word = ActiveWord(2, 23, startMs = 1000, durationMs = 12000, activation = 8)
        val clock = EnglishWashClock(24, 2, 8, 4000, 1000)
        val paused = window.progress(englishWashPositionMs(word, clock, 24))
        // Repeated draws through an arbitrarily long pause read the same sample.
        repeat(100) { assertEquals(paused, window.progress(englishWashPositionMs(word, clock, 24)), 0f) }
        assertEquals(0.5f, window.progress(clock.copy(positionMs = 7000).positionMs), 0f)
        assertEquals(0.75f, window.progress(clock.copy(positionMs = 10000).positionMs), 0f)
        assertTrue("an eight-second animation cap must not finish a twelve-second hold", window.progress(9000) < 1f)
    }

    @Test
    fun `new seek and chapter cannot consume the previous clock`() {
        val word = ActiveWord(2, 23, startMs = 26800, durationMs = 1690, activation = 9)
        val stale = EnglishWashClock(24, 2, 8, 36250, 0)
        assertEquals(word.startMs, englishWashPositionMs(word, stale, 24))
        assertEquals(word.startMs, englishWashPositionMs(word, stale.copy(activation = 9, surahId = 25), 24))
        assertEquals(word.startMs, englishWashPositionMs(word, stale.copy(activation = 9, ayah = 3), 24))
        assertEquals(28000L, englishWashPositionMs(word, stale.copy(activation = 9, positionMs = 28000), 24))
    }

    @Test
    fun `frame interpolation resets on a new verse chapter or seek`() {
        val sample = EnglishWashClock(24, 1, 8, 15000, 0)
        assertTrue(sample.copy(positionMs = 15033).samePerformanceAs(sample))
        assertTrue(!sample.samePerformanceAs(null))
        assertTrue(!sample.copy(activation = 9).samePerformanceAs(sample))
        assertTrue(!sample.copy(ayah = 2).samePerformanceAs(sample))
        assertTrue(!sample.copy(surahId = 25).samePerformanceAs(sample))
        assertEquals(sample, sample.frameAt(20_000_000_000L, sample.copy(activation = 9)))
    }

    @Test
    fun `every frame moves between polls without restarting or guessing future audio`() {
        val previous = EnglishWashClock(24, 1, 8, 1000, 0, sampledAtNanos = 1_000_000_000L)
        val next = previous.copy(positionMs = 1033, sampledAtNanos = 1_033_000_000L)
        val frames = listOf(1041L, 1049L, 1057L, 1065L).map { next.frameAt(it * 1_000_000, previous).positionMs }
        assertEquals(listOf(1008L, 1016L, 1024L, 1032L), frames)
        val following = next.copy(positionMs = 1066, sampledAtNanos = 1_066_000_000L)
        assertEquals(1040L, following.frameAt(1_073_000_000L, next).positionMs)
        assertEquals(next.positionMs, next.frameAt(9_000_000_000L, previous).positionMs)
    }

    @Test
    fun `paused samples freeze and changing speed changes the frame slope`() {
        val previous = EnglishWashClock(24, 1, 8, 1000, 0, sampledAtNanos = 1_000_000_000L)
        val paused = previous.copy(sampledAtNanos = 1_033_000_000L)
        assertEquals(1000L, paused.frameAt(9_000_000_000L, previous).positionMs)
        val faster = paused.copy(positionMs = 1066)
        assertEquals(1032L, faster.frameAt(1_049_000_000L, previous).positionMs)
    }

    @Test
    fun `a repeat completes prior ink even while the frame animation trails its poll`() {
        val repeated = ActiveWord(1, 4, startMs = 15640, durationMs = 3250, activation = 8, isRepeat = true, highWater = 7)
        val frame = EnglishWashClock(24, 1, 8, 15620, 0)
        val position = englishWashPositionMs(repeated, frame, 24)
        assertEquals(15640L, position)
        assertEquals(1f, EnglishWashWindow(12680, 15640).progress(position), 0f)
        assertEquals(0f, EnglishWashWindow(15640, 18890).progress(position), 0f)
    }

    @Test
    fun `completing a carried first wash does not move the repeat away from its phrase`() {
        val alignment = EnglishAlignment(FloatArray(9), listOf(
            EnglishWordSpan(0f, 0.4f, 4), EnglishWordSpan(0.4f, 0.8f, 7), EnglishWordSpan(0.8f, 1f, 8),
        ))
        val segments = listOf(
            Segment(4, 0, 1000), Segment(7, 1000, 2000), Segment(4, 2000, 3000),
            Segment(7, 3000, 4000), Segment(8, 4000, 5000),
        )
        val clock = EnglishWashClock(24, 1, 8, 2200, 0, segments)
        val repeated = ActiveWord(1, 4, startMs = 2000, durationMs = 1000, activation = 8, isRepeat = true, highWater = 7)
        val windows = englishWashWindows(alignment.washSpans.map { it.position }, segments)
        val book = englishBookOf(listOf(
            listOf(EnglishVerseRun(24, 1, 0, 5)), listOf(EnglishVerseRun(24, 1, 5, 10)),
        ), { _, _ -> 350 }, { _, _ -> "abcdefghij" })
        val laid = englishWashThrough(alignment, windows, clock.positionMs, clock.voiceStartMs)!!
        assertEquals(2, mushafLeafNumber(book, 24, 1, 350, laid))
        val voice = if (clock.followsFirstWash(repeated, 24)) laid else
            mushafVerseThrough(repeated.wordPosition, 9, alignment = alignment)
        assertEquals(1, mushafLeafNumber(book, 24, 1, 350, voice))
        assertTrue(clock.followsFirstWash(repeated.copy(isRepeat = false), 24))
    }

    @Test
    fun `opening silence does not consume the wash and final audio silence does not extend it`() {
        val windows = englishWashWindows(listOf(1, 2), listOf(Segment(1, 0, 4000), Segment(2, 4000, 6000)))
        assertEquals(0f, windows.getValue(1).progress(3000, voiceStartMs = 3000), 0f)
        assertEquals(0.5f, windows.getValue(1).progress(3500, voiceStartMs = 3000), 0f)
        assertEquals(1f, windows.getValue(2).progress(6000), 0f)
    }

    @Test
    fun `the first wash completes before repeats inside its portion`() {
        val windows = englishWashWindows(listOf(1, 3), listOf(
            Segment(1, 0, 500), Segment(2, 500, 1000), Segment(1, 1000, 1500),
            Segment(2, 1500, 2000), Segment(3, 2000, 2500), Segment(2, 2500, 3000), Segment(3, 3000, 3500),
        ))
        assertEquals(EnglishWashWindow(0, 1000), windows.getValue(1))
        assertEquals(EnglishWashWindow(2000, 2500), windows.getValue(3))
        assertEquals(0.5f, windows.getValue(1).progress(500), 0f)
        assertEquals(1f, windows.getValue(1).progress(1000), 0f)
        assertEquals(1f, windows.getValue(1).progress(2500), 0f)
        assertEquals(1f, windows.getValue(3).progress(2500), 0f)
    }

    @Test
    fun `glimmer and repeat tint cannot get past a partially revealed phrase`() {
        assertEquals(0f, englishInkLayerProgress(20..29, 15f), 0f)
        assertEquals(0.5f, englishInkLayerProgress(20..29, 25f), 0f)
        assertEquals(1f, englishInkLayerProgress(20..29, 35f), 0f)
    }

    @Test
    fun `a carried phrase completes its first leaf before washing its second leaf`() {
        val span = EnglishInkSpan(0..9, 23, from = 0.4f, to = 1f)
        val firstLeaf: (Float) -> Float = { (it / 0.7f).coerceIn(0f, 1f) }
        val secondLeaf: (Float) -> Float = { ((it - 0.7f) / 0.3f).coerceIn(0f, 1f) }
        assertEquals(0.5f, englishWashFragmentProgress(span, 0.25f, firstLeaf), 0.00001f)
        assertEquals(0f, englishWashFragmentProgress(span, 0.25f, secondLeaf), 0f)
        assertEquals(1f, englishWashFragmentProgress(span, 0.5f, firstLeaf), 0.00001f)
        assertEquals(0f, englishWashFragmentProgress(span, 0.5f, secondLeaf), 0.00001f)
        assertEquals(0.5f, englishWashFragmentProgress(span, 0.75f, secondLeaf), 0.00001f)
    }

    @Test
    fun `calibration cannot rewind laid ink but a seek can`() {
        val previous = EnglishWashClock(24, 2, 8, 32000, 360, tail)
        val retuned = previous.copy(positionMs = 31000).after(previous)
        assertEquals(32000L, retuned.positionMs)
        assertEquals(27000L, previous.copy(activation = 9, positionMs = 27000).after(previous).positionMs)
        assertEquals(0L, previous.copy(ayah = 3, positionMs = 0).after(previous).positionMs)
        assertEquals(0L, previous.copy(surahId = 25, positionMs = 0).after(previous).positionMs)
    }

    @Test
    fun `a forward jump takes intervening English ink with it before a backward repeat`() {
        val windows = englishWashWindows(listOf(6, 7, 8, 9), listOf(
            Segment(6, 6530, 7290), Segment(8, 7290, 9150), Segment(7, 9150, 10190),
            Segment(8, 10190, 11060), Segment(9, 11060, 11440),
        ))
        assertEquals(EnglishWashWindow(7290, 7290), windows.getValue(7))
        assertEquals(EnglishWashWindow(7290, 9150), windows.getValue(8))
        assertEquals(1f, windows.getValue(7).progress(7290), 0f)
        assertEquals(1f, windows.getValue(8).progress(9150), 0f)
    }

    @Test
    fun `An Nur clear evidence is fully inked before and revealed repeats`() {
        val (_, text, glosses) = javaClass.getResourceAsStream("/english-clear-evidence-owners.tsv")!!
            .bufferedReader().use { it.readLines().first { row -> row.startsWith("24:1\t") } }.split('\t')
        val alignment = EnglishWordAlignment.align(text, glosses.split('|'))!!
        // Alafasy's 1..7,4..7,8,9 topology on a synthetic one-second clock.
        val segments = listOf(1, 2, 3, 4, 5, 6, 7, 4, 5, 6, 7, 8, 9).mapIndexed { index, position ->
            Segment(position, index * 1000L, (index + 1) * 1000L)
        }
        val windows = englishWashWindows(alignment.washSpans.map { it.position }, segments)
        assertEquals(EnglishWashWindow(6000, 7000), windows.getValue(7))
        assertTrue(windows.getValue(7).progress(6999) < 1f)
        alignment.washSpans.filter { it.position <= 7 }.forEach {
            assertEquals("unfinished band ${it.position} under repeat", 1f, windows.getValue(it.position).progress(7000), 0f)
        }
        assertEquals(0f, windows.getValue(8).progress(7000), 0f)
        assertEquals(1f, windows.getValue(7).progress(10000), 0f)
    }

    @Test
    fun `carried leaf follows the unfinished phrase and keeps a calibrated turn lead`() {
        val alignment = EnglishAlignment(FloatArray(26), listOf(
            EnglishWordSpan(0f, 0.6f, 22), EnglishWordSpan(0.6f, 1f, 23),
        ))
        val windows = englishWashWindows(listOf(22, 23), tail)
        val cut = 0.8f
        assertTrue(englishWashThrough(alignment, windows, 30000, 0)!! < cut)
        assertTrue(englishWashThrough(alignment, windows, 31000, 0)!! < cut)
        assertTrue(englishWashThrough(alignment, windows, 32000, 0)!! > cut)
        // A 500 ms turn lead previews media time at the current playback speed.
        assertTrue(englishWashThrough(alignment, windows, 31100 + (500 * 1.25f).toLong(), 0)!! > cut)
        assertEquals(1f, englishWashThrough(alignment, windows, 36250, 0)!!, 0f)
    }

    @Test
    fun `a cut before punishment stays on its first leaf when Arabic starts group`() {
        val (_, text, glosses) = javaClass.getResourceAsStream("/english-quran-ai-opening-owners.tsv")!!
            .bufferedReader().use { it.readLines().first { row -> row.startsWith("24:2\t") } }.split('\t')
        val alignment = EnglishWordAlignment.align(text, glosses.split('|'))!!
        val cut = text.indexOf("punishment")
        val book = englishBookOf(listOf(
            listOf(EnglishVerseRun(24, 2, 0, cut)), listOf(EnglishVerseRun(24, 2, cut, text.length)),
        ), { _, _ -> 350 }, { _, _ -> text })
        val windows = alignment.washSpans.associate { it.position to EnglishWashWindow(0, 25530) } +
            englishWashWindows(alignment.washSpans.map { it.position }, tail)
        val held = englishWashThrough(alignment, windows, 28490, 0)!!
        assertEquals(1, mushafLeafNumber(book, 24, 2, 350, held))
        val crossed = englishWashThrough(alignment, windows, 32000, 0)!!
        assertEquals(2, mushafLeafNumber(book, 24, 2, 350, crossed))
    }
}
