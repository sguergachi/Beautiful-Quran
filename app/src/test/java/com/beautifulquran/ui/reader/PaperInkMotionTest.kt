package com.beautifulquran.ui.reader

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import com.beautifulquran.domain.TajweedPacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperInkMotionTest {
    private fun motion(
        state: InkEngine.State,
        progress: State<Float>,
        prefix: WaslPrefix? = null,
        previous: InkMotion? = null,
        wet: Float = 1f,
    ) = InkMotion(
        ink = InkEngine.Word(state, false), lyricInk = mutableStateOf(1f),
        sweep = LetterSweep(progress, mutableStateOf(null), mutableStateOf(null)),
        repeatWash = RepeatWash(mutableStateOf(0f), mutableStateOf(0f), mutableStateOf(null)),
        glintAlpha = mutableStateOf(0f), glintIsRepeat = false,
        glintReplacedByRepeat = false, waslPrefix = prefix,
        tarji = mutableStateOf(InkEngine.GlintResonance.Idle),
        paperWet = mutableStateOf(wet), previous = previous,
    )

    @Test fun connectedOpeningContinuesTheSameWetLetterEdgeAcrossHandoff() {
        val style = PaperInkStyle(Color.Black, Color.Red)
        // The reported 64:12 pair, plus short and wrapped connected openings.
        for ((donor, target) in listOf("فَإِن" to "تَوَلَّيۡتُمۡ", "مَن" to "يَشۡرِي",
            "مِن" to "رَّبِّكُمۡ", "بِمَعۡرُوفٖ" to "وَلَا")) {
            val connection = requireNotNull(TajweedPacing.connection(donor, target))
            val handoff = connection.at(1f, .75f, .45f)
            val clock = mutableStateOf(0f)
            val before = motion(InkEngine.State.Active, mutableStateOf(1f))
            val prefix = WaslPrefix(clock, waslContinuationStart(connection.prefixFraction, 1.6f),
                1.6f, connection.prefixFraction)
            val upcoming = motion(InkEngine.State.Upcoming, mutableStateOf(0f), prefix, before)
            val samples = (0..12).map { step ->
                clock.value = handoff * step / 12
                upcoming.paperInkFrame(style).progress
            }
            assertEquals(0f, samples.first(), 0f)
            assertTrue(samples.zipWithNext().all { (a, b) -> b > a })
            assertTrue(samples.last() < connection.prefixFraction)
            val arriving = upcoming.paperInkFrame(style)
            val active = motion(InkEngine.State.Active, mutableStateOf(samples.last()))
                .paperInkFrame(style)
            assertEquals("$target must keep its deposit", active.progress, arriving.progress, 0f)
            assertEquals("$target must not suddenly change pigment", active.wet, arriving.wet, 0f)
            assertEquals(0f, arriving.retained, 0f)
            // Pausing the donor freezes this same edge and dries both sides alike.
            val pausedBefore = motion(InkEngine.State.Active, mutableStateOf(1f), wet = .4f)
            assertEquals(.4f, motion(InkEngine.State.Upcoming, mutableStateOf(0f), prefix,
                pausedBefore).paperInkFrame(style).wet, 0f)
        }
    }

    @Test fun unconnectedOpeningBeginsUnreadAndFillsGraduallyOnItsOwnClock() {
        val clock = mutableStateOf(0f)
        val style = PaperInkStyle(Color.Black, Color.Red)
        val opening = motion(InkEngine.State.Active, clock)
        assertEquals(0f, opening.paperInkFrame(style).progress, 0f)
        clock.value = .05f
        assertEquals(.05f, opening.paperInkFrame(style).progress, 0f)
        // A direct start has no predecessor's prefix to import.
        val unread = motion(InkEngine.State.Upcoming, mutableStateOf(1f))
        assertEquals(-1f, unread.paperInkFrame(style).progress, 0f)
        assertEquals(0f, unread.paperInkFrame(style).wet, 0f)
    }

    @Test fun repeatRetainsFirstCoatProgressAndItsIndependentDryingClock() {
        val first = mutableStateOf(.9f)
        val underWet = mutableStateOf(.65f)
        val repeat = mutableStateOf(0f)
        val freshWet = mutableStateOf(1f)
        val motion = InkMotion(
            ink = InkEngine.Word(InkEngine.State.Active, true),
            lyricInk = mutableStateOf(1f),
            sweep = LetterSweep(first, mutableStateOf(null), mutableStateOf(null)),
            repeatWash = RepeatWash(repeat, mutableStateOf(1f), mutableStateOf(null)),
            glintAlpha = mutableStateOf(0f), glintIsRepeat = true,
            glintReplacedByRepeat = false, waslPrefix = null,
            tarji = mutableStateOf(InkEngine.GlintResonance.Idle),
            paperWet = freshWet, paperFirstWet = underWet,
        )
        val style = PaperInkStyle(Color.Black, Color.Red)
        val initial = motion.paperInkFrame(style)
        assertEquals(.9f, initial.underProgress, 0f)
        assertEquals(.65f, initial.underWet, 0f)
        assertEquals(0f, initial.progress, 0f)
        assertEquals(1f, initial.wet, 0f)
        underWet.value = .3f
        first.value = 1f
        repeat.value = .4f
        val later = motion.paperInkFrame(style)
        assertEquals(1f, later.underProgress, 0f)
        assertEquals(.3f, later.underWet, 0f)
        assertEquals(.4f, later.progress, 0f)
        assertEquals(1f, later.wet, 0f)
    }

    @Test fun closingLetterUsesItsPacedPositionOnFirstAndRepeatPasses() {
        val word = ActiveWord(31, 15, durationMs = 4000)
        val pacing = requireNotNull(InkEngine.pacing("ٱلۡمُسۡرِفِينَ", word, isAyahFinal = true))
        val time = mutableStateOf(.85f)
        val wash = derivedStateOf { pacing.at(time.value) }
        val letters = derivedStateOf { pacing.atLetters(time.value) }
        for (repeat in listOf(false, true)) {
            val motion = InkMotion(
                ink = InkEngine.Word(InkEngine.State.Active, repeat),
                lyricInk = mutableStateOf(1f),
                sweep = LetterSweep(wash, mutableStateOf(1.1092f), mutableStateOf(pacing),
                    time, letters),
                repeatWash = RepeatWash(wash, mutableStateOf(if (repeat) 1f else 0f),
                    mutableStateOf(1.1092f), letters),
                glintAlpha = mutableStateOf(0f), glintIsRepeat = repeat,
                glintReplacedByRepeat = false, waslPrefix = null,
                tarji = mutableStateOf(InkEngine.GlintResonance.Idle),
            )
            val style = PaperInkStyle(Color.Black, Color.Red)
            time.value = .85f
            val held = motion.paperInkFrame(style).progress
            assertTrue("the last letter must receive ink during its hold", held >= 7f / 8f)
            assertEquals(pacing.atLetters(.85f), held, 0f)
            // Pausing retains this deposit; resuming advances it on the same clock.
            assertEquals(held, motion.paperInkFrame(style).progress, 0f)
            time.value = 1f
            assertEquals(1f, motion.paperInkFrame(style).progress, 0f)
        }
    }
}
