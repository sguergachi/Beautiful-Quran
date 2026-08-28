package com.beautifulquran.ui.reader

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The English leaf has no per-word alignment to claim, so its wash says the one
 * thing that is true: how far through the verse the reciter has read. See
 * `domain/EnglishLeaf.kt`.
 */
class EnglishVerseProgressTest {

    @Test
    fun `a verse still to come is not washed at all`() {
        assertEquals(0f, englishVerseReadProgress(verse(active = -1, upcoming = 4)), 0f)
    }

    @Test
    fun `an idle leaf reads as already inked`() {
        assertEquals(1f, englishVerseReadProgress(verse(active = -1, upcoming = 0, plain = 4)), 0f)
    }

    @Test
    fun `the wash is the words behind the voice plus the sweep of the one on it`() {
        // Second of four words, half swept: one whole word plus half of the
        // next, over four.
        assertEquals(
            0.375f,
            englishVerseReadProgress(verse(active = 1, upcoming = 2, recited = 1, sweep = 0.5f)),
            0.0001f,
        )
    }

    @Test
    fun `the first word opens the wash from nothing`() {
        assertEquals(
            0f,
            englishVerseReadProgress(verse(active = 0, upcoming = 3, sweep = 0f)),
            0.0001f,
        )
    }

    @Test
    fun `a finished verse is fully inked`() {
        assertEquals(1f, englishVerseReadProgress(verse(active = -1, upcoming = 0, recited = 4)), 0f)
    }

    @Test
    fun `the wash reads the active word's index, so an unsettled word cannot rewind it`() {
        // The word before the voice has not yet left Upcoming. Counting what
        // is behind would put the wash back at the start of the verse; the
        // index does not.
        val motions = listOf(
            motion(InkEngine.State.Upcoming),
            motion(InkEngine.State.Active, sweep = 1f),
            motion(InkEngine.State.Upcoming),
            motion(InkEngine.State.Upcoming),
        )
        assertEquals(0.5f, englishVerseReadProgress(motions), 0.0001f)
    }

    @Test
    fun `a verse with no clock of its own is left at full ink`() {
        assertEquals(1f, englishVerseReadProgress(emptyList()), 0f)
    }
}

private fun verse(
    active: Int,
    upcoming: Int = 0,
    recited: Int = 0,
    plain: Int = 0,
    sweep: Float = 0f,
): List<InkMotion> {
    val words = ArrayList<InkMotion>()
    repeat(recited) { words += motion(InkEngine.State.Recited) }
    if (active >= 0) {
        while (words.size < active) words += motion(InkEngine.State.Recited)
        words += motion(InkEngine.State.Active, sweep = sweep)
    }
    repeat(upcoming) { words += motion(InkEngine.State.Upcoming) }
    repeat(plain) { words += motion(InkEngine.State.Plain) }
    return words
}

private fun motion(state: InkEngine.State, sweep: Float = 0f) = InkMotion(
    ink = InkEngine.Word(state = state, repeat = false),
    lyricInk = mutableStateOf(state.inkAlpha()),
    sweep = LetterSweep(
        progress = mutableStateOf(sweep),
        feather = mutableStateOf(null),
        pacing = mutableStateOf(null),
    ),
    repeatWash = RepeatWash(
        progress = mutableStateOf(1f),
        alpha = mutableStateOf(0f),
        feather = mutableStateOf(null),
    ),
    glintAlpha = mutableStateOf(0f),
    glintIsRepeat = false,
    glintReplacedByRepeat = false,
    waslPrefix = null,
    tarji = mutableStateOf(InkEngine.GlintResonance.Idle),
)
