package com.beautifulquran.ui.reader

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.beautifulquran.data.model.Word
import com.beautifulquran.ui.theme.ShapedWordBloom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapedUnreadInkTest {
    private val text = AnnotatedString("one two")
    private val owner = text.indices
    private val floor get() = InkEngine.State.Upcoming.inkAlpha()

    private fun blooms(
        state: InkEngine.State,
        progress: Float,
        paper: Boolean,
        departure: PaperInkDeparture? = null,
        recess: Float = 0f,
        softHandoff: Boolean = false,
        keepUnreadFloor: Boolean = false,
    ): List<ShapedWordBloom> {
        val motion = InkMotion(
            ink = InkEngine.Word(state, false), lyricInk = mutableStateOf(1f),
            sweep = LetterSweep(mutableStateOf(progress), mutableStateOf(null), mutableStateOf(null)),
            repeatWash = RepeatWash(mutableStateOf(0f), mutableStateOf(0f), mutableStateOf(null)),
            glintAlpha = mutableStateOf(0f), glintIsRepeat = false,
            glintReplacedByRepeat = false, waslPrefix = null,
            tarji = mutableStateOf(InkEngine.GlintResonance.Idle),
            paperWet = mutableStateOf(if (state == InkEngine.State.Active) 1f else 0f),
            anticipateNextWord = false, paperDeparture = departure,
        )
        return buildShapedBlooms(
            motions = listOf(motion),
            words = listOf(Word(1, "كلمة", text.text, "kalima")),
            rendered = RenderedLineText(text, listOf(owner), IntRange.EMPTY),
            palette = WordInkPalette(Color.Black, Color.White, Color.Red,
                if (paper) PaperInkStyle(Color.Black, Color.Red) else null),
            glintInk = null, markAlpha = { 1f }, recessCover = { recess },
            flashWordPositions = emptySet(),
            searchHitWash = RepeatWash(mutableStateOf(0f), mutableStateOf(0f), mutableStateOf(null)),
            englishWordRanges = englishPaperInkWordRanges(text, listOf(owner)),
            softHandoff = softHandoff,
            keepUnreadFloor = keepUnreadFloor,
        )
    }

    /** Coverage of a glyph still wholly ahead of every reveal in this fixture. */
    private fun untouchedAlpha(blooms: List<ShapedWordBloom>): Float =
        blooms.fold(1f) { alpha, bloom ->
            alpha * when (bloom) {
                is ShapedWordBloom.UpcomingDim -> 1f - bloom.coverAlpha
                is ShapedWordBloom.InkReveal -> {
                    assertEquals(0f, bloom.progress, 0f)
                    bloom.restingAlpha
                }
                else -> 1f
            }
        }

    @Test fun `unread English glyphs keep the same strength when their owner starts`() {
        val before = untouchedAlpha(blooms(InkEngine.State.Upcoming, 0f, paper = true))
        val during = blooms(InkEngine.State.Active, .2f, paper = true)
            .filterIsInstance<ShapedWordBloom.PaperInk>().single { it.range == 4..6 }
        assertEquals(-1f, during.frame.progress, 0f)
        assertEquals("the shader cannot brighten a still-unread word", before,
            during.frame.unreadAlpha, .000001f)
        assertEquals("an unavailable field must have the same unread ink", before,
            untouchedAlpha(during.fallback), .000001f)
    }

    @Test fun `ordinary wash enters with the same faint ink as an upcoming word`() {
        val before = untouchedAlpha(blooms(InkEngine.State.Upcoming, 0f, paper = false))
        val entering = untouchedAlpha(blooms(InkEngine.State.Active, 0f, paper = false))
        assertEquals(floor, before, .000001f)
        assertEquals(before, entering, .000001f)
    }

    @Test fun `outgoing handoff keeps untouched words faint while laid ink can soften`() {
        for (paper in listOf(false, true)) {
            for (recess in listOf(0f, .2f, .5f, 1f - floor)) {
                assertEquals(floor, untouchedAlpha(blooms(InkEngine.State.Upcoming, 0f,
                    paper, recess = recess, softHandoff = true,
                    keepUnreadFloor = true)), .000001f)
            }
            assertEquals(.8f, untouchedAlpha(blooms(InkEngine.State.Upcoming, 1f,
                paper, recess = .2f, softHandoff = true,
                keepUnreadFloor = true)), .000001f)
        }
    }

    @Test fun `a verse at rest follows the recess instead of snapping to the floor`() {
        // Pressing play recesses every verse that was showing full ink. Its
        // cover must track the shared clock, so the ink fades with the chrome
        // rather than dropping to the Upcoming floor on the first frame.
        for (paper in listOf(false, true)) {
            for (recess in listOf(0f, .2f, .5f, 1f - floor)) {
                assertEquals(1f - recess, untouchedAlpha(blooms(InkEngine.State.Upcoming, 0f,
                    paper, recess = recess, softHandoff = true,
                    keepUnreadFloor = false)), .000001f)
            }
        }
    }

    @Test fun `a partial residual and an evaporating prefix keep their soft reveals`() {
        val residual = blooms(InkEngine.State.Upcoming, .2f, paper = false)
            .filterIsInstance<ShapedWordBloom.InkReveal>().single()
        assertEquals(.2f, residual.progress, 0f)
        val history = PaperInkEvaporation(upcoming = false)
        history.sample(PaperInkDeposit(.4f, .6f, 0f, 0f, 0f), 1f)
        history.transition(upcoming = true)
        val departing = blooms(InkEngine.State.Upcoming, 0f, paper = true,
            departure = PaperInkDeparture(history, mutableStateOf(.5f)))
            .filterIsInstance<ShapedWordBloom.PaperInk>()
        assertTrue(departing.isNotEmpty())
        assertTrue(departing.flatMap { it.fallback }.any { it is ShapedWordBloom.InkReveal })
        assertTrue(departing.all { it.frame.evaporation == .5f })
    }
}
