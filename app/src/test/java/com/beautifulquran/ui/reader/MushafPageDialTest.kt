package com.beautifulquran.ui.reader

import androidx.compose.ui.unit.dp
import kotlin.math.abs
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

    /** A phone's rule, in px, and the inset each end is held back by. */
    private val widthPx = 1080f
    private val insetPx = 42f

    @Test
    fun `the whole book lies across the measure, right end to left end`() {
        // The chapter tier reads the finger's place along the rule as a place
        // in the book — the same law the trough uses, over the whole 604. So
        // the far right is al-Fatihah and the far left is the back of the
        // book, in one stroke, on any width of phone.
        val book = 1..604
        assertEquals(1f, mushafDialTroughPage(1f, book), 1e-4f)
        assertEquals(604f, mushafDialTroughPage(0f, book), 1e-4f)
    }

    @Test
    fun `the comb the finger reads stands where the finger says it is`() {
        // The comb is nailed to the rule, and the tier is read off the same
        // scale, so a mark's place on screen and the finger's place over it
        // are the same arithmetic run in opposite directions. Anything else
        // and the bracket lands somewhere the reader's finger is not.
        for (page in intArrayOf(1, 2, 50, 300, 603, 604)) {
            val x = mushafDialTrackX(1f - mushafDialFraction(page.toFloat(), 604), widthPx, insetPx)
            val read = mushafDialTroughPage(mushafDialTrackFraction(x, widthPx, insetPx), 1..604)
            assertEquals(page.toFloat(), read, 0.01f)
        }
    }

    @Test
    fun `the comb never runs off the end of its own rule`() {
        // What a comb carried under the thumb did: slide until the last
        // chapter sat mid-rule with bare line beyond it, and the reader could
        // not tell the end of the book from the end of the comb.
        for (page in intArrayOf(1, 604)) {
            val x = mushafDialTrackX(1f - mushafDialFraction(page.toFloat(), 604), widthPx, insetPx)
            assertTrue("leaf $page stood at $x", x >= insetPx - 0.01f && x <= widthPx - insetPx + 0.01f)
        }
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
        assertTrue(mushafDialShouldOpen(-1f, MUSHAF_DIAL_HOLD_S))
    }

    @Test
    fun `the hold is long enough that only a deliberate one reaches it`() {
        // Around two seconds: past every slowing inside a scrub, plus the
        // extra second the user asked for — still one gesture rather than a
        // wait. The speed gate is the other half of the test.
        assertTrue(MUSHAF_DIAL_HOLD_S in 1.4f..1.8f)
        assertTrue(MUSHAF_DIAL_HOLD_DP_S < 30f)
    }

    @Test
    fun `an insistent hold asks for the trough from anywhere, and costs more`() {
        // The stillness test is the same one; only the clock is longer. It has
        // to be long enough that nothing a reader does on the way somewhere
        // reaches it, which means clearly past the ordinary hold, and short
        // enough to still be a pause rather than a wait.
        assertTrue(mushafDialInsists(0f, MUSHAF_DIAL_INSIST_S))
        assertFalse(mushafDialInsists(0f, MUSHAF_DIAL_INSIST_S * 0.9f))
        assertFalse(mushafDialInsists(MUSHAF_DIAL_HOLD_DP_S * 3f, 10f))
        // The two clocks have converged, now that the ordinary hold is itself
        // deliberate: what the insistent one still buys is not a longer wait
        // but a hold that is answered *wherever* the finger is. It must stay
        // the later of the two, so a hold on the line is never the slower way
        // in — the reader who is somewhere legitimate is never punished for it.
        assertTrue(MUSHAF_DIAL_INSIST_S > MUSHAF_DIAL_HOLD_S)
        assertTrue(MUSHAF_DIAL_INSIST_S in 1.8f..2.2f)
    }

    @Test
    fun `every ordinary hold is already an insistent one waiting`() {
        // The two are nested, not rival: a hold that has reached the long
        // clock has certainly passed the short one. So the insistent path can
        // only ever *widen* what opens the trough, never move it — the standing
        // closing is the one thing separating them, and it is lifted by travel
        // rather than by where the finger has got to.
        var held = 0f
        while (held <= 3f) {
            if (mushafDialInsists(0f, held)) assertTrue(mushafDialShouldOpen(0f, held))
            held += 0.02f
        }
    }

    @Test
    fun `the trough is left by going past an end of it, and by nothing else`() {
        // A 360 dp rule at 3x: the measure runs from the trough inset to that
        // far from the other end, and the run-out is what lies beyond.
        val width = 1080f
        val troughInset = (14f + 26f) * 3f
        assertTrue(mushafDialPastTrough(troughInset - 1f, width, troughInset))
        assertTrue(mushafDialPastTrough(width - troughInset + 1f, width, troughInset))
        // Both ends of the measure itself are still inside it — the last leaf
        // is a place the reader has to be able to sit on without falling out.
        assertFalse(mushafDialPastTrough(troughInset, width, troughInset))
        assertFalse(mushafDialPastTrough(width - troughInset, width, troughInset))
        assertFalse(mushafDialPastTrough(width / 2f, width, troughInset))
    }

    @Test
    fun `the ends of the book stand in the run-out, so place cannot guard the open`() {
        // The regression this pins: the chapter tier lays all 604 leaves across
        // the *full* measure, while the run-out is measured against the
        // trough's shorter one. So a finger on al-Fatihah or an-Nas is "past
        // the trough" by construction, and while the ordinary open was refused
        // to anyone standing there, the two ends of the book — the two places
        // a reader most often sweeps to — could only be opened by holding for
        // a second and a half. What lets the trough open is now what the hand
        // has done since the last closing, not where it has got to.
        val width = 1080f
        val density = 2.625f
        val inset = 14f * density
        val troughInset = (14f + 26f) * density
        for (page in intArrayOf(1, 604)) {
            val x = mushafDialTrackX(1f - mushafDialFraction(page.toFloat(), 604), width, inset)
            assertTrue("leaf $page stood at $x", mushafDialPastTrough(x, width, troughInset))
        }
        // And the middle of the book is not, so this is a fact about the ends.
        val middle = mushafDialTrackX(1f - mushafDialFraction(302f, 604), width, inset)
        assertFalse(mushafDialPastTrough(middle, width, troughInset))
    }

    @Test
    fun `the run-out is a target, and it does not eat the chapter`() {
        // Wide enough to aim at rather than arrive at, on the narrowest phone
        // this ships to; and both run-outs together still leave the chapter
        // most of the rule, or picking a leaf inside it gets cramped.
        assertTrue(MushafDialRunOut >= 20.dp)
        val rule = 320.dp
        val measure = rule - (MushafDialEdgeInset + MushafDialRunOut) * 2f
        assertTrue(measure > rule * 0.6f)
    }

    @Test
    fun `a hand that moves fast inside the trough keeps it`() {
        // The rule this replaced: a hand moving at a pace was handed the whole
        // book back mid-gesture. Working quickly inside a long chapter is the
        // tier's own job. Closing now reads a place, not a speed — so however
        // fast the finger is going, anywhere over the measure keeps the trough.
        val width = 1080f
        val troughInset = 120f
        var x = troughInset
        while (x <= width - troughInset) {
            assertFalse("x $x drops the trough", mushafDialPastTrough(x, width, troughInset))
            x += 5f
        }
        // And no speed at all opens it: the hold is the only way in.
        var speed = MUSHAF_DIAL_HOLD_DP_S
        while (speed < 2000f) {
            assertFalse("speed $speed opens the trough", mushafDialShouldOpen(speed, 10f))
            speed += 5f
        }
    }

    @Test
    fun `coming off the line the finger pressed on is leaving, either way`() {
        // The rule is the instrument. There is nothing above or below it to
        // aim at, so travel across it can only mean the reader is done — and
        // it is read as a displacement from the press, so a slow deliberate
        // lift counts exactly as much as a flick.
        val press = 2143f
        val stray = 74f
        assertTrue(mushafDialStrayed(press - stray - 1f, press, stray))
        assertTrue(mushafDialStrayed(press + stray + 1f, press, stray))
        // Ordinary drift along a scrub is still on the line.
        assertFalse(mushafDialStrayed(press, press, stray))
        assertFalse(mushafDialStrayed(press - stray * 0.5f, press, stray))
        assertFalse(mushafDialStrayed(press + stray * 0.5f, press, stray))
    }

    @Test
    fun `the hud holds steady until the band is nearly spent`() {
        // Elastic-band tension: ordinary drift inside the band barely moves
        // the label, and the lean arrives steeply only as the hand nears the
        // edge — where the pop is actually imminent. Clamped at the stray:
        // past it the tier is already gone and a lean would lie.
        val stray = 74f
        val lean = 20f
        assertEquals(0f, mushafDialHudLean(0f, stray, lean), 1e-4f)
        // Half the band: an eighth of the lean — a quarter: almost nothing.
        assertEquals(lean * 0.125f, mushafDialHudLean(stray * 0.5f, stray, lean), 1e-4f)
        assertEquals(-lean * 0.125f, mushafDialHudLean(-stray * 0.5f, stray, lean), 1e-4f)
        assertEquals(lean * 0.015625f, mushafDialHudLean(stray * 0.25f, stray, lean), 1e-6f)
        // Full tension stands exactly at the break, and never lies past it.
        assertEquals(lean, mushafDialHudLean(stray, stray, lean), 1e-4f)
        assertEquals(lean, mushafDialHudLean(stray * 3f, stray, lean), 1e-4f)
        assertEquals(-lean, mushafDialHudLean(-stray * 3f, stray, lean), 1e-4f)
    }

    @Test
    fun `the hud text stops a margin short of the glass, flush aligned`() {
        // The collision is the *text's*, not the plate's: parked at either
        // wall, the words sit edgeMargin from the glass and the plate hangs
        // past it by exactly the padding that is not wanted. With an inset
        // of 21 and a margin of 8, the plate parks 13 out on either side.
        val track = 1000f
        val plate = 300f
        val inset = 21f
        val margin = 8f
        val slack = inset - margin
        assertEquals(350f, mushafDialHudX(500f, plate, track, inset, margin), 1e-4f)
        // Left wall: text left edge at +8, plate hanging 13 past the glass.
        assertEquals(-slack, mushafDialHudX(0f, plate, track, inset, margin), 1e-4f)
        assertEquals(-slack, mushafDialHudX(-200f, plate, track, inset, margin), 1e-4f)
        assertEquals(margin, mushafDialHudX(0f, plate, track, inset, margin) + inset, 1e-4f)
        // Right wall: mirrored — text right edge at track−8.
        assertEquals(track - plate + slack, mushafDialHudX(track, plate, track, inset, margin), 1e-4f)
        assertEquals(track - plate + slack, mushafDialHudX(track + 400f, plate, track, inset, margin), 1e-4f)
        assertEquals(
            margin,
            track - (mushafDialHudX(track, plate, track, inset, margin) + plate - inset),
            1e-4f,
        )
    }

    @Test
    fun `every chapter owns a stable cell and every cell is selectable`() {
        // Selection reads stable cells, not the lensed drawing: the cells
        // partition the whole measure, never move under a moving hand, and
        // stay ordered with breathing room on every screen. Al-Baqarah (idx
        // 1) opens one page after al-Fatihah; chapter 18 sat in the crushed
        // head; three surahs stack on page 601 and three on 603 — every one
        // of them must own a slice of the rule a finger can hold.
        val marks = intArrayOf(
            1, 2, 50, 77, 106, 128, 151, 177, 187, 208, 221, 235, 249, 255,
            262, 267, 282, 293, 305, 312, 322, 332, 342, 350, 359, 367, 377,
            385, 396, 404, 411, 415, 418, 428, 434, 440, 446, 453, 458, 467,
            477, 483, 489, 496, 499, 502, 507, 511, 515, 518, 520, 523, 526,
            528, 531, 534, 537, 542, 545, 549, 551, 553, 554, 556, 558, 560,
            562, 564, 566, 568, 570, 572, 574, 575, 577, 578, 580, 582, 583,
            585, 586, 587, 587, 589, 590, 591, 591, 592, 593, 594, 595, 595,
            596, 596, 597, 597, 598, 598, 599, 599, 600, 600, 601, 601, 601,
            602, 602, 602, 603, 603, 603, 604, 604, 604,
        )
        val pages = 604

        fun check(density: Float, width: Float) {
            val inset = 14f * density
            val rule = density
            val seats = mushafDialCombCellSeats(marks, pages, inset, width, rule)
            // Invariant 1: the cells tile the measure in order, each at
            // least a minimum gap wide, none outside the track.
            val minGap = rule * 1.5f
            for (i in 0 until marks.size - 1) {
                assertTrue(
                    "cell gap collapsed [$i] on ${width}px: ${seats[i] - seats[i + 1]}",
                    seats[i] - seats[i + 1] >= minGap / 2f && seats[i] in inset..width - inset,
                )
            }
            assertTrue(seats[marks.size - 1] in inset..width - inset)
            // Invariant 2: every chapter's cell is held by some finger
            // position — sweep the whole rule and collect who wins.
            val reachable = BooleanArray(marks.size)
            for (finger in (inset.toInt()..(width - inset).toInt())) {
                reachable[mushafDialChapterAt(seats, finger.toFloat())] = true
            }
            val missing = (0 until marks.size).filter { !reachable[it] }
            assertTrue(
                "chapters with no cell of their own on ${width}px: $missing",
                missing.isEmpty(),
            )
            // And the two chapters that were reported broken by name.
            assertTrue(reachable[1])
            assertTrue(reachable[17])
        }

        check(density = 2.625f, width = 1080f)
        check(density = 2.0f, width = 800f)
        check(density = 3.5f, width = 1440f)
    }

    @Test
    fun `the lensed comb stays ordered under any hand`() {
        // The lensed drawing is decoration, but decoration still has laws:
        // it never folds back on itself, whatever the finger does.
        val marks = intArrayOf(1, 2, 50, 77, 106, 128, 151, 177, 187, 208)
        val pages = 208
        val density = 2.625f
        val width = 1080f
        val inset = 14f * density
        var sigmaMag = 1f
        while (sigmaMag <= 1.61f) {
            for (finger in (inset.toInt()..(width - inset).toInt())) {
                val drawn = mushafDialCombDrawnXs(
                    marks,
                    pages,
                    finger.toFloat(),
                    isLensed = true,
                    combInk = 1f,
                    insetPx = inset,
                    widthPx = width,
                    rulePx = density,
                    lensSigmaPx = MUSHAF_DIAL_LENS_SIGMA_DP * density * sigmaMag,
                    tailPushPx = 10f * density,
                    epsilonPx = 1.8f * density,
                )
                for (i in 0 until marks.size - 1) {
                    assertFalse(drawn[i].isNaN())
                    assertTrue(drawn[i] > drawn[i + 1])
                }
            }
            sigmaMag += 0.15f
        }
    }

    @Test
    fun `a wider hud collides sooner without any font knowledge`() {
        // The width arrives measured, so whatever set it — a bigger HUD type,
        // a user font scale — buys an earlier wall for free. Same hand, same
        // rule: the wider plate sits further left.
        val track = 1000f
        val hand = 700f
        val inset = 21f
        assertTrue(
            mushafDialHudX(hand, 500f, track, inset) > mushafDialHudX(hand, 800f, track, inset)
        )
        // The wider plate has already met its wall at this hand.
        assertEquals(track - 800f + inset - MUSHAF_DIAL_HUD_EDGE_MARGIN_PX, mushafDialHudX(hand, 800f, track, inset), 1e-4f)
        // A plate wider than the rule itself parks at the left margin and stays put.
        assertEquals(-(inset - MUSHAF_DIAL_HUD_EDGE_MARGIN_PX), mushafDialHudX(track * 0.9f, track * 2f, track, inset), 1e-4f)
    }

    @Test
    fun `the hud's full lean stays short of the stray band itself`() {
        // The label warns; it does not leave. If the lean could carry the HUD
        // as far as the hand has come off the line, the warning and the event
        // it warns of would be the same distance, and the first said nothing.
        assertTrue(MushafDialHudLean < MushafDialStray)
    }

    @Test
    fun `the stray band clears the grab strip's own half-height`() {
        // Inside the strip the finger is still on the rule it took hold of.
        // If the band were narrower than the paper the reader is allowed to
        // press, a scrub could end itself without the hand leaving the target.
        assertTrue(MushafDialStray > MushafDialBelowGrab / 2f)
    }

    @Test
    fun `the run-out resists, so overshooting the last leaf costs nothing`() {
        // Sweeping down to a chapter's last leaf overshoots the end of the
        // measure — that is what aiming at an end looks like. Crossing starts
        // a clock; coming back inside is what clears it, and the caller does
        // that by resetting the count, so a brush past never reaches here.
        assertFalse(mushafDialShouldLeaveTrough(0f, false))
        assertFalse(mushafDialShouldLeaveTrough(MUSHAF_DIAL_RUNOUT_S * 0.5f, false))
        assertTrue(mushafDialShouldLeaveTrough(MUSHAF_DIAL_RUNOUT_S, false))
        assertTrue(mushafDialShouldLeaveTrough(MUSHAF_DIAL_RUNOUT_S * 2f, false))
        // Straying takes no dwell at all: the two ways out are weighted
        // differently on purpose, and this is the asymmetry.
        assertTrue(mushafDialShouldLeaveTrough(0f, true))
        // And the resistance is a beat, not a wait. It used to be pinned under
        // the hold that opened the trough, on the reasoning that leaving must
        // not cost more than arriving. That tie is gone deliberately: the hold
        // has since been walked down twice toward the floor its speed gate
        // allows, and entering and leaving turned out not to be one act.
        // Arriving should cost as close to nothing as possible; leaving is the
        // one place a little stickiness is wanted, because the end of the
        // measure is somewhere the reader legitimately aims. What still has to
        // hold is that it is a beat in absolute terms.
        assertTrue(MUSHAF_DIAL_RUNOUT_S in 0.1f..0.3f)
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
    fun `a swept hand still opens the trough before the insistent clock`() {
        // The worst case: a full sweep of the book, then stop and hold. The
        // whole latency the reader feels is the estimate catching up plus the
        // dwell, and the estimate's share of it has to stay small — otherwise
        // the hold on the line lands after the hold that works anywhere, and
        // the ordinary way in would be the slow one.
        val total = settleSeconds(from = 900f) + MUSHAF_DIAL_HOLD_S
        assertTrue("trough opened after ${total}s", total < MUSHAF_DIAL_INSIST_S)
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
    fun `the label names a chapter in the comb, and the leaf itself in the trough`() {
        val leaf = MushafDialLabel(number = 2, chapter = "Al-Baqarah", fromAyah = 6, toAyah = 16)
        assertEquals("2  Al-Baqarah", mushafDialLabelHead(leaf, zoomed = false, page = 42))
        assertEquals("Al-Baqarah  ·  pg. 42", mushafDialLabelHead(leaf, zoomed = true, page = 42))
        assertEquals("Ayah 6–16", mushafDialLabelFoot(leaf, zoomed = true))
        // A leaf holding one verse says one number, not "282-282".
        val one = MushafDialLabel(number = 2, chapter = "Al-Baqarah", fromAyah = 282, toAyah = 282)
        assertEquals("Ayah 282", mushafDialLabelFoot(one, zoomed = true))
    }

    @Test
    fun `the comb keeps the verse line's paper without writing on it`() {
        // The head must not move when the trough opens, so the foot is empty
        // rather than absent at chapter tier — the Column reserves both lines
        // either way.
        val leaf = MushafDialLabel(number = 2, chapter = "Al-Baqarah", fromAyah = 6, toAyah = 16)
        assertEquals("", mushafDialLabelFoot(leaf, zoomed = false))
    }
    @Test
    fun `the comb stays on the hairline at rest`() {
        // True place in the book: leaf 1 at the right, 604 at the left.
        // Magnification is a lens under the finger, not a warped book.
        val marks = intArrayOf(1, 2, 50, 51, 604)
        for (mark in marks) {
            val f = mushafDialChapterFraction(mark.toFloat(), marks, 604)
            val prop = mushafDialFraction(mark.toFloat(), 604)
            assertEquals(prop, f, 1e-4f)
        }
        // Ordering still preserved.
        for (i in 1 until marks.size) {
            val prev = mushafDialChapterFraction(marks[i - 1].toFloat(), marks, 604)
            val cur = mushafDialChapterFraction(marks[i].toFloat(), marks, 604)
            assertTrue(prev < cur)
        }
    }

    @Test
    fun `the lens magnifies closer marks more than farther ones`() {
        // Fisheye: closer to the centre is taller and pushed farther out.
        val sigma = 120f
        val mag = MUSHAF_DIAL_LENS_MAG
        val heightMag = MUSHAF_DIAL_LENS_HEIGHT_GAIN
        val centre = 540f
        val near = centre + 10f
        val far = centre + 90f
        val nearFactor = mushafDialLensFactor(10f, sigma, mag)
        val farFactor = mushafDialLensFactor(90f, sigma, mag)
        assertTrue(nearFactor > farFactor)
        assertTrue(nearFactor > 1f && farFactor >= 1f)
        val nearLensed = mushafDialLensedX(near, centre, sigma, mag)
        val farLensed = mushafDialLensedX(far, centre, sigma, mag)
        assertTrue(nearLensed - centre > 10f)
        assertTrue(farLensed - centre > 90f)
        // But near is magnified more: ratio of lensed distance to true distance
        assertTrue((nearLensed - centre) / 10f > (farLensed - centre) / 90f)
        // Height follows the same falloff.
        val nearH = mushafDialLensFactor(10f, sigma, heightMag)
        val farH = mushafDialLensFactor(90f, sigma, heightMag)
        assertTrue(nearH > farH)
    }

    @Test
    fun `steering inside a long chapter still moves through its leaves`() {
        // True distribution: within a chapter the finger walks leaves right to left.
        val marks = intArrayOf(1, 2, 50, 51, 604)
        val fNear = mushafDialChapterFraction(5f, marks, 604)
        val fDeep = mushafDialChapterFraction(30f, marks, 604)
        assertTrue(fDeep > fNear)
        val mid = (fNear + fDeep) / 2f
        val page = mushafDialChapterPage(mid, marks, 604)
        assertTrue(page > 2f && page < 49f)
    }

    @Test
    fun `the comb reads back the page under the finger`() {
        val marks = intArrayOf(1, 2, 50, 51, 286, 604)
        for (mark in marks) {
            val x = mushafDialTrackX(
                1f - mushafDialChapterFraction(mark.toFloat(), marks, 604),
                widthPx,
                insetPx,
            )
            val read = mushafDialChapterPage(
                1f - mushafDialTrackFraction(x, widthPx, insetPx),
                marks,
                604,
            )
            val run = mushafDialChapterRun(marks, mark, 604)
            assertTrue("mark $mark read $read run $run", read >= run.first - 1.01f && read <= run.last + 1.01f)
        }
    }
}
