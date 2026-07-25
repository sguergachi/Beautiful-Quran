package com.beautifulquran.ui.reader

import com.beautifulquran.ui.reader.InkEngine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkEngineTest {

    private fun active(
        wordPosition: Int,
        durationMs: Long = 600,
        isRepeat: Boolean = false,
        highWater: Int = wordPosition,
        repeatStart: Int = wordPosition,
    ) = ActiveWord(
        ayah = 1,
        wordPosition = wordPosition,
        durationMs = durationMs,
        isRepeat = isRepeat,
        highWater = highWater,
        repeatStart = repeatStart,
    )

    private fun states(count: Int, activeWord: ActiveWord?): List<State> =
        (1..count).map {
            InkEngine.wordState(it, activeWord, isActiveAyah = true, dimmed = false)
        }

    @Test
    fun `sweep entry lifecycle arms only for a new active generation`() {
        assertEquals(
            SweepEntryAction.Arm,
            sweepEntryAction(
                wasActive = false,
                previousActivation = 0L,
                active = true,
                activation = 0L,
                hasSweep = true,
            ),
        )
        assertEquals(
            SweepEntryAction.Keep,
            sweepEntryAction(
                wasActive = true,
                previousActivation = 4L,
                active = true,
                activation = 4L,
                hasSweep = true,
            ),
        )
        assertEquals(
            SweepEntryAction.Arm,
            sweepEntryAction(
                wasActive = true,
                previousActivation = 4L,
                active = true,
                activation = 5L,
                hasSweep = true,
            ),
        )
    }

    @Test
    fun `sweep entry lifecycle clears outside a runnable active word`() {
        assertEquals(
            SweepEntryAction.Clear,
            sweepEntryAction(
                wasActive = true,
                previousActivation = 4L,
                active = false,
                activation = 4L,
                hasSweep = true,
            ),
        )
        assertEquals(
            SweepEntryAction.Clear,
            sweepEntryAction(
                wasActive = false,
                previousActivation = 0L,
                active = true,
                activation = 0L,
                hasSweep = false,
            ),
        )
    }

    @Test
    fun `pending sweep entry masks the completed animatable before reset`() {
        assertEquals(0f, displayedSweepProgress(entryPending = true, progress = 1f), 0f)
        assertEquals(0.4f, displayedSweepProgress(entryPending = false, progress = 0.4f), 0f)
    }

    @Test
    fun `wasl handoff continues from the completed prefix edge`() {
        val prefix = 1f / 7f
        val feather = 1.6f
        val start = waslContinuationStart(prefix, feather)

        assertEquals(2f * prefix / (1f + feather), start, 1e-4f)
        assertEquals(start, continuedSweepProgress(progress = 0f, start = start), 0f)
        assertEquals(
            start + 0.5f * (1f - start),
            continuedSweepProgress(progress = 0.5f, start = start),
            0f,
        )
        assertEquals(1f, continuedSweepProgress(progress = 1f, start = start), 0f)
    }

    @Test
    fun `ordinary and sought words start their sweep at zero`() {
        assertEquals(0.4f, continuedSweepProgress(progress = 0.4f, start = 0f), 0f)
    }

    // --- wordState ---

    @Test
    fun `idle ayah words are plain, recessed ayah words are upcoming`() {
        assertEquals(
            State.Plain,
            InkEngine.wordState(1, activeWord = null, isActiveAyah = false, dimmed = false),
        )
        assertEquals(
            State.Upcoming,
            InkEngine.wordState(1, activeWord = null, isActiveAyah = false, dimmed = true),
        )
    }

    @Test
    fun `basmalah preface ink follows active and recess`() {
        assertEquals(State.Plain, InkEngine.prefaceState(isActive = false, dimmed = false))
        assertEquals(State.Active, InkEngine.prefaceState(isActive = true, dimmed = false))
        assertEquals(State.Active, InkEngine.prefaceState(isActive = true, dimmed = true))
        assertEquals(State.Upcoming, InkEngine.prefaceState(isActive = false, dimmed = true))
    }

    @Test
    fun `calligraphy wash follows the lead-in playback clock`() {
        assertEquals(0f, InkEngine.prefaceWashProgress(positionMs = 0, durationMs = 5000), 1e-4f)
        assertEquals(0f, InkEngine.prefaceWashProgress(positionMs = 100, durationMs = 0), 1e-4f)
        val settleAt = (5000 * InkEngine.PREFACE_WASH_SETTLE_FRACTION).toLong()
        assertEquals(
            0.5f,
            InkEngine.prefaceWashProgress(positionMs = settleAt / 2, durationMs = 5000),
            1e-3f,
        )
        assertEquals(1f, InkEngine.prefaceWashProgress(positionMs = settleAt, durationMs = 5000), 1e-4f)
        assertEquals(1f, InkEngine.prefaceWashProgress(positionMs = 5000, durationMs = 5000), 1e-4f)
        // Settles before the clip ends so the feathered edge can finish.
        assertEquals(1f, InkEngine.prefaceWashProgress(positionMs = settleAt + 1, durationMs = 5000), 1e-4f)
        assertTrue(settleAt < 5000)
    }

    @Test
    fun `active ayah with no lit word rests every word at upcoming`() {
        // E.g. during the basmalah lead before the first word's segment.
        assertEquals(
            List(4) { State.Upcoming },
            states(4, activeWord = null),
        )
    }

    @Test
    fun `words split around the active word`() {
        assertEquals(
            listOf(State.Recited, State.Recited, State.Active, State.Upcoming),
            states(4, active(wordPosition = 3)),
        )
    }

    @Test
    fun `high-water keeps already-recited words lit during a repeat`() {
        // Reciter reached word 4, then jumped back to word 2: words 3 and 4
        // were already recited this pass, so they hold full ink.
        assertEquals(
            listOf(State.Recited, State.Active, State.Recited, State.Recited, State.Upcoming),
            states(5, active(wordPosition = 2, isRepeat = true, highWater = 4, repeatStart = 2)),
        )
    }

    // --- inRepeatChain ---

    @Test
    fun `no chain while not repeating`() {
        assertFalse(InkEngine.inRepeatChain(2, active(wordPosition = 3)))
        assertFalse(InkEngine.inRepeatChain(2, activeWord = null))
    }

    @Test
    fun `chain spans repeat start through the re-recited word`() {
        val repeating = active(wordPosition = 3, isRepeat = true, highWater = 4, repeatStart = 2)
        assertFalse(InkEngine.inRepeatChain(1, repeating))
        assertTrue(InkEngine.inRepeatChain(2, repeating))
        assertTrue(InkEngine.inRepeatChain(3, repeating))
        assertFalse(InkEngine.inRepeatChain(4, repeating))
    }

    @Test
    fun `chain releases once playback advances past the high water`() {
        // Chain complete: the reciter moved on to new words, isRepeat is false.
        val moved = active(wordPosition = 5, highWater = 5)
        for (position in 1..5) {
            assertFalse(InkEngine.inRepeatChain(position, moved))
        }
    }

    // --- word ---

    @Test
    fun `word bundles state and repeat membership`() {
        val repeating = active(wordPosition = 2, isRepeat = true, highWater = 4, repeatStart = 2)
        val word = InkEngine.word(2, repeating, isActiveAyah = true, dimmed = false)
        assertEquals(State.Active, word.state)
        assertTrue(word.repeat)
    }

    @Test
    fun `inactive ayah words never wear the repeat wash`() {
        // An ayah is inactive when it does not own the reciting word — the
        // caller passes activeWord = null for it.
        val word = InkEngine.word(2, activeWord = null, isActiveAyah = false, dimmed = true)
        assertEquals(State.Upcoming, word.state)
        assertFalse(word.repeat)
    }

    @Test
    fun `the ayah that owns the active word keeps it lit through the fade lead`() {
        // The fade-led focus bit moves to the next ayah FADE_LEAD_MS before the
        // audio boundary — during a waqf, while the closing word is still held.
        // The owning ayah's words must follow the audio (activeWord), not the
        // focus bit, or the sustained letter drops out of its paced hold early.
        val holding = active(wordPosition = 3, highWater = 3)
        assertEquals(
            listOf(State.Recited, State.Recited, State.Active),
            (1..3).map { InkEngine.wordState(it, holding, isActiveAyah = false, dimmed = true) },
        )
        // And a repeat chain keeps its orange through the same lead.
        val repeating = active(wordPosition = 2, isRepeat = true, highWater = 4, repeatStart = 2)
        assertTrue(InkEngine.word(2, repeating, isActiveAyah = false, dimmed = true).repeat)
    }

    // --- sweepMs ---

    @Test
    fun `sweep follows the word duration corrected for speed`() {
        assertEquals(600, InkEngine.sweepMs(active(1, durationMs = 600), playbackSpeed = 1f))
        assertEquals(300, InkEngine.sweepMs(active(1, durationMs = 600), playbackSpeed = 2f))
        assertEquals(1200, InkEngine.sweepMs(active(1, durationMs = 600), playbackSpeed = 0.5f))
    }

    @Test
    fun `sweep clamps to the tuned floor and ceiling`() {
        val tuning = InkEngine.tuning
        assertEquals(
            tuning.minSweepMs,
            InkEngine.sweepMs(
                active(1, durationMs = tuning.minSweepMs.toLong()),
                playbackSpeed = 1f,
            ),
        )
        assertEquals(
            500,
            InkEngine.sweepMs(active(1, durationMs = 500), playbackSpeed = 1f),
        )
        assertEquals(
            tuning.maxSweepMs,
            InkEngine.sweepMs(active(1, durationMs = 60_000), playbackSpeed = 1f),
        )
    }

    @Test
    fun `short hold is scaled up to the min sweep floor`() {
        // Short holds (and first-word timing with almost no remaining Active
        // time) still get a visible wash. Renderers finish residual progress
        // after handoff instead of snapping to full ink.
        val floor = InkEngine.tuning.minSweepMs
        assertEquals(floor, InkEngine.sweepMs(active(1, durationMs = 80), playbackSpeed = 1f))
        assertEquals(floor, InkEngine.sweepMs(active(1, durationMs = 80), playbackSpeed = 2f))
        assertEquals(floor, InkEngine.sweepMs(active(1, durationMs = 10), playbackSpeed = 1f))
        assertEquals(floor, InkEngine.sweepMs(active(1, durationMs = 0), playbackSpeed = 1f))
    }

    @Test
    fun `no active word means no sweep`() {
        assertNull(InkEngine.sweepMs(activeWord = null, playbackSpeed = 1f))
    }

    // --- glinting ---

    @Test
    fun `every active word wears the fresh-ink glint, replays included`() {
        // Tap-to-play / seek / loop restart must never skip the reveal — full
        // ink already on the page is not a substitute for the wash motion — so
        // being Active is the whole gate. A repeat glints over its orange wash
        // on the same terms.
        assertTrue(InkEngine.glinting(State.Active))
        // Resting states never glint.
        assertFalse(InkEngine.glinting(State.Plain))
        assertFalse(InkEngine.glinting(State.Upcoming))
        assertFalse(InkEngine.glinting(State.Recited))
    }

    @Test
    fun `same-word repeat reveals with an unchanged activation generation`() {
        assertEquals(
            RepeatWashAction.Reveal,
            repeatWashAction(
                wasRepeat = false,
                previousActivation = 4L,
                repeat = true,
                activation = 4L,
            ),
        )
    }

    @Test
    fun `repeat wash holds on chain advance and restarts on seek`() {
        assertEquals(
            RepeatWashAction.Hold,
            repeatWashAction(
                wasRepeat = true,
                previousActivation = 4L,
                repeat = true,
                activation = 0L,
            ),
        )
        assertEquals(
            RepeatWashAction.Reveal,
            repeatWashAction(
                wasRepeat = true,
                previousActivation = 4L,
                repeat = true,
                activation = 5L,
            ),
        )
    }

    @Test
    fun `initial glint keeps its identity and recedes behind a same-word repeat`() {
        val identity = GlintIdentity(repeat = false)
        assertFalse(identity.update(glinting = true, repeat = false))
        assertFalse(identity.update(glinting = true, repeat = true))
        assertTrue(identity.replacedByRepeat)
        assertEquals(1f, glintCarryAlpha(identity.replacedByRepeat, 0f), 0f)
        assertEquals(0.5f, glintCarryAlpha(identity.replacedByRepeat, 0.5f), 1e-4f)
        assertEquals(0f, glintCarryAlpha(identity.replacedByRepeat, 1f), 0f)

        identity.update(glinting = false, repeat = false)
        assertTrue(identity.replacedByRepeat)
        assertEquals(0f, glintCarryAlpha(identity.replacedByRepeat, 1f), 0f)
        assertTrue(identity.update(glinting = true, repeat = true))
        assertFalse(identity.replacedByRepeat)
        assertEquals(1f, glintCarryAlpha(identity.replacedByRepeat, 1f), 0f)
    }

    // --- inkAlpha ---

    @Test
    fun `only upcoming ink is faint`() {
        assertEquals(InkEngine.tuning.upcomingAlpha, State.Upcoming.inkAlpha(), 0f)
        assertEquals(1f, State.Plain.inkAlpha(), 0f)
        assertEquals(1f, State.Active.inkAlpha(), 0f)
        assertEquals(1f, State.Recited.inkAlpha(), 0f)
    }
}
