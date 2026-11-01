package com.beautifulquran.ui.reader

import androidx.compose.ui.graphics.Color
import com.beautifulquran.ui.theme.ShapedWordBloom
import com.beautifulquran.ui.theme.paperink.PaperInk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishPaperInkTest {
    private val text = "it is said"
    private val owner = text.indices
    private val bounds = englishPaperInkWordRanges(text, listOf(owner))
    private val frame = PaperInk.Frame(
        progress = .25f, wet = 1f, repeatPass = 0f, repeatTint = 0f, retained = 0f,
        dryInk = Color.Black, wetInk = Color.Black, repeatColor = Color.Red,
        unreadAlpha = .22f, pooling = .25f, reflection = .5f, softness = 0f,
        leadFade = 1f, flowing = false,
    )

    private fun split(frame: PaperInk.Frame): List<ShapedWordBloom.PaperInk> =
        englishPaperInkBlooms(listOf(ShapedWordBloom.PaperInk(
            owner, frame,
            fallback = listOf(
                ShapedWordBloom.UpcomingDim(owner, Color.White, .78f),
                ShapedWordBloom.InkReveal(owner, frame.progress, Color.White, .22f),
                ShapedWordBloom.ColorReveal(owner, frame.progress, Color.Red),
            ),
            after = listOf(ShapedWordBloom.UpcomingDim(owner, Color.White, .2f)),
        )), bounds).filterIsInstance<ShapedWordBloom.PaperInk>()

    @Test fun `multi-word gloss waits at each printed boundary and leads by one letter`() {
        val words = split(frame)
        assertEquals(listOf(0..1, 3..4, 6..9), words.map { it.range })
        assertEquals(listOf(1f, -1f, -1f), words.map { it.frame.progress })
        assertEquals(listOf(1f, 0f, 0f), words.map { it.frame.wet })
        words.forEach { word ->
            assertEquals(1f, word.frame.leadFade * 2f, 0f)
            assertTrue(word.frame.fadeLeadOnEntry)
            assertTrue(word.fallback.all { it.range == word.range })
            assertTrue(word.after.all { it.range == word.range })
        }
        val middle = split(frame.copy(progress = .4f))
        assertEquals(.5f, middle[1].frame.progress, 0f)
        assertEquals(-1f, middle[2].frame.progress, 0f)
    }

    @Test fun `word fields preserve a continuous prefix through the owner clock`() {
        for (step in 0..100) {
            val words = split(frame.copy(progress = step / 100f))
            words.forEachIndexed { index, word ->
                if (word.frame.progress > 0f) {
                    assertTrue(words.take(index).all { it.frame.progress == 1f })
                }
            }
        }
    }

    @Test fun `repeat and interrupted evaporation preserve each coat and material`() {
        val repeat = frame.copy(progress = .4f, repeatPass = 1f, repeatTint = .6f,
            retained = 1f, underProgress = .8f, underWet = .3f, evaporation = .4f)
        val words = split(repeat)
        assertEquals(listOf(1f, .5f, -1f), words.map { it.frame.progress })
        assertEquals(listOf(1f, 1f, .5f), words.map { it.frame.underProgress })
        words.forEach { word ->
            assertEquals(.4f, word.frame.evaporation, 0f)
            assertEquals(1f, word.frame.retained, 0f)
            assertEquals(.6f, word.frame.repeatTint, 0f)
            assertEquals(.3f, word.frame.underWet, 0f)
            assertTrue(word.frame.fadeLeadOnEntry)
            assertEquals(frame.pooling, word.frame.pooling, 0f)
        }
        assertEquals(words.map { it.frame }, split(repeat).map { it.frame })
    }

    @Test fun `warming uses printed fields while Arabic blooms keep their identity`() {
        val warm = englishPaperInkBlooms(listOf(ShapedWordBloom.PaperInkWarm(owner)), bounds)
        assertEquals(listOf(0..1, 3..4, 6..9), warm.map { it.range })
        assertTrue(warm.all { it is ShapedWordBloom.PaperInkWarm })
        val arabic = ShapedWordBloom.PaperInk(20..24, frame, emptyList())
        assertTrue(englishPaperInkBlooms(listOf(arabic), bounds).single() === arabic)
        assertTrue(!arabic.frame.fadeLeadOnEntry)
    }
}
