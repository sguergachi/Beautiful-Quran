package com.beautifulquran.ui.reader

import androidx.compose.ui.graphics.Color
import com.beautifulquran.ui.theme.ShapedWordBloom
import org.junit.Assert.*
import org.junit.Test

class EnglishWordWashCoverTest {
    private fun cover(range: IntRange, progress: Float, recited: Float = 0f) =
        englishWordWashCover(range, progress, Color.White, .22f, .78f, recited)

    @Test
    fun `drying out cannot put a phrase cover back over a completed printed word`() {
        // Equal character progress is not equal pixel distance: WW can be
        // wider than the entire following run of narrow letters.
        val text = "WW iiiiiiiiii"
        val words = englishPaperInkWordRanges(text, listOf(text.indices)).getValue(text.indices)
        val edge = words.first().last + 1f
        val first = englishPaperInkWordProgress(edge, words[0].first, words[0].last + 1)
        val next = englishPaperInkWordProgress(edge, words[1].first, words[1].last + 1)
        for (wet in listOf(1f, .5f, 0f)) {
            assertNull("the base ink stays fully revealed when its material retires", cover(words[0], first))
            assertFalse(englishPaperInkWordVisible(next, wet, departing = false))
            assertEquals(.78f, (cover(words[1], next) as ShapedWordBloom.UpcomingDim).coverAlpha, 0f)
        }
        assertFalse(englishPaperInkWordVisible(first, 0f, departing = false))
    }

    @Test
    fun `one soft printed edge never skips words or follows the owner's wetness ahead`() {
        val text = "Whatever is in the heavens"
        val words = englishPaperInkWordRanges(text, listOf(text.indices)).getValue(text.indices)
        for (tick in 0..text.length * 8) {
            val edge = tick / 8f
            var partial = 0
            words.forEach { range ->
                val progress = englishPaperInkWordProgress(edge, range.first, range.last + 1)
                val bloom = cover(range, progress)
                when {
                    range.last + 1 <= edge -> assertNull(bloom)
                    range.first >= edge -> {
                        assertTrue(bloom is ShapedWordBloom.UpcomingDim)
                        if (range.first > edge) assertFalse(englishPaperInkWordVisible(progress, 1f, false))
                    }
                    else -> {
                        partial++
                        assertTrue(bloom is ShapedWordBloom.InkReveal)
                        assertEquals(range, bloom!!.range)
                        assertEquals(progress, (bloom as ShapedWordBloom.InkReveal).progress, 0f)
                        assertNull("keep the standard soft feather", bloom.feather)
                    }
                }
            }
            assertTrue("only one printed word can carry the forward edge", partial <= 1)
        }
    }

    @Test
    fun `seek recess and deposited departure keep their existing cover and material`() {
        assertEquals(.4f, (cover(0..7, 1f, recited = .4f) as ShapedWordBloom.UpcomingDim).coverAlpha, 0f)
        assertTrue(englishPaperInkWordVisible(1f, 0f, departing = true))
        assertTrue(englishPaperInkWordVisible(.5f, 0f, departing = true))
        assertFalse(englishPaperInkWordVisible(-1f, 1f, departing = true))
        assertEquals(.78f, (cover(12..18, -1f) as ShapedWordBloom.UpcomingDim).coverAlpha, 0f)
    }

    @Test
    fun `short opening words stay laid while the verse is still lifting from recess`() {
        val entry = EnglishWashEntry()
        entry.onFrame(1, 7, .2f)
        for (edge in listOf(8f, 11f, 14f, 25f)) {
            entry.onFrame(1, 7, edge)
            assertEquals(0f, entry.recitedCover(0..7, .7f), 0f)
            assertEquals(0f, entry.recitedCover(9..10, .7f), 0f)
        }
        // A middle-word seek still lifts the prefix already present at entry.
        entry.onFrame(2, 7, 13f)
        assertEquals(.7f, entry.recitedCover(0..7, .7f), 0f)
        assertEquals(.7f, entry.recitedCover(9..10, .7f), 0f)
        assertEquals(0f, entry.recitedCover(12..13, .7f), 0f)
        entry.onFrame(2, 1, .2f)
        assertEquals(0f, entry.recitedCover(0..7, .7f), 0f)
    }

    @Test
    fun `translation letter feather retains unread entry and completed ink without changing card defaults`() {
        for (range in listOf(0..0, 0..1, 0..7, 0..15)) {
            fun translation(progress: Float) = englishWordWashCover(range, progress, Color.White, .22f, .78f, 0f,
                letterFeather = true)
            assertEquals(.78f, (translation(0f) as ShapedWordBloom.UpcomingDim).coverAlpha, 0f)
            assertNull(translation(1f))
            val partial = translation(.5f) as ShapedWordBloom.InkReveal
            assertEquals(1f, partial.feather!! * range.count(), .000001f)
            assertEquals(.5f, partial.progress, 0f)
            assertNull((cover(range, .5f) as ShapedWordBloom.InkReveal).feather)
        }
    }
}
