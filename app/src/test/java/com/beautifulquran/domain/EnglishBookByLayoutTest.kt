package com.beautifulquran.domain

import com.beautifulquran.data.model.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The book paginated by measuring rather than by counting. The ruler here is a
 * stand-in that fits a fixed number of characters, which is all the pagination
 * needs to know about it — on a device it is a text layout.
 */
class EnglishBookByLayoutTest {

    private fun source(surahId: Int, ayah: Int, page: Int) = MushafSourceWord(
        surahId = surahId,
        ayah = ayah,
        word = Word(
            position = 1,
            arabic = "\u0648",
            translation = "",
            transliteration = "",
            qcfPage = page,
            qcfLine = 1,
        ),
    )

    /**
     * A ruler whose leaf holds [holds] characters, standing in for a layout.
     * Null when the offer did not fill it, which is the answer that asks for
     * more — the same contract a real layout answers with.
     */
    private fun ruler(holds: Int) = EnglishLeafRuler { _, runs ->
        // A line of this stand-in holds ten characters.
        var room = holds
        var used = 0
        for ((k, run) in runs.withIndex()) {
            val len = run.to - run.from
            if (len + 1 <= room) {
                room -= len + 1
                used += len + 1
            } else {
                return@EnglishLeafRuler EnglishLeafFill(
                    lines = (used + room) / 10,
                    cut = EnglishRulerCut(k, run.from + room.coerceAtLeast(1)),
                )
            }
        }
        EnglishLeafFill(lines = used / 10, cut = null)
    }

    private fun book(vararg pages: Pair<Int, List<Pair<Int, Int>>>, text: (Int, Int) -> String) =
        buildEnglishBookByLayout(
            buildMushafCatalog(pages.flatMap { (page, verses) -> verses.map { (s, a) -> source(s, a, page) } }),
            text,
            ruler(holds = 100),
        )

    @Test
    fun `the leaf takes exactly what the ruler says it holds`() {
        val b = book(3 to listOf(2 to 2, 2 to 3)) { _, _ -> "x".repeat(80) }
        val leaves = b.leaves
        assertEquals(2, leaves.size)
        // 80 of the first verse, then 19 of the second: the ruler's hundred.
        assertEquals(listOf(2 to 2, 2 to 3), leaves[0].verses)
        assertEquals(19, leaves[0].runs.last().let { it.to - it.from })
        assertEquals(19, leaves[1].runs.first().from)
    }

    @Test
    fun `a leaf the offer could not fill is offered more`() {
        // The ruler answers null when the run it was handed all fitted, and the
        // pagination widens the offer rather than ending the leaf short. Sixty
        // verses of ten characters is well past the first offer of 24.
        val verses = (2..80).map { 2 to it }
        val b = book(3 to verses) { _, _ -> "0123456789" }
        // A leaf holds a hundred characters, so nine of these verses at eleven
        // apiece: nothing near the offer decides it.
        assertTrue("offer must not decide the leaf", b.leaves.all { it.verses.size <= 10 })
        assertTrue(b.leaves.size >= 8)
    }

    @Test
    fun `a chapter still opens a leaf of its own`() {
        val b = book(3 to listOf(2 to 5, 3 to 1)) { _, _ -> "x".repeat(20) }
        assertEquals(2, b.leaves.size)
        assertEquals(listOf(2 to 5), b.leaves[0].verses)
        assertEquals(listOf(3 to 1), b.leaves[1].verses)
    }

    @Test
    fun `every verse lands on exactly one leaf, in order, with nothing dropped`() {
        val verses = (2..30).map { 2 to it }
        val b = book(3 to verses) { _, a -> "word ".repeat(a % 7 + 3).trim() }
        var expectedFrom = 0
        var last: Pair<Int, Int>? = null
        b.leaves.forEach { leaf ->
            leaf.runs.forEach { run ->
                val key = run.surahId to run.ayah
                if (key != last) {
                    assertEquals("a verse must start where the last one ended", 0, run.from)
                    last = key
                } else {
                    assertEquals("a carried verse resumes where it stopped", expectedFrom, run.from)
                }
                expectedFrom = run.to
            }
        }
        assertTrue("the book must not be empty", b.leaves.isNotEmpty())
        assertEquals(2 to 30, last)
    }

    @Test
    fun `a ruler that says a leaf holds nothing still advances`() {
        // Not a case a well of any size produces, but the book must not hang.
        val b = buildEnglishBookByLayout(
            buildMushafCatalog(listOf(source(2, 2, 3), source(2, 3, 3))),
            { _, _ -> "x".repeat(50) },
            { _, _ -> EnglishLeafFill(1, EnglishRulerCut(0, 0)) },
        )
        assertTrue(b.leaves.size in 1..200)
    }
}
