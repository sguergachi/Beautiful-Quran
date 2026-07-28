package com.beautifulquran.ui.reader

import com.beautifulquran.data.TimingScheme
import com.beautifulquran.data.model.SubwordKeyframe
import com.beautifulquran.ui.reader.InkEngine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        timingScheme: TimingScheme = TimingScheme.V1,
        keyframes: List<SubwordKeyframe> = emptyList(),
    ) = ActiveWord(
        ayah = 1,
        wordPosition = wordPosition,
        durationMs = durationMs,
        timingScheme = timingScheme,
        subwordKeyframes = keyframes,
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
        // Arm always masks — including re-Arm with the same activation keys.
        assertEquals(
            0f,
            displayedSweepProgress(
                entryAction = SweepEntryAction.Arm,
                applied = true,
                progress = 1f,
            ),
            0f,
        )
        // Keep before the reset effect applied still masks the idle 1f.
        assertEquals(
            0f,
            displayedSweepProgress(
                entryAction = SweepEntryAction.Keep,
                applied = false,
                progress = 1f,
            ),
            0f,
        )
        // Live wash after apply.
        assertEquals(
            0.4f,
            displayedSweepProgress(
                entryAction = SweepEntryAction.Keep,
                applied = true,
                progress = 0.4f,
            ),
            0f,
        )
        // Recited residual / clear never mask.
        assertEquals(
            1f,
            displayedSweepProgress(
                entryAction = SweepEntryAction.Clear,
                applied = true,
                progress = 1f,
            ),
            0f,
        )
        // Legacy bool overload.
        assertEquals(0f, displayedSweepProgress(entryPending = true, progress = 1f), 0f)
        assertEquals(0.4f, displayedSweepProgress(entryPending = false, progress = 0.4f), 0f)
    }

    @Test
    fun `re-arm after recited with same activation still masks full ink`() {
        // Same (active=true, activation=N) keys as the first pass — the old
        // remember() MutableState stayed false and flashed full → unread.
        val reentry = sweepEntryAction(
            wasActive = false,
            previousActivation = 1L,
            active = true,
            activation = 1L,
            hasSweep = true,
        )
        assertEquals(SweepEntryAction.Arm, reentry)
        assertEquals(
            0f,
            displayedSweepProgress(reentry, applied = true, progress = 1f),
            0f,
        )
    }

    @Test
    fun `residual only rewinds an idle full-ink animatable not a mid-wash`() {
        // Unapplied arm still sitting at the idle ceiling → start residual at 0.
        assertEquals(0f, residualSweepAnchor(applied = false, currentProgress = 1f), 0f)
        // Wash already advanced: never snap back to unread (prior-word flash).
        assertEquals(0.35f, residualSweepAnchor(applied = false, currentProgress = 0.35f), 0f)
        assertEquals(0.9f, residualSweepAnchor(applied = false, currentProgress = 0.9f), 0f)
        // Applied residual always continues from the live value.
        assertEquals(1f, residualSweepAnchor(applied = true, currentProgress = 1f), 0f)
        assertEquals(0.5f, residualSweepAnchor(applied = true, currentProgress = 0.5f), 0f)
    }

    @Test
    fun `reveal start stays latched through Active to Recited residual`() {
        val waslEdge = 0.22f
        assertEquals(
            waslEdge,
            effectiveRevealStart(
                active = true,
                finishResidual = false,
                revealStart = waslEdge,
                latchedRevealStart = 0f,
            ),
            0f,
        )
        // Handoff: caller passes 0 for non-active words; residual keeps the edge.
        assertEquals(
            waslEdge,
            effectiveRevealStart(
                active = false,
                finishResidual = true,
                revealStart = 0f,
                latchedRevealStart = waslEdge,
            ),
            0f,
        )
        // Seek / recess clears the edge.
        assertEquals(
            0f,
            effectiveRevealStart(
                active = false,
                finishResidual = false,
                revealStart = 0f,
                latchedRevealStart = waslEdge,
            ),
            0f,
        )
    }

    @Test
    fun `latched reveal start prevents wasl prefix unread flash on residual`() {
        val start = 0.2f
        // Mid residual raw progress after handoff — without the latch this
        // would display as 0.1 (rewound); with it the edge holds continuity.
        assertEquals(
            continuedSweepProgress(progress = 0.1f, start = start),
            continuedSweepProgress(
                progress = 0.1f,
                start = effectiveRevealStart(
                    active = false,
                    finishResidual = true,
                    revealStart = 0f,
                    latchedRevealStart = start,
                ),
            ),
            0f,
        )
        assertTrue(
            continuedSweepProgress(progress = 0.1f, start = start) >
                continuedSweepProgress(progress = 0.1f, start = 0f),
        )
    }

    @Test
    fun `wasl handoff continues from a supplied prefix edge`() {
        val prefix = 1f / 7f
        val mainFeather = 1.6f
        val start = waslContinuationStart(prefix, mainFeather)

        assertEquals(waslHeadTravel(prefix) / (1f + mainFeather), start, 1e-4f)
        assertEquals(start, continuedSweepProgress(progress = 0f, start = start), 0f)
        assertEquals(
            start + 0.5f * (1f - start),
            continuedSweepProgress(progress = 0.5f, start = start),
            0f,
        )
        assertEquals(1f, continuedSweepProgress(progress = 1f, start = start), 0f)
    }

    @Test
    fun `wasl wash maps the freed tail onto a short main-wash segment`() {
        val prefix = 1f / 7f
        val mainFeather = 1.6f
        val end = waslContinuationStart(prefix, mainFeather)
        // Window 0→1 only advances the main wash to the handoff edge — not a
        // full 0→1 wipe — so the soft edge has time to breathe.
        assertTrue(end < 0.35f)
        assertEquals(0f, waslWashProgress(windowProgress = 0f, endProgress = end), 0f)
        assertEquals(end * 0.5f, waslWashProgress(windowProgress = 0.5f, endProgress = end), 1e-4f)
        assertEquals(end, waslWashProgress(windowProgress = 1f, endProgress = end), 0f)
        // Head travel is one glyph plus a soft lead under main geometry.
        assertTrue(waslHeadTravel(prefix) > prefix)
        assertEquals(0.5f + 0.55f, waslHeadTravel(0.5f), 0f)
    }

    @Test
    fun `unfinished wasl bloom keeps the same edge across handoff`() {
        val fullEnd = waslContinuationStart(prefixFraction = 0.25f, mainFeather = 1.6f)
        val partialWindow = 0.35f
        val carried = waslWashProgress(partialWindow, fullEnd)

        assertTrue(carried in 0f..fullEnd)
        assertEquals(carried, continuedSweepProgress(progress = 0f, start = carried), 0f)
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
        val floor = InkEngine.minSweepFloorMs()
        assertEquals(
            floor,
            InkEngine.sweepMs(
                active(1, durationMs = floor.toLong()),
                playbackSpeed = 1f,
            ),
        )
        assertEquals(
            500,
            InkEngine.sweepMs(active(1, durationMs = 500), playbackSpeed = 1f),
        )
        assertEquals(
            InkEngine.tuning.maxSweepMs,
            InkEngine.sweepMs(active(1, durationMs = 60_000), playbackSpeed = 1f),
        )
    }

    @Test
    fun `short hold is scaled up to the min sweep floor`() {
        // Short holds (and first-word timing with almost no remaining Active
        // time) still get a visible wash. Renderers finish residual progress
        // after handoff instead of snapping to full ink. Floor includes the
        // highlight lead so early-started short words breathe longer.
        val floor = InkEngine.minSweepFloorMs()
        assertEquals(floor, InkEngine.sweepMs(active(1, durationMs = 80), playbackSpeed = 1f))
        assertEquals(floor, InkEngine.sweepMs(active(1, durationMs = 80), playbackSpeed = 2f))
        assertEquals(floor, InkEngine.sweepMs(active(1, durationMs = 10), playbackSpeed = 1f))
        assertEquals(floor, InkEngine.sweepMs(active(1, durationMs = 0), playbackSpeed = 1f))
    }

    @Test
    fun `highlight lead raises the short-hold sweep floor`() {
        val savedLead = InkEngine.highlightLeadMs
        try {
            InkEngine.highlightLeadMs = 0
            assertEquals(
                InkEngine.tuning.minSweepMs,
                InkEngine.minSweepFloorMs(),
            )
            InkEngine.highlightLeadMs = 114
            assertEquals(
                InkEngine.tuning.minSweepMs + 114,
                InkEngine.minSweepFloorMs(),
            )
            assertEquals(
                InkEngine.minSweepFloorMs(),
                InkEngine.sweepMs(active(1, durationMs = 80), playbackSpeed = 1f),
            )
        } finally {
            InkEngine.highlightLeadMs = savedLead
        }
    }

    @Test
    fun `no active word means no sweep`() {
        assertNull(InkEngine.sweepMs(activeWord = null, playbackSpeed = 1f))
    }

    @Test
    fun `V2 wash feed-forward chases without leading past the letter`() {
        // On target: no overshoot.
        assertEquals(
            0.5f,
            InkEngine.acousticWashStep(
                current = 0.5f,
                target = 0.5f,
                dtSec = 0.05f,
                targetVelocity = 0f,
            ),
            1e-3f,
        )

        // Large gap + reciter peel velocity: keep-up (feed-forward), not crawl.
        val after = InkEngine.acousticWashStep(
            current = 0.2f,
            target = 0.8f,
            dtSec = 0.05f,
            targetVelocity = 1.2f,
        )
        assertTrue("should keep up on peel (got $after)", after > 0.2f + 0.08f)
        assertTrue("must not lead past target (got $after)", after <= 0.8f + 1e-4f)

        // Bigger gap → faster catch-up than small gap (same v_target).
        val smallGap = InkEngine.acousticWashStep(
            current = 0.5f,
            target = 0.55f,
            dtSec = 0.05f,
            targetVelocity = 0.5f,
        )
        val bigGap = InkEngine.acousticWashStep(
            current = 0.5f,
            target = 0.75f,
            dtSec = 0.05f,
            targetVelocity = 0.5f,
        )
        assertTrue(
            "gap term should speed catch-up (${smallGap - 0.5f} vs ${bigGap - 0.5f})",
            (bigGap - 0.5f) > (smallGap - 0.5f),
        )

        // dt=0 never snaps.
        assertEquals(
            0.2f,
            InkEngine.acousticWashStep(
                current = 0.2f,
                target = 0.9f,
                dtSec = 0f,
                targetVelocity = 2f,
            ),
            1e-4f,
        )

        // Never rewinds.
        assertEquals(
            0.7f,
            InkEngine.acousticWashStep(
                current = 0.7f,
                target = 0.4f,
                dtSec = 0.05f,
                targetVelocity = 0f,
            ),
            1e-4f,
        )
    }

    @Test
    fun `letter feather scales with letter count for visible chase`() {
        assertTrue(InkEngine.letterFeather(2) in 0.35f..0.9f)
        assertTrue(InkEngine.letterFeather(5) < InkEngine.letterFeather(3))
        assertEquals(0.35f, InkEngine.letterFeather(20), 1e-3f) // floor
        // Front on letter mid-word maps inside mask progress.
        val f = InkEngine.letterFeather(5)
        val p = InkEngine.maskProgressForLetterFront(0.5f, f)
        assertTrue("mask p in (0,1) got $p", p in 0.2f..0.8f)
    }

    @Test
    fun `V2 acoustic pacing does not depend on the V1 Tajweed toggle`() {
        val saved = InkEngine.tuning
        try {
            InkEngine.tuning = saved.copy(tajweedPacing = false)
            val curve = InkEngine.pacing(
                arabic = "ٱلضَّآلِّينَ",
                activeWord = active(
                    1,
                    durationMs = 1_000,
                    timingScheme = TimingScheme.V2,
                    keyframes = listOf(
                        SubwordKeyframe(200, 0.4f),
                        SubwordKeyframe(600, 1f),
                    ),
                ),
                isAyahFinal = true,
            )
            assertNotNull(curve)
            assertEquals(0.4f, curve!!.at(0.2f), 1e-4f)
        } finally {
            InkEngine.tuning = saved
        }
    }

    @Test
    fun `V2 never borrows inferred Tajweed pacing when keyframes are absent`() {
        val saved = InkEngine.tuning
        try {
            InkEngine.tuning = saved.copy(tajweedPacing = true)
            val v1 = active(1, durationMs = 1_000, timingScheme = TimingScheme.V1)
            val v2 = active(1, durationMs = 1_000, timingScheme = TimingScheme.V2)

            assertNotNull(InkEngine.pacing("ٱلضَّآلِّينَ", v1, isAyahFinal = true))
            assertNull(InkEngine.pacing("ٱلضَّآلِّينَ", v2, isAyahFinal = true))
        } finally {
            InkEngine.tuning = saved
        }
    }

    @Test
    fun `V2 wasl blooms the next opening letter like V1 geometry`() {
        val saved = InkEngine.tuning
        try {
            InkEngine.tuning = saved.copy(tajweedPacing = true, holdConnect = true)
            assertNotNull(
                InkEngine.connection("مِن", "رَّبِّكُم", TimingScheme.V1),
            )
            // Missing measured budget must not kill wasl — ink still starts on
            // the next word's opening letter during the donor tail.
            assertNotNull(
                InkEngine.connection("مِن", "رَّبِّكُم", TimingScheme.V2, waslFromPrevMs = 0L),
            )
            assertNotNull(
                InkEngine.connection("مِن", "رَّبِّكُم", TimingScheme.V2, waslFromPrevMs = 200L),
            )
            // Measured budget only refines duration.
            assertEquals(200, InkEngine.waslPrefixTargetMs(200L))
            assertEquals(InkEngine.tuning.waslPrefixMs, InkEngine.waslPrefixTargetMs(0L))
            InkEngine.tuning = saved.copy(holdConnect = false)
            assertNull(
                InkEngine.connection("مِن", "رَّبِّكُم", TimingScheme.V2, waslFromPrevMs = 200L),
            )
        } finally {
            InkEngine.tuning = saved
        }
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
                wasActive = false,
                previousActivation = 4L,
                repeat = true,
                active = true,
                activation = 4L,
            ),
        )
    }

    @Test
    fun `repeat wash holds on chain handoff and reveals multi-loop re-say`() {
        // Active ends while still in chain — Hold so residual is not cancelled.
        assertEquals(
            RepeatWashAction.Hold,
            repeatWashAction(
                wasRepeat = true,
                wasActive = true,
                previousActivation = 4L,
                repeat = true,
                active = false,
                activation = 4L,
            ),
        )
        // Multi-loop re-say: still in open chain, becomes Active again → Reveal.
        assertEquals(
            RepeatWashAction.Reveal,
            repeatWashAction(
                wasRepeat = true,
                wasActive = false,
                previousActivation = 4L,
                repeat = true,
                active = true,
                activation = 4L,
            ),
        )
        // Seek / generation bump while in chain → Reveal.
        assertEquals(
            RepeatWashAction.Reveal,
            repeatWashAction(
                wasRepeat = true,
                wasActive = true,
                previousActivation = 4L,
                repeat = true,
                active = true,
                activation = 5L,
            ),
        )
        // Activation unchanged and still Active → Hold (no re-snap every frame).
        assertEquals(
            RepeatWashAction.Hold,
            repeatWashAction(
                wasRepeat = true,
                wasActive = true,
                previousActivation = 4L,
                repeat = true,
                active = true,
                activation = 4L,
            ),
        )
    }

    @Test
    fun `wash may hard-restart only when the overlay is invisible`() {
        // Product law: never rewind progress while still painted.
        assertFalse(washMayHardRestart(visibleAlpha = 1f))
        assertFalse(washMayHardRestart(visibleAlpha = 0.4f))
        assertFalse(washMayHardRestart(visibleAlpha = WASH_INVISIBLE_ALPHA))
        assertTrue(washMayHardRestart(visibleAlpha = 0f))
        assertTrue(washMayHardRestart(visibleAlpha = WASH_INVISIBLE_ALPHA - 0.001f))
    }

    @Test
    fun `drawn wash progress never rewinds while the overlay is visible`() {
        // Visible: peak holds even if raw clock snaps backward (flash source).
        val (mid, peakMid) = monotonicWashProgress(raw = 0.4f, visibleAlpha = 1f, peak = 0f)
        assertEquals(0.4f, mid, 1e-5f)
        val (held, peakHeld) = monotonicWashProgress(raw = 0f, visibleAlpha = 1f, peak = peakMid)
        assertEquals(0.4f, held, 1e-5f)
        assertEquals(0.4f, peakHeld, 1e-5f)
        val (advanced, peakAdv) = monotonicWashProgress(raw = 0.7f, visibleAlpha = 1f, peak = peakHeld)
        assertEquals(0.7f, advanced, 1e-5f)
        // Invisible: may return to 0 for a true cold start.
        val (cold, peakCold) = monotonicWashProgress(raw = 0f, visibleAlpha = 0f, peak = peakAdv)
        assertEquals(0f, cold, 1e-5f)
        assertEquals(0f, peakCold, 1e-5f)
    }

    @Test
    fun `repeat wash never hard-restarts a visible overlay — even on seek`() {
        // Mid-wash re-fire must not snap the edge back to 0.
        assertFalse(
            repeatWashShouldRestart(
                previousActivation = 0L,
                activation = 4L,
                clockProgress = 0.4f,
                alpha = 1f,
            ),
        )
        // Already-settled full orange must not restart (that flashed full→0).
        assertFalse(
            repeatWashShouldRestart(
                previousActivation = 0L,
                activation = 4L,
                clockProgress = 1f,
                alpha = 1f,
            ),
        )
        // Seek while still painted also cannot hard-restart; dissolve first.
        assertFalse(
            repeatWashShouldRestart(
                previousActivation = 4L,
                activation = 5L,
                clockProgress = 0.4f,
                alpha = 1f,
            ),
        )
        // Cold entry (overlay invisible) — only then may progress return to 0.
        assertTrue(
            repeatWashShouldRestart(
                previousActivation = 0L,
                activation = 4L,
                clockProgress = 1f,
                alpha = 0f,
            ),
        )
        assertTrue(
            repeatWashShouldRestart(
                previousActivation = 4L,
                activation = 5L,
                clockProgress = 0.4f,
                alpha = 0f,
            ),
        )
    }

    @Test
    fun `leaving the chain releases after a completed hold`() {
        assertEquals(
            RepeatWashAction.Release,
            repeatWashAction(
                wasRepeat = true,
                wasActive = false,
                previousActivation = 0L,
                repeat = false,
                active = false,
                activation = 0L,
            ),
        )
    }

    @Test
    fun `repeat wash duration helper floors short dwells`() {
        // Duration follows reciter dwell with repeatSweepMs as the soft floor.
        assertEquals(450, repeatWashDurationMs(activeSweepMs = null, minimumMs = 450))
        assertEquals(450, repeatWashDurationMs(activeSweepMs = 140, minimumMs = 450))
        assertEquals(1_800, repeatWashDurationMs(activeSweepMs = 1_800, minimumMs = 450))
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
