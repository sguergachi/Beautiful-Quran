package com.beautifulquran.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishRagTest {
    private val em = 50f
    private val space = 10f

    /**
     * A paragraph of words of these widths, joined by spaces, with hyphen
     * hyphen cuts at the given (word, width-into-word) places.
     */
    private class Para(
        val candidates: EnglishRag.Candidates,
        /** Which candidate each word's following space is; -1 for the last. */
        val spaceOf: IntArray,
    )

    private fun para(
        widths: List<Float>,
        cuts: Map<Int, List<Float>> = emptyMap(),
        hyphen: Float = 12f,
    ): Para {
        val ends = ArrayList<Float>()
        val starts = ArrayList<Float>()
        val hyphens = ArrayList<Float>()
        val spaceOf = IntArray(widths.size) { -1 }
        var x = 0f
        widths.forEachIndexed { i, w ->
            cuts[i].orEmpty().forEach { into ->
                ends += x + into
                starts += x + into
                hyphens += hyphen
            }
            x += w
            if (i < widths.lastIndex) {
                spaceOf[i] = ends.size
                ends += x
                starts += x + space
                hyphens += 0f
                x += space
            }
        }
        return Para(
            EnglishRag.Candidates(
                contentEnd = ends.toFloatArray(),
                nextStart = starts.toFloatArray(),
                hyphenPx = hyphens.toFloatArray(),
                textEnd = x,
            ),
            spaceOf,
        )
    }

    /** Greedy, as the platform breaks with no hyphen: the old leaf. */
    private fun greedyLines(widths: List<Float>, measure: Float): Int {
        var lines = 1
        var x = 0f
        widths.forEach { w ->
            if (x == 0f) {
                x = w
            } else if (x + space + w <= measure - 1f) {
                x += space + w
            } else {
                lines++
                x = w
            }
        }
        return lines
    }

    @Test
    fun `shares a greedy hole with the line above it`() {
        // Greedy fills line one to 990 px and then cannot set the 950 px word
        // after a 100 px one, so line two is that short word alone: a 900 px
        // hole under a full line. Carrying line one's last words down to it
        // leaves two shallower holes (380 and 530 px), which is what a
        // compositor does; one word down would still leave 740.
        val widths = listOf(200f, 200f, 200f, 200f, 150f, 100f, 950f)
        val p = para(widths)
        val breaks = EnglishRag.breaks(p.candidates, 1000f, em)!!
        assertEquals(greedyLines(widths, 1000f), breaks.size + 1)
        assertEquals(p.spaceOf[2], breaks[0])
        assertEquals(p.spaceOf[5], breaks[1])
    }

    @Test
    fun `never sets more lines than greedy`() {
        val widths = List(120) { i -> listOf(40f, 90f, 160f, 30f, 220f, 70f)[i % 6] }
        listOf(600f, 800f, 1000f).forEach { measure ->
            val breaks = EnglishRag.breaks(para(widths).candidates, measure, em)!!
            assertTrue(breaks.size + 1 <= greedyLines(widths, measure))
        }
    }

    @Test
    fun `leaves a line that is already fine alone`() {
        // Every line greedy sets is within the free hole: nothing to improve,
        // and the ties go to the fuller line, so this *is* greedy.
        val widths = List(30) { 90f }
        val p = para(widths)
        val breaks = EnglishRag.breaks(p.candidates, 1000f, em)!!
        // Ten 90 px words and nine spaces are 990: greedy's line.
        assertEquals(p.spaceOf[9], breaks[0])
        assertEquals(p.spaceOf[19], breaks[1])
    }

    @Test
    fun `hyphenates to fill a hole only when no space can`() {
        // Line one ends on 400 px of room and a 400 px word follows: no
        // arrangement of spaces fills it, a cut 200 px into the word does.
        val widths = listOf(600f, 400f, 500f)
        val p = para(widths, cuts = mapOf(1 to listOf(200f)))
        val breaks = EnglishRag.breaks(p.candidates, 1000f, em)!!
        assertTrue(
            "the hyphen is taken",
            breaks.any { p.candidates.hyphenPx[it] > 0f },
        )
        // ...and not where the word fits whole.
        val roomy = para(listOf(600f, 300f, 500f), cuts = mapOf(1 to listOf(150f)))
        val easy = EnglishRag.breaks(roomy.candidates, 1000f, em)!!
        assertTrue(easy.none { roomy.candidates.hyphenPx[it] > 0f })
    }

    @Test
    fun `a paragraph that fits is one line`() {
        val p = para(listOf(100f, 100f))
        assertArrayEquals(IntArray(0), EnglishRag.breaks(p.candidates, 1000f, em))
    }

    @Test
    fun `an unbreakable run wider than the measure is left to the platform`() {
        assertNull(EnglishRag.breaks(para(listOf(1200f, 100f)).candidates, 1000f, em))
    }
}
