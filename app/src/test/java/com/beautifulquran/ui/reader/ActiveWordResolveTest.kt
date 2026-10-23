package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveWordResolveTest {

    @Test
    fun `slow buffering leaves a full confirmation grace after the stream becomes ready`() {
        val timeout = WordTapConfirmationTimeout(2_000)
        assertFalse(timeout.expired(buffering = false, nowMs = 0))
        assertFalse(timeout.expired(buffering = true, nowMs = 1_000))
        assertFalse(timeout.expired(buffering = true, nowMs = 10_000))
        assertFalse(timeout.expired(buffering = false, nowMs = 10_100))
        assertFalse(timeout.expired(buffering = false, nowMs = 12_099))
        assertTrue(timeout.expired(buffering = false, nowMs = 12_100))
    }

    @Test
    fun `a new buffering burst restarts the confirmation grace`() {
        val timeout = WordTapConfirmationTimeout(2_000)
        assertFalse(timeout.expired(buffering = false, nowMs = 100))
        assertFalse(timeout.expired(buffering = true, nowMs = 2_000))
        assertFalse(timeout.expired(buffering = false, nowMs = 3_000))
        assertFalse(timeout.expired(buffering = false, nowMs = 4_999))
        assertTrue(timeout.expired(buffering = false, nowMs = 5_000))
    }

    private fun word(
        ayah: Int = 2,
        position: Int = 5,
        activation: Long = 6,
        fromTap: Boolean = false,
        durationMs: Long = 400,
    ) = ActiveWord(
        ayah = ayah,
        wordPosition = position,
        durationMs = durationMs,
        activation = activation,
        fromTap = fromTap,
        tapStartPosition = position.takeIf { fromTap },
    )

    @Test
    fun `a tap seed wins over a stale poll`() {
        val seed = word(fromTap = true)
        val stale = word(ayah = 1, position = 3, activation = 5)
        assertSame(seed, WordTapInk(seed).resolve(stale))
    }

    @Test
    fun `poll replacing a tap seed keeps the seed activation`() {
        val seed = word(activation = 6, fromTap = true)
        val polled = word(activation = 7, durationMs = 420)
        val shown = WordTapInk(seed).onPoll(polled).resolve(polled)!!
        assertEquals(6L, shown.activation)
        assertFalse(shown.fromTap)
        assertEquals(420L, shown.durationMs)
    }

    @Test
    fun `matching poll while the seed is held still pins activation`() {
        val seed = word(activation = 6, fromTap = true)
        val polled = word(activation = 8)
        val shown = WordTapInk(seed).resolve(polled)!!
        assertEquals(6L, shown.activation)
        assertFalse(shown.fromTap)
    }

    @Test
    fun `the next word uses the poll activation`() {
        val seed = word(position = 5, activation = 6, fromTap = true)
        val next = word(position = 6, activation = 7)
        val tap = WordTapInk(seed).onPoll(word(activation = 7))
        val shown = tap.resolve(next)!!
        assertEquals(next.activation, shown.activation)
        assertEquals(5, shown.tapStartPosition)
    }
    @Test
    fun `old poll for the same word cannot acknowledge a new tap`() {
        val seed = word(activation = 8, fromTap = true)
        val stale = word(activation = 7)
        assertSame(seed, WordTapInk(seed).resolve(stale))
        assertFalse(WordTapInk(seed).resolve(word(activation = 8))!!.fromTap)
    }

    @Test
    fun `buffering preserves the tapped prefix before and after acknowledgement`() {
        val seed = word(position = 5, fromTap = true)
        var tap = WordTapInk(seed)
        val polls = listOf(null, word(ayah = 1, activation = 5), word(activation = 7),
            null, word(position = 6, activation = 7))
        for (polled in polls) {
            tap = tap.onPoll(polled)
            val shown = streamWaitActiveWord(tap.resolve(polled), streamWait = true)!!
            for (position in 1 until shown.wordPosition) {
                assertEquals(InkEngine.State.Recited,
                    InkEngine.wordState(position, shown, isActiveAyah = true, dimmed = false))
            }
            assertEquals(InkEngine.State.Upcoming,
                InkEngine.wordState(shown.wordPosition + 1, shown, isActiveAyah = true, dimmed = false))
        }
    }

    @Test
    fun `a pause and resume within the tapped generation keep its origin`() {
        val seed = word(fromTap = true)
        val heard = word(activation = 8)
        val tap = WordTapInk(seed).onPoll(heard)
        assertEquals(5, streamWaitActiveWord(tap.resolve(heard), true)?.tapStartPosition)
        assertEquals(5, streamWaitActiveWord(tap.onPoll(null).resolve(heard), true)?.tapStartPosition)
    }

    @Test
    fun `new performance verse or reciter cannot inherit a tap prefix`() {
        val seed = word(fromTap = true)
        val tap = WordTapInk(seed).onPoll(word(activation = 7))
        for (other in listOf(word(activation = 8), word(ayah = 3, activation = 7),
            word(activation = 7).copy(reciterId = 2))) {
            assertSame(other, tap.resolve(other))
            assertNull(tap.onPoll(other).seed)
            assertNull(streamWaitActiveWord(tap.resolve(other), true))
        }
    }

    @Test
    fun `ordinary buffering still covers the recited tail`() {
        val heard = word()
        assertNull(streamWaitActiveWord(heard, true))
        assertSame(heard, streamWaitActiveWord(heard, false))
    }

    @Test
    fun `rapid later tap beats an earlier acknowledgement without leaving a prefix gap`() {
        val first = word(position = 3, activation = 6, fromTap = true)
        val second = word(position = 7, activation = 7, fromTap = true)
        val tap = WordTapInk(second).onPoll(first.copy(fromTap = false))
        val shown = streamWaitActiveWord(tap.resolve(first), true)!!
        assertSame(second, shown)
        for (position in 1..6) {
            assertEquals(InkEngine.State.Recited,
                InkEngine.wordState(position, shown, isActiveAyah = true, dimmed = false))
        }
    }

    @Test fun aWordTapsOwnLoadingNeverPutsThePageUnderTheStreamWait() {
        // Press play on an uncached item: the whole page waits under paper.
        assertTrue(streamWaitEngages(isBuffering = true, wordTapAwaitingVoice = false))
        // Tap a word whose audio is still downloading: the page keeps the
        // ordinary recitation look instead.
        assertFalse(streamWaitEngages(isBuffering = true, wordTapAwaitingVoice = true))
        assertFalse(streamWaitEngages(isBuffering = false, wordTapAwaitingVoice = false))
    }

    @Test fun aTapsWaitEndsOnlyWhenItsOwnSeekIsHeard() {
        val wait = WordTapVoiceWait(activation = 8)
        // The old performance still playing at the moment of the tap is not it.
        assertFalse(wait.heard(inkId = 7, isPlaying = true, isBuffering = false))
        assertFalse(wait.heard(inkId = 8, isPlaying = false, isBuffering = true))
        assertTrue(wait.heard(inkId = 8, isPlaying = true, isBuffering = false))
    }

    @Test fun aColdChapterStartLandsWhenItsDownloadHasBeenSeen() {
        // A tap into a chapter that is not loaded yet starts a new item, with
        // no seek generation to wait for: its buffering spell marks the landing.
        val wait = WordTapVoiceWait(activation = 3)
        assertFalse(wait.heard(inkId = 0, isPlaying = false, isBuffering = true))
        assertTrue(wait.heard(inkId = 0, isPlaying = true, isBuffering = false))
    }
}
