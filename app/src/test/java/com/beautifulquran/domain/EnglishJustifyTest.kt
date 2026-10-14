package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EnglishJustifyTest {
    private val space = 10f

    private fun candidates(widths: List<Float>): EnglishRag.Candidates {
        val ends = ArrayList<Float>()
        val starts = ArrayList<Float>()
        var x = 0f
        widths.forEachIndexed { i, w ->
            x += w
            if (i < widths.lastIndex) {
                ends += x
                starts += x + space
                x += space
            }
        }
        return EnglishRag.Candidates(ends.toFloatArray(), starts.toFloatArray(), x)
    }

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

    /** The drawn width of each line, start to end, as the leaf will set it. */
    private fun drawnWidths(
        c: EnglishRag.Candidates,
        lines: List<EnglishJustify.Line>,
    ): List<Float> {
        var start = 0f
        return lines.map { line ->
            val end = if (line.end < 0) c.textEnd else c.contentEnd[line.end]
            val natural = end - start
            if (line.end >= 0) start = c.nextStart[line.end]
            natural * (1 + line.expansion) + line.spaces * line.spaceExtraPx
        }
    }

    @Test
    fun `sets as many lines as greedy wrapping for every prefix`() {
        val rnd = Random(7)
        val words = List(400) { 20f + rnd.nextInt(140) }
        for (count in 5..words.size step 7) {
            val widths = words.take(count)
            val lines = EnglishJustify.set(candidates(widths), 1000f, space)!!
            assertEquals(greedyLines(widths, 1000f), lines.size)
        }
    }

    @Test
    fun `every line but the last reaches the measure, and the last does not`() {
        val rnd = Random(11)
        val widths = List(200) { 30f + rnd.nextInt(120) }
        val c = candidates(widths)
        val lines = EnglishJustify.set(c, 1000f, space)!!
        val drawn = drawnWidths(c, lines)
        lines.dropLast(1).forEachIndexed { i, line ->
            assertTrue(line.justified)
            assertEquals(999f, drawn[i], 0.01f)
        }
        assertFalse(lines.last().justified)
        assertTrue(drawn.last() <= 999f)
    }

    @Test
    fun `letters never widen past the expansion limit`() {
        val rnd = Random(3)
        val widths = List(300) { 10f + rnd.nextInt(300) }
        val lines = EnglishJustify.set(candidates(widths), 1000f, space)!!
        lines.forEach { assertTrue(it.expansion <= EnglishJustify.Tuning().expansion + 1e-6f) }
    }

    @Test
    fun `evens a loose line out with the line above it`() {
        // Greedy takes four 200s and a 150 onto line one (990 px, nearly no
        // stretch) and leaves line two at 100 + 700 = 810 px: one tight line
        // over one loose one. Moving the 150 down gives 830 and 960 — both
        // stretch a little, which is what an even page is.
        val widths = listOf(200f, 200f, 200f, 200f, 150f, 100f, 700f, 900f)
        val c = candidates(widths)
        val lines = EnglishJustify.set(c, 1000f, space)!!
        assertEquals(3, lines.size)
        assertEquals(3, lines[0].end)
        val loosest = lines.dropLast(1).maxOf { it.spaceExtraPx }
        val greedyLoose = EnglishJustify.drawnOut(5, 810f, 1, 999f, space).spaceExtraPx
        assertTrue(loosest < greedyLoose)
    }

    @Test
    fun `a carried last line is drawn out when asked`() {
        val widths = List(30) { 90f }
        val c = candidates(widths)
        val lines = EnglishJustify.set(c, 1000f, space, justifyLast = true)!!
        val drawn = drawnWidths(c, lines)
        // 30 words of 90 at ten to a full line: the last is full too.
        assertTrue(lines.last().justified)
        assertEquals(999f, drawn.last(), 0.01f)
    }

    @Test
    fun `a carried last line too short to draw out is left as it stands`() {
        val widths = List(21) { 90f }
        val lines = EnglishJustify.set(candidates(widths), 1000f, space, justifyLast = true)!!
        assertFalse(lines.last().justified)
    }

    @Test
    fun `a free line count sets a paragraph no looser than the kept one`() {
        val rnd = Random(5)
        val widths = List(300) { 20f + rnd.nextInt(160) }
        val c = candidates(widths)
        val kept = EnglishJustify.set(c, 1000f, space)!!
        val free = EnglishJustify.set(
            c, 1000f, space,
            tuning = EnglishJustify.Tuning(idealSpace = 1.2f, shrink = 0.25f, keepLineCount = false),
        )!!
        val drawn = drawnWidths(c, free)
        free.dropLast(1).forEachIndexed { i, line ->
            if (line.justified) assertEquals(999f, drawn[i], 0.01f)
        }
        val loosest = { ls: List<EnglishJustify.Line> -> ls.dropLast(1).maxOf { it.spaceExtraPx } }
        assertTrue(loosest(free) <= loosest(kept))
    }

    /** Gaps and letters for words of these widths, one letter per 10 px, as the leaf counts them. */
    private fun text(widths: List<Float>): EnglishJustify.Text {
        val chars = StringBuilder()
        val offsets = ArrayList<Int>()
        widths.forEachIndexed { i, w ->
            repeat((w / 10).toInt().coerceAtLeast(1)) { chars.append('a') }
            if (i < widths.lastIndex) {
                offsets += chars.length
                chars.append(' ')
            }
        }
        val source = chars.toString()
        val gaps = IntArray(source.length + 1)
        val letters = IntArray(source.length + 1)
        source.forEachIndexed { i, c ->
            gaps[i + 1] = gaps[i] + if (c == ' ') 1 else 0
            letters[i + 1] = letters[i] + if (c == ' ') 0 else 1
        }
        return EnglishJustify.Text(gaps, letters, offsets.toIntArray(), emPx = 50f)
    }

    /** The drawn width with letter tracking, as the leaf sets each line. */
    private fun drawnWithTracking(
        c: EnglishRag.Candidates,
        lines: List<EnglishJustify.Line>,
    ): List<Float> = drawnWidths(c, lines).mapIndexed { i, w -> w + lines[i].letters * lines[i].trackingPx }

    @Test
    fun `letter tracking shares the slack and every line still reaches the measure`() {
        val rnd = Random(17)
        val widths = List(250) { 20f + rnd.nextInt(140) }
        val c = candidates(widths)
        val tuning = EnglishJustify.Tuning(idealSpace = 1.2f, shrink = 0.25f, expansion = 0.03f, tracking = 0.0075f, keepLineCount = false)
        val tracked = EnglishJustify.set(c, 1000f, space, tuning = tuning, text = text(widths))!!
        val drawn = drawnWithTracking(c, tracked)
        tracked.dropLast(1).forEachIndexed { i, line ->
            if (line.justified) assertEquals(999f, drawn[i], 0.05f)
            assertTrue(kotlin.math.abs(line.trackingPx) <= 0.0075f * 50f + 1e-4f)
        }
        // Spread over the letters too, the word spaces open less than without.
        val untracked = EnglishJustify.set(c, 1000f, space, tuning = tuning.copy(tracking = 0f), text = text(widths))!!
        val loosest = { ls: List<EnglishJustify.Line> -> ls.dropLast(1).filter { it.justified }.maxOf { it.spaceExtraPx } }
        assertTrue(loosest(tracked) < loosest(untracked))
    }

    @Test
    fun `smoothness keeps neighbouring lines alike`() {
        val rnd = Random(23)
        val widths = List(400) { 20f + rnd.nextInt(160) }
        val c = candidates(widths)
        val base = EnglishJustify.Tuning(idealSpace = 1.2f, shrink = 0.25f, keepLineCount = false)
        fun jumps(lines: List<EnglishJustify.Line>) = lines.dropLast(1).zipWithNext { a, b ->
            kotlin.math.abs(a.spaceExtraPx - b.spaceExtraPx)
        }.sum()
        val rough = EnglishJustify.set(c, 1000f, space, tuning = base)!!
        val smooth = EnglishJustify.set(c, 1000f, space, tuning = base.copy(smoothness = 5f))!!
        assertTrue(jumps(smooth) <= jumps(rough))
    }

    @Test
    fun `a carried last line counts like any line, so the leaf ends full`() {
        // The last line free, a paragraph can leave its last word alone on it;
        // carried on, that stub costs, and the setting fills it instead.
        val widths = List(31) { 90f }
        val c = candidates(widths)
        val tuning = EnglishJustify.Tuning(idealSpace = 1.2f, shrink = 0.25f, keepLineCount = false)
        val carried = EnglishJustify.set(c, 1000f, space, justifyLast = true, tuning = tuning)!!
        val lastWidth = drawnWidths(c, carried).last()
        assertTrue(carried.last().justified || lastWidth > 500f)
    }

    @Test
    fun `the leaf's cache key changes with its tuning`() {
        assertTrue(EnglishJustify.cacheKey.contains(EnglishJustify.LEAF.toString()))
        assertTrue(EnglishJustify.cacheKey.contains("justify${EnglishJustify.VERSION}"))
    }

    @Test
    fun `an unbreakable run wider than the measure cannot be set`() {
        val lines = EnglishJustify.set(candidates(listOf(100f, 1200f, 100f)), 1000f, space)
        assertEquals(null, lines)
        assertNotNull(EnglishJustify.set(candidates(listOf(100f, 100f)), 1000f, space))
    }

    @Test
    fun `a wide word cannot borrow shrink that its line will not draw`() {
        for (widths in listOf(listOf(1010f), listOf(100f, 1010f, 100f))) {
            assertEquals(
                null,
                EnglishJustify.set(candidates(widths), 1000f, space, tuning = EnglishJustify.LEAF),
            )
        }
        assertNotNull(EnglishJustify.set(candidates(listOf(999f)), 1000f, space, tuning = EnglishJustify.LEAF))
    }

    @Test
    fun `a wide word can still share a shrinking line with its neighbour`() {
        val c = candidates(listOf(1005f, 1f, 900f))
        val lines = EnglishJustify.set(c, 1000f, space, tuning = EnglishJustify.LEAF)!!
        assertEquals(2, lines.size)
        assertEquals(1, lines.first().end)
        drawnWidths(c, lines).forEach { assertTrue(it <= 999.01f) }
    }
}

