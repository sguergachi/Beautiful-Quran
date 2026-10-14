package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The expected owners come from the input permutation, independently of the mapper. */
class EnglishAlignmentPermutationTest {
    @Test
    fun `all six head orders preserve exact owners and a continuous completed prefix`() {
        val heads = listOf("vision", "brothers", "sisters", "prayer", "commerce", "Allah")
        val text = heads.joinToString(" ")
        val ends = heads.map { text.indexOf(it) + it.length }
        for (order in permutations(heads)) {
            val glosses = order.map { if (it == "commerce") "trade" else it }
            val alignment = EnglishWordAlignment.align(text, glosses)!!
            heads.forEachIndexed { index, head ->
                val expected = order.indexOf(head) + 1
                assertEquals("$order: $head", expected,
                    alignment.positionAt((ends[index] - head.length / 2f) / text.length))
                assertEquals("tap $order: $head", expected - 1,
                    EnglishWordAlignment.ownerAt(text, glosses, text.indexOf(head) + 1))
            }
            for (position in 1..heads.size) {
                val expected = ends.filterIndexed { index, _ -> order.indexOf(heads[index]) < position }.max()
                val reached = alignment.washSpans.filter { it.position <= position }
                assertEquals("$order at $position", expected / text.length.toFloat(), reached.last().to, 1e-6f)
                assertEquals(0f, reached.first().from)
                reached.zipWithNext().forEach { (before, after) -> assertEquals(before.to, after.from) }
            }
            assertTrue(alignment.wordEnds.asList().zipWithNext().all { (a, b) -> a <= b })
            assertEquals(1f, alignment.wordEnds.last())
        }
    }

    @Test
    fun `a prayer action keeps its owner through punctuation reorder and literal alternatives`() {
        for (action in listOf("establishing", "establishment", "performance")) {
            for (text in listOf("performance of prayer", "prayer; performance of prayer", "performance of work. performance of prayer")) {
                val glosses = when {
                    text.startsWith("prayer;") -> listOf("the prayer", action, "the prayer")
                    text.startsWith("performance of work") -> listOf("performance of work", action, "the prayer")
                    else -> listOf(action, "the prayer")
                }
                val alignment = EnglishWordAlignment.align(text, glosses)!!
                val offset = text.lastIndexOf("performance") + 3
                assertEquals("$action: $text", glosses.lastIndex,
                    alignment.positionAt(offset / text.length.toFloat()))
                assertEquals("tap $action: $text", glosses.lastIndex - 1,
                    EnglishWordAlignment.ownerAt(text, glosses, offset))
            }
        }
    }

    private fun <T> permutations(values: List<T>): List<List<T>> =
        if (values.isEmpty()) listOf(emptyList()) else values.flatMap { value ->
            permutations(values - value).map { listOf(value) + it }
        }
}
