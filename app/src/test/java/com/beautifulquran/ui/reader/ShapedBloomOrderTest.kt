package com.beautifulquran.ui.reader

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.beautifulquran.data.model.Word
import com.beautifulquran.ui.theme.ShapedWordBloom
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The paint order of one shaped ayah's blooms.
 *
 * A word's paper cover reaches past its own box to catch glyph overhang, and
 * that is safe only because "any neighbour ink it laps is redrawn by the same
 * text pass" ([androidx.compose.ui.Modifier.shapedWordBloom]'s `coverPad`).
 * The glint halo breaks that assumption: it is a one-shot blur emitted for one
 * word, and nothing redraws it. Emitted per word, the *next* word's cover
 * landed on top of it and cut it square along the line box, so a lit word wore
 * a rectangle of paper.
 *
 * So: every cover goes down before any ink. This test is what stops the two
 * being interleaved again.
 */
class ShapedBloomOrderTest {

    private fun motion(
        state: InkEngine.State,
        sweep: Float,
        glint: Float,
        repeat: Boolean = false,
    ): InkMotion = InkMotion(
        ink = InkEngine.Word(state = state, repeat = repeat),
        lyricInk = mutableStateOf(1f),
        sweep = LetterSweep(
            progress = mutableStateOf(sweep),
            feather = mutableStateOf(null),
            pacing = mutableStateOf(null),
        ),
        repeatWash = RepeatWash(
            progress = mutableStateOf(0f),
            alpha = mutableStateOf(0f),
            feather = mutableStateOf(null),
        ),
        glintAlpha = mutableStateOf(glint),
        glintIsRepeat = false,
        glintReplacedByRepeat = false,
        waslPrefix = null,
        tarji = mutableStateOf(InkEngine.GlintResonance.Idle),
    )

    private fun word(position: Int) = Word(
        position = position,
        arabic = "كلمة",
        translation = "word",
        transliteration = "kalima",
    )

    private val palette = WordInkPalette(
        fullInk = Color.White,
        paper = Color.Black,
        repeatInk = Color(0xFFE06A18),
    )

    /** A lit word mid-ayah: one behind it done, one ahead still unread. */
    private fun blooms(): List<ShapedWordBloom> = buildShapedBlooms(
        motions = listOf(
            motion(InkEngine.State.Recited, sweep = 1f, glint = 0f),
            motion(InkEngine.State.Active, sweep = 0.5f, glint = 1f),
            motion(InkEngine.State.Upcoming, sweep = 0f, glint = 0f),
        ),
        words = listOf(word(1), word(2), word(3)),
        rendered = RenderedLineText(
            text = AnnotatedString("كلمة كلمة كلمة"),
            wordRanges = listOf(0..3, 5..8, 10..13),
            markRange = IntRange.EMPTY,
        ),
        palette = palette,
        glintInk = Color(0xFFFFF0C7),
        markAlpha = { 1f },
        recessCover = { 0f },
        flashWordPositions = emptySet(),
        searchHitWash = RepeatWash(
            progress = mutableStateOf(0f),
            alpha = mutableStateOf(0f),
            feather = mutableStateOf(null),
        ),
    )

    private fun isCover(b: ShapedWordBloom) =
        b is ShapedWordBloom.UpcomingDim || b is ShapedWordBloom.InkReveal

    @Test
    fun `no paper cover is painted over any ink`() {
        val list = blooms()
        val firstInk = list.indexOfFirst { it is ShapedWordBloom.ColorReveal }
        assertTrue("the fixture must produce ink to order against", firstInk >= 0)
        val lateCover = list.withIndex().firstOrNull { (i, b) -> i > firstInk && isCover(b) }
        assertTrue(
            "a ${lateCover?.value?.let { it::class.simpleName }} is painted at index " +
                "${lateCover?.index} after ink at $firstInk — a cover laid over ink " +
                "cuts the glint halo square along the line box",
            lateCover == null,
        )
    }

    @Test
    fun `the lit word still gets its halo`() {
        val glints = blooms().filterIsInstance<ShapedWordBloom.ColorReveal>()
            .filter { it.glowAlpha > 0f }
        assertTrue("the active word should emit a glowing ink layer", glints.isNotEmpty())
    }

    @Test
    fun `unread words are still covered`() {
        val list = blooms()
        assertTrue(
            "the unread word needs a cover, or nothing dims it",
            list.any { it is ShapedWordBloom.UpcomingDim } &&
                list.any { it is ShapedWordBloom.InkReveal },
        )
    }

    @Test
    fun `covers keep their own word order`() {
        val reveals = blooms().filterIsInstance<ShapedWordBloom.InkReveal>()
        assertTrue(
            "reveals should stay in word order",
            reveals.map { it.range.first } == reveals.map { it.range.first }.sorted(),
        )
    }
}
