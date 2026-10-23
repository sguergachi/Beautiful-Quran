package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Ayah
import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.HighlightEngine
import com.beautifulquran.domain.HighlightFrame
import com.beautifulquran.domain.HighlightItem
import com.beautifulquran.domain.HighlightPhase
import com.beautifulquran.domain.HighlightPlaybackSample
import com.beautifulquran.domain.HighlightSync
import com.beautifulquran.domain.HighlightTimingContext
import com.beautifulquran.domain.HighlightWash
import com.beautifulquran.domain.HighlightWashState
import org.junit.Assert.*
import org.junit.Test

class EnglishScrollTimingTest {
    private val verse = Ayah(64, 8, "", "", words = emptyList())
    private val item = HighlightItem(64, 8, 7)
    private fun word(spoken: Long = 800, hold: Long = 2400) = ActiveWord(
        ayah = 8, wordPosition = 2, startMs = 1000, durationMs = hold,
        spokenMs = spoken, reciterId = 7,
    )
    private fun window(word: ActiveWord, speed: Float = 1f) =
        mediaWashWindow(verse, word, InkEngine.sweepMs(word, speed), speed, spokenOnly = true)!!
    private fun shown(linear: Float) = mediaSweepProgress(linear, paced = false, linearTime = true)
    private fun frame(at: Long) = HighlightFrame(item, 0, 1, at, at, at,
        HighlightPhase.Speaking, 0, null, 0f)

    @Test fun `English finishes the current word at its spoken end and rests through the gap`() {
        val wash = HighlightWash.transition(HighlightWashState(), true, false, 0, window(word()))
        assertEquals(.5f, shown(HighlightWash.progress(wash, frame(1400))), .000001f)
        for (at in listOf(1800L, 2000L, 3000L, 3399L)) {
            assertEquals(1f, shown(HighlightWash.progress(wash, frame(at))), 0f)
        }
    }

    @Test fun `English progress neither accelerates nor slows relative to elapsed speech`() {
        for (step in 0..100) assertEquals(step / 100f, shown(step / 100f), .000001f)
    }

    @Test fun `short and long voiced spans keep their media duration at every speed`() {
        for (spoken in listOf(40L, 800L, 12000L)) for (speed in listOf(.5f, 1f, 2f)) {
            val current = word(spoken, spoken + 2000)
            assertEquals(spoken, window(current, speed).durationMs)
        }
    }

    @Test fun `a later owner cannot open before the preceding spoken window closes`() {
        val first = HighlightWash.transition(HighlightWashState(), true, false, 0, window(word()))
        val next = word(600, 600).copy(wordPosition = 3, startMs = 3400)
        val second = HighlightWash.transition(HighlightWashState(), true, false, 0, window(next))
        for (at in 1000L..4000L step 25) {
            val prior = shown(HighlightWash.progress(first, frame(at)))
            val current = shown(HighlightWash.progress(second, frame(at)))
            if (current > 0f) assertEquals(1f, prior, 0f)
            assertEquals(at >= 1800, canStartSequentialSweep(prior))
        }
    }

    @Test fun `a repeat uses its own voiced occurrence without inheriting a long gap`() {
        val first = HighlightWash.transition(HighlightWashState(), true, false, 0, window(word()))
        val occurrence = word(100, 900).copy(startMs = 3500, isRepeat = true)
        val repeated = HighlightWash.transition(first, true, true, 0, window(occurrence), repeatOccurrence = true)
        assertEquals(.5f, shown(HighlightWash.progress(repeated, frame(3550))), .000001f)
        assertEquals(1f, shown(HighlightWash.progress(repeated, frame(3600))), 0f)
    }

    @Test fun `a seek into an already ended spoken span cannot animate through its silence`() {
        assertEquals(1L, window(word(0, 900)).durationMs)
        val wash = HighlightWash.transition(HighlightWashState(), true, false, 0, window(word(0, 900)))
        assertEquals(1f, HighlightWash.progress(wash, frame(1001)), 0f)
    }

    @Test fun `Arabic keeps its existing hold duration and easing`() {
        val current = word()
        assertEquals(2400L, mediaWashWindow(verse, current, 1200, 2f)!!.durationMs)
        for (step in 0..100) {
            val value = step / 100f
            assertEquals(InkEngine.sweepEasing.transform(value),
                mediaSweepProgress(value, paced = false, linearTime = false), 0f)
            assertEquals(value, mediaSweepProgress(value, paced = true, linearTime = false), 0f)
        }
    }

    @Test fun `the real gap hold keeps its owner without continuing the English fill`() {
        val timing = HighlightTimingContext(1, HighlightEngine.PreparedTimings.prepare(listOf(
            Segment(2, 1000, 1800), Segment(3, 3400, 4000),
        )), 0)
        val first = HighlightWash.transition(HighlightWashState(), true, false, 0, window(word()))
        val next = HighlightWash.transition(HighlightWashState(), true, false, 0,
            window(word(600, 600).copy(wordPosition = 3, startMs = 3400)))
        for (position in listOf(1800L, 2200L, 3399L)) {
            val sync = HighlightSync.sample(null, HighlightPlaybackSample(item, position, 0, true), timing)
            val frame = HighlightSync.frame(sync, 0)
            assertEquals(HighlightPhase.GapHold, frame.phase)
            assertEquals(2, frame.active!!.position)
            assertEquals(1f, shown(HighlightWash.progress(first, frame)), 0f)
            assertEquals(0f, shown(HighlightWash.progress(next, frame)), 0f)
        }
    }
}
