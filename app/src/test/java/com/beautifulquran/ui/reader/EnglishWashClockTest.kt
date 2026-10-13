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
    fun `opening silence does not consume the wash and final audio silence does not extend it`() {
        val windows = englishWashWindows(listOf(1, 2), listOf(Segment(1, 0, 4000), Segment(2, 4000, 6000)))
        assertEquals(0f, windows.getValue(1).progress(3000, voiceStartMs = 3000), 0f)
        assertEquals(0.5f, windows.getValue(1).progress(3500, voiceStartMs = 3000), 0f)
        assertEquals(1f, windows.getValue(2).progress(6000), 0f)
    }

    @Test
    fun `repeated words inside a portion extend its time without reversing the wash`() {
        val windows = englishWashWindows(listOf(1, 3), listOf(
            Segment(1, 0, 500), Segment(2, 500, 1000), Segment(1, 1000, 1500),
            Segment(2, 1500, 2000), Segment(3, 2000, 2500), Segment(2, 2500, 3000), Segment(3, 3000, 3500),
        ))
        assertEquals(EnglishWashWindow(0, 2000), windows.getValue(1))
        assertEquals(EnglishWashWindow(2000, 3500), windows.getValue(3))
        assertEquals(0.5f, windows.getValue(1).progress(1000), 0f)
        assertEquals(1f, windows.getValue(1).progress(2500), 0f)
        assertTrue(windows.getValue(3).progress(3000) < 1f)
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
        assertEquals(EnglishWashWindow(7290, 11060), windows.getValue(8))
        assertEquals(1f, windows.getValue(7).progress(7290), 0f)
        assertTrue(windows.getValue(8).progress(9150) < 1f)
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
