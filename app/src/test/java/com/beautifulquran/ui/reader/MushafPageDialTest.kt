package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page dial's laws, away from Compose. What a dp of finger buys in each of
 * the two tiers, what opens and shuts the trough, and what the comb is
 * therefore allowed to show.
 */
class MushafPageDialTest {

    /** A phone's width less the rule's two 14dp insets. */
    private val trackDp = 360f - 28f
    private val bookGain = mushafDialBookGain(604, trackDp)

    @Test
    fun `one stroke of the measure is the whole book`() {
        // The chapter tier's only promise: drag from one end of the rule to
        // the other and you have crossed all 604. Not "most of them" with a
        // fudge factor — the last leaf has to be reachable in one stroke or
        // "all the way left is the last chapter" is not true.
        assertEquals(603f, bookGain * trackDp, 0.5f)
    }

    @Test
    fun `the book's scale is a relation to the screen, not a number`() {
        // A tablet's wider rule spends more screen on the same book, so a dp
        // buys fewer leaves. Hard-coding leaves-per-dp would make the sweep
        // overshoot on a small phone and fall short on a large one.
        val narrow = mushafDialBookGain(604, 300f)
        val wide = mushafDialBookGain(604, 900f)
        assertTrue("wide $wide must be gentler than narrow $narrow", wide < narrow)
        assertEquals(603f, narrow * 300f, 0.5f)
        assertEquals(603f, wide * 900f, 0.5f)
    }

    @Test
    fun `a book with no length still has a usable scale`() {
        assertTrue(mushafDialBookGain(1, trackDp) > 0f)
        assertEquals(1f, mushafDialBookGain(604, 0f), 1e-6f)
    }

    @Test
    fun `the trough runs right to left inside its chapter`() {
        // What the reader asked for in as many words: the far right end is
        // the chapter's first leaf, the far left its last.
        val run = 50..76
        assertEquals(50f, mushafDialTroughPage(1f, run), 1e-4f)
        assertEquals(76f, mushafDialTroughPage(0f, run), 1e-4f)
        assertEquals(63f, mushafDialTroughPage(0.5f, run), 1e-4f)
    }

    @Test
    fun `the trough is absolute, so the same place is the same leaf`() {
        // Unlike the chapter tier, which accumulates: a finger returning to
        // the same x inside the trough must name the same leaf it named
        // before, or aiming at a page is guesswork.
        val run = 100..130
        val there = mushafDialTroughPage(0.37f, run)
        assertEquals(there, mushafDialTroughPage(0.37f, run), 0f)
        // And it never leaves the chapter it is holding.
        assertTrue(mushafDialTroughPage(-1f, run) <= run.last)
        assertTrue(mushafDialTroughPage(2f, run) >= run.first)
    }

    @Test
    fun `a one-leaf chapter still fills its trough`() {
        // The short chapters at the back share a leaf. Their trough has
        // nowhere to travel, and must not divide by that nothing.
        val run = 604..604
        assertEquals(604f, mushafDialTroughPage(0f, run), 1e-4f)
        assertEquals(604f, mushafDialTroughPage(1f, run), 1e-4f)
    }

    @Test
    fun `the trough opens on a held hand, not on a slow one`() {
        // Both halves are load-bearing. A hand that is merely slow is still
        // steering, and the top of every stroke is an instant of stillness —
        // opening on either alone would take the ground away mid-gesture.
        assertTrue(mushafDialShouldOpen(0f, MUSHAF_DIAL_HOLD_S))
        assertFalse(mushafDialShouldOpen(0f, MUSHAF_DIAL_HOLD_S * 0.5f))
        assertFalse(mushafDialShouldOpen(MUSHAF_DIAL_HOLD_DP_S * 3f, 2f))
        // Direction is irrelevant: stillness is stillness either way.
        assertTrue(mushafDialShouldOpen(-1f, 1f))
    }

    @Test
    fun `the hold is short enough to feel like a gesture and long enough to be one`() {
        // A quarter second: past a stall in an ordinary stroke, short of a
        // wait. And the threshold sits far below any real steering speed, so
        // a reader creeping through the chapters is not clicked into a trough.
        assertTrue(MUSHAF_DIAL_HOLD_S in 0.18f..0.35f)
        assertTrue(MUSHAF_DIAL_HOLD_DP_S < MUSHAF_DIAL_FLEE_DP_S / 8f)
    }

    @Test
    fun `the trough gives way to a hand that moves off, or leans on an end`() {
        assertTrue(mushafDialShouldClose(MUSHAF_DIAL_FLEE_DP_S * 1.2f, 0f))
        assertTrue(mushafDialShouldClose(-MUSHAF_DIAL_FLEE_DP_S * 1.2f, 0f))
        assertTrue(mushafDialShouldClose(0f, MUSHAF_DIAL_EDGE_S))
        // Ordinary aiming inside the trough keeps it open.
        assertFalse(mushafDialShouldClose(60f, 0f))
        assertFalse(mushafDialShouldClose(60f, MUSHAF_DIAL_EDGE_S * 0.5f))
    }

    @Test
    fun `opening and shutting cannot both be true of one hand`() {
        // A speed that opens the trough must not be a speed that shuts it,
        // or the dial would chatter between the tiers every frame.
        assertTrue(MUSHAF_DIAL_HOLD_DP_S < MUSHAF_DIAL_FLEE_DP_S)
        var speed = 0f
        while (speed < 2000f) {
            assertFalse(
                "speed $speed both opens and shuts",
                mushafDialShouldOpen(speed, 10f) && mushafDialShouldClose(speed, 0f),
            )
            speed += 5f
        }
    }

    @Test
    fun `the edge has to be leaned on, not brushed`() {
        // Passing through the end of the trough on the way to the last leaf
        // is normal. Staying there is the reader asking to get out.
        assertTrue(MUSHAF_DIAL_EDGE_S > 0.08f)
        assertTrue(MUSHAF_DIAL_EDGE_DP >= 8f)
    }

    @Test
    fun `the speed estimate converges on what the finger is doing`() {
        var speed = 0f
        repeat(40) { speed = mushafDialSpeed(speed, 800f, 0.016f) }
        assertEquals(800f, speed, 5f)
        // And lets go of it again when the hand slows.
        repeat(60) { speed = mushafDialSpeed(speed, 0f, 0.016f) }
        assertEquals(0f, speed, 5f)
    }

    @Test
    fun `the estimate answers a hand speeding up faster than one slowing down`() {
        // The two directions are not symmetric on purpose. Speeding up out of
        // the trough has to be answered at once, or the reader is still
        // magnified while their hand has already left; slowing down has to be
        // held for a moment so the stalls in a rough stroke do not read as a
        // stop. The dwell does most of that work now, so the fall is shorter
        // than it was under the old speed-ramp — but not equal to the rise.
        assertTrue(MUSHAF_DIAL_SPEED_RISE_TAU_S < MUSHAF_DIAL_SPEED_FALL_TAU_S)
        val up = mushafDialSpeed(0f, 600f, 0.016f)
        val down = 600f - mushafDialSpeed(600f, 0f, 0.016f)
        assertTrue("rise $up should outrun fall $down", up > down)
    }

    @Test
    fun `a hand that stops is read as stopped before the dwell can matter`() {
        // The frame meter feeds this a zero every frame while the hand is
        // down and not moving. Coming to rest from an ordinary steering pace
        // has to be recognised almost at once — the dwell is what decides a
        // stop was *meant*, and every millisecond the estimate spends
        // catching up is added to that wait for nothing.
        assertTrue(settleSeconds(from = 150f) < 0.15f)
    }

    @Test
    fun `a swept hand opens the trough inside half a second`() {
        // The worst case: a full sweep of the book, then stop and hold. The
        // whole latency the reader feels is the estimate catching up plus the
        // dwell, and past about half a second a hold stops reading as a
        // gesture and starts reading as the dial being slow.
        val total = settleSeconds(from = 900f) + MUSHAF_DIAL_HOLD_S
        assertTrue("trough opened after ${total}s", total < 0.55f)
    }

    /** How long the estimate takes to fall under the hold threshold. */
    private fun settleSeconds(from: Float): Float {
        var speed = from
        var elapsed = 0f
        while (speed >= MUSHAF_DIAL_HOLD_DP_S && elapsed < 5f) {
            speed = mushafDialSpeed(speed, 0f, 0.016f)
            elapsed += 0.016f
        }
        return elapsed
    }

    @Test
    fun `the speed estimate weighs a sample by its own elapsed time`() {
        // Frame-rate independence: one 32ms sample must move the estimate as
        // far as two 16ms samples of the same speed, or the tier changes
        // would depend on how often the frame clock happened to tick.
        val oneStep = mushafDialSpeed(0f, 600f, 0.032f)
        var twoSteps = mushafDialSpeed(0f, 600f, 0.016f)
        twoSteps = mushafDialSpeed(twoSteps, 600f, 0.016f)
        assertEquals(oneStep, twoSteps, 0.01f)
        // A sample with no time behind it changes nothing.
        assertEquals(123f, mushafDialSpeed(123f, 999f, 0f), 1e-5f)
    }

    @Test
    fun `the haptic is spaced by travel and by time, not by leaves crossed`() {
        // Chapter openings are about three dp apart at the book's scale, so a
        // tick per crossing is a buzz. Both guards are needed: a fast hand
        // clears the travel in a millisecond, and a creeping one clears the
        // time without having gone anywhere.
        assertTrue(mushafDialHapticDue(MUSHAF_DIAL_HAPTIC_PITCH_DP, MUSHAF_DIAL_HAPTIC_MIN_S))
        assertFalse(mushafDialHapticDue(MUSHAF_DIAL_HAPTIC_PITCH_DP * 0.5f, 1f))
        assertFalse(mushafDialHapticDue(50f, MUSHAF_DIAL_HAPTIC_MIN_S * 0.5f))
        // Either direction of travel counts.
        assertTrue(mushafDialHapticDue(-MUSHAF_DIAL_HAPTIC_PITCH_DP, 1f))
        // And the cadence stays under a comfortable ceiling.
        assertTrue(1f / MUSHAF_DIAL_HAPTIC_MIN_S < 30f)
    }

    @Test
    fun `the rubber gives the same on screen whatever the book's length`() {
        // Slack is held in dp and converted, not held in leaves: eighteen
        // leaves of overshoot is a whole screen in a short book and invisible
        // in a long one.
        assertEquals(MUSHAF_DIAL_SLACK_DP, mushafDialSlack(bookGain) / bookGain, 1e-3f)
        assertTrue(mushafDialSlack(bookGain) > mushafDialSlack(mushafDialBookGain(30, trackDp)))
    }

    @Test
    fun `the book runs right to left, end to end`() {
        assertEquals(0f, mushafDialFraction(1f, 604), 1e-6f)
        assertEquals(1f, mushafDialFraction(604f, 604), 1e-6f)
        assertEquals(0.5f, mushafDialFraction(302.5f, 604), 1e-4f)
        // Rubber band overshoot is clamped before it reaches the paper.
        assertEquals(0f, mushafDialFraction(-4f, 604), 1e-6f)
        assertEquals(1f, mushafDialFraction(700f, 604), 1e-6f)
        // A one-leaf book has nowhere to go.
        assertEquals(0f, mushafDialFraction(1f, 1), 1e-6f)
    }

    @Test
    fun `the dial bands past either end of the book rather than stopping dead`() {
        assertEquals(302f, rubberBandDialPosition(302f, 1f, 604f), 1e-4f)
        assertTrue(rubberBandDialPosition(-9f, 1f, 604f) < 1f)
        assertTrue(rubberBandDialPosition(-9f, 1f, 604f) > -9f)
        assertTrue(rubberBandDialPosition(700f, 1f, 604f) > 604f)
        assertTrue(rubberBandDialPosition(700f, 1f, 604f) < 700f)
    }

    @Test
    fun `a comb mark stands at the book's pitch, pinned to the thumb`() {
        val pitchPx = 3f
        val here = mushafDialTickX(thumbXPx = 500f, page = 100, at = 100f, pitchPx = pitchPx)
        val next = mushafDialTickX(thumbXPx = 500f, page = 101, at = 100f, pitchPx = pitchPx)
        assertEquals(500f, here, 1e-3f)
        assertEquals(pitchPx, here - next, 1e-3f)
    }

    @Test
    fun `later leaves stand to the left, as the book runs`() {
        val ahead = mushafDialTickX(400f, page = 300, at = 290f, pitchPx = 10f)
        val behind = mushafDialTickX(400f, page = 280, at = 290f, pitchPx = 10f)
        assertTrue("later leaf must sit left of the thumb", ahead < 400f)
        assertTrue("earlier leaf must sit right of the thumb", behind > 400f)
    }

    @Test
    fun `a fractional leaf slides the comb rather than jumping it`() {
        val pitchPx = 20f
        val at = mushafDialTickX(500f, page = 100, at = 100f, pitchPx = pitchPx)
        val halfway = mushafDialTickX(500f, page = 100, at = 100.5f, pitchPx = pitchPx)
        assertEquals(pitchPx / 2f, halfway - at, 1e-3f)
    }

    @Test
    fun `the comb travels exactly as far as the hand does`() {
        // The chapter tier's whole feel: the comb is pinned to the thumb, so
        // a chapter mark under the finger stays under the finger. Push the
        // book's scale by a dp of screen and the comb moves that same dp.
        val pitchPx = 3f
        val gain = 1f / 3f // leaves per px at this pitch
        val before = mushafDialTickX(500f, page = 120, at = 100f, pitchPx = pitchPx)
        // Ten px of screen to the right is ten px of comb to the right, and
        // the leaf under the thumb drops by what those ten px are worth.
        val after = mushafDialTickX(510f, page = 120, at = 100f - 10f * gain, pitchPx = pitchPx)
        assertEquals(before, after, 1e-3f)
    }

    @Test
    fun `the thumb's travel stops clear of both screen edges`() {
        // Either end of the rule lies inside the system's back-gesture strip.
        // The first and last leaves must still be grabbable.
        val width = 1000f
        val inset = 40f
        assertEquals(inset, mushafDialTrackX(0f, width, inset), 1e-3f)
        assertEquals(width - inset, mushafDialTrackX(1f, width, inset), 1e-3f)
        assertEquals(width / 2f, mushafDialTrackX(0.5f, width, inset), 1e-3f)
    }

    @Test
    fun `the track survives a rule narrower than its own insets`() {
        val x = mushafDialTrackX(0.5f, widthPx = 20f, insetPx = 40f)
        assertEquals(10f, x, 1e-3f)
    }

    @Test
    fun `the track clamps a fraction that has run past either end`() {
        assertEquals(30f, mushafDialTrackX(-0.4f, 1000f, 30f), 1e-3f)
        assertEquals(970f, mushafDialTrackX(1.6f, 1000f, 30f), 1e-3f)
    }

    @Test
    fun `reading a place off the track is the inverse of drawing one`() {
        // The trough reads the finger's x back into a fraction. If the two
        // disagreed, the leaf under the thumb would not be the leaf the
        // trough drew there.
        val width = 1000f
        val inset = 40f
        var f = 0f
        while (f <= 1f) {
            val x = mushafDialTrackX(f, width, inset)
            assertEquals(f, mushafDialTrackFraction(x, width, inset), 1e-4f)
            f += 0.05f
        }
        assertEquals(0f, mushafDialTrackFraction(-500f, width, inset), 1e-5f)
        assertEquals(1f, mushafDialTrackFraction(9_999f, width, inset), 1e-5f)
    }

    @Test
    fun `a track with no width reads as its own start`() {
        assertEquals(0f, mushafDialTrackFraction(15f, 40f, 90f), 1e-5f)
    }

    @Test
    fun `a finger at either screen edge still leaves the thumb clear of it`() {
        val width = 1000f
        val inset = 40f
        assertEquals(inset, mushafDialClampToTrack(-120f, width, inset), 0.001f)
        assertEquals(width - inset, mushafDialClampToTrack(9_999f, width, inset), 0.001f)
    }

    @Test
    fun `a finger inside the track is left exactly where it is`() {
        // The whole point of tracking: no smoothing, no lag, no correction.
        assertEquals(371.5f, mushafDialClampToTrack(371.5f, 1000f, 40f), 0.0f)
    }

    @Test
    fun `the hand and the leaf's seat share the same two ends`() {
        val width = 1000f
        val inset = 40f
        assertEquals(
            mushafDialTrackX(0f, width, inset),
            mushafDialClampToTrack(-1f, width, inset),
            0.001f,
        )
        assertEquals(
            mushafDialTrackX(1f, width, inset),
            mushafDialClampToTrack(width + 1f, width, inset),
            0.001f,
        )
    }

    @Test
    fun `the clamp survives a rule narrower than its own insets`() {
        assertEquals(20f, mushafDialClampToTrack(0f, 40f, 90f), 0.001f)
        assertEquals(20f, mushafDialClampToTrack(40f, 40f, 90f), 0.001f)
    }

    @Test
    fun `the trough holds the chapter the hand is inside`() {
        val marks = intArrayOf(1, 2, 50, 77, 604)
        assertEquals(2..49, mushafDialChapterRun(marks, 2, 604))
        assertEquals(2..49, mushafDialChapterRun(marks, 30, 604))
        assertEquals(50..76, mushafDialChapterRun(marks, 50, 604))
        assertEquals(77..603, mushafDialChapterRun(marks, 100, 604))
        // The last chapter runs to the back of the book.
        assertEquals(604..604, mushafDialChapterRun(marks, 604, 604))
        // Before the first mark, the first chapter.
        assertEquals(1..1, mushafDialChapterRun(marks, 1, 604))
    }

    @Test
    fun `a release in the chapter tier lands on the chapter's own first leaf`() {
        // What chapter granularity has to mean when the hand comes off: the
        // reader picked a chapter, so they get its opening, not whichever of
        // its leaves the arithmetic happened to leave the thumb over.
        val marks = intArrayOf(1, 2, 50, 77, 604)
        assertEquals(50, mushafDialChapterRun(marks, 63, 604).first)
        assertEquals(2, mushafDialChapterRun(marks, 49, 604).first)
    }

    @Test
    fun `a chapter that shares its leaf still brackets something`() {
        // The back of the book puts several chapters on one leaf, so two marks
        // land on the same page. An empty or backwards run would draw nothing
        // at exactly the place the reader needs the most help.
        val marks = intArrayOf(1, 600, 600, 601)
        val run = mushafDialChapterRun(marks, 600, 604)
        assertTrue("run $run", !run.isEmpty())
        assertTrue(run.first <= run.last)
    }

    @Test
    fun `an empty book still brackets the whole rule`() {
        assertEquals(1..604, mushafDialChapterRun(intArrayOf(), 3, 604))
        assertEquals(1..1, mushafDialChapterRun(intArrayOf(), 3, 0))
    }

    @Test
    fun `the label names a chapter in the comb and a run of verses in the trough`() {
        val leaf = MushafDialLabel(chapter = "Al-Baqarah", fromAyah = 6, toAyah = 16)
        assertEquals("Al-Baqarah", mushafDialLabelText(leaf, zoomed = false))
        assertEquals("Al-Baqarah  6–16", mushafDialLabelText(leaf, zoomed = true))
        // A leaf holding one verse says one number, not "282-282".
        val long = MushafDialLabel(chapter = "Al-Baqarah", fromAyah = 282, toAyah = 282)
        assertEquals("Al-Baqarah  282", mushafDialLabelText(long, zoomed = true))
    }
}
