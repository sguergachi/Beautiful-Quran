package com.beautifulquran.ui.reader

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperInkMotionTest {
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
