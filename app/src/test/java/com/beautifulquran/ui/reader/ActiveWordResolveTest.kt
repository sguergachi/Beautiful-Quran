package com.beautifulquran.ui.reader

import com.beautifulquran.domain.HighlightWash
import com.beautifulquran.domain.HighlightWashState
import com.beautifulquran.domain.HighlightWashWindow
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
    fun `an acknowledged seek cannot fade its prefix when the display clock is earlier`() {
        val seed = word(position = 8, fromTap = true)
        val heard = word(position = 8, activation = 7)
        val tap = WordTapInk(seed).onPoll(heard)
        for (cursor in listOf(1, 4, 7, 8, 9)) {
            val shown = tap.resolve(word(position = cursor, activation = 7))!!
            for (position in 1..7) {
                assertEquals("cursor=$cursor prefix=$position", InkEngine.State.Recited,
                    InkEngine.wordState(position, shown, isActiveAyah = true, dimmed = false))
            }
            assertEquals(InkEngine.State.Upcoming,
                InkEngine.wordState(10, shown, isActiveAyah = true, dimmed = false))
        }
    }

    @Test
    fun `a tap publishes its full prefix before any player or coroutine update`() {
        val publication = WordInkPublication()
        publication.onPoll(word(position = 1, activation = 5))
        val seed = word(position = 8, fromTap = true)
        publication.seed(seed)
        // No collector or dispatcher turn is needed before playback can start.
        assertSame(seed, publication.activeWord.value)
        for (position in 1..7) {
            assertEquals(InkEngine.State.Recited,
                InkEngine.wordState(position, publication.activeWord.value, true, false))
        }
        publication.onPoll(null)
        assertSame(seed, publication.activeWord.value)
        publication.onPoll(word(position = 8, activation = 7))
        publication.onPoll(word(position = 1, activation = 7))
        assertEquals(8, publication.activeWord.value?.tapStartPosition)
    }

    @Test
    fun `an old confirmation timer cannot clear a newer tap publication`() {
        val publication = WordInkPublication()
        val first = word(position = 3, fromTap = true)
        val second = word(position = 8, activation = 7, fromTap = true)
        publication.seed(first)
        publication.seed(second)
        publication.expire(first)
        assertSame(second, publication.activeWord.value)
        assertFalse(publication.awaiting(first))
        assertTrue(publication.awaiting(second))
        publication.clear()
        assertNull(publication.activeWord.value)
    }

    @Test
    fun `session acknowledgement of the same word seek cannot hand its prefix to stream wait`() {
        val publication = WordInkPublication()
        val seed = word(position = 11, activation = 4, fromTap = true).copy(startMs = 11_900)
        publication.seed(seed, positionIntentId = 4)
        publication.onPositionIntent(4)
        publication.onPoll(seed.copy(fromTap = false, tapStartPosition = null))
        // Recorded 2:63: an immediate controller seek, a buffering callback,
        // then the session reports the same landing with another ink generation.
        publication.onPoll(seed.copy(activation = 5, playbackActivation = 5,
            fromTap = false, tapStartPosition = null))
        val shown = streamWaitActiveWord(publication.activeWord.value, streamWait = true)!!
        assertEquals(5L, shown.playbackActivation)
        assertEquals(11, shown.tapStartPosition)
        for (position in 1..10) {
            assertEquals(InkEngine.State.Recited, InkEngine.wordState(position, shown, true, false))
        }
        publication.onPoll(word(position = 12, activation = 5))
        assertEquals(11, publication.activeWord.value?.tapStartPosition)
    }

    @Test
    fun `a new position intent clears the exception even when it seeks to the same word`() {
        val publication = WordInkPublication()
        val seed = word(fromTap = true)
        publication.seed(seed, positionIntentId = 4)
        publication.onPoll(word())
        publication.onPositionIntent(5)
        assertNull(publication.activeWord.value?.tapStartPosition)
        publication.onPoll(word(activation = 7))
        assertNull(streamWaitActiveWord(publication.activeWord.value, streamWait = true))
    }

    @Test
    fun `an unrelated seek cannot reuse a word taps intent merely by staying in the same verse`() {
        val publication = WordInkPublication()
        publication.seed(word(fromTap = true), positionIntentId = 4)
        publication.onPoll(word())
        publication.onPoll(word(position = 2, activation = 7))
        assertNull(publication.activeWord.value?.tapStartPosition)
    }

    @Test
    fun `a tapped prefix completes old residuals while natural handoffs keep their soft edge`() {
        val window = HighlightWashWindow(2, 2, 7, 1_000, 2_000)
        val old = HighlightWash.transition(HighlightWashState(), true, false, 7, window)
        val tapped = word(position = 8, activation = 7).copy(tapStartPosition = 8)
        val state = InkEngine.wordState(4, tapped, true, false)
        val skipped = HighlightWash.transition(old, false,
            InkEngine.retainWordSweep(4, state, tapped), 0, null)
        assertNull(skipped.window)
        assertEquals(1f, HighlightWash.progress(skipped, null), 0f)

        val natural = tapped.copy(tapStartPosition = null)
        val residual = HighlightWash.transition(old, false,
            InkEngine.retainWordSweep(4, state, natural), 0, null)
        assertEquals(window, residual.window)
        assertFalse(InkEngine.inTappedPrefix(8, tapped))
    }

    @Test
    fun `a genuine repeat before the tap keeps its orange occurrence over full base ink`() {
        val repeating = word(position = 2, activation = 7).copy(
            tapStartPosition = 8, isRepeat = true, highWater = 9, repeatStart = 2)
        assertEquals(InkEngine.State.Active, InkEngine.wordState(2, repeating, true, false))
        assertTrue(InkEngine.inRepeatChain(2, repeating))
        assertFalse(InkEngine.retainWordSweep(2, InkEngine.State.Active, repeating))
        assertEquals(InkEngine.State.Recited, InkEngine.wordState(7, repeating, true, false))
        assertEquals(InkEngine.State.Upcoming, InkEngine.wordState(10, repeating, true, false))
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
