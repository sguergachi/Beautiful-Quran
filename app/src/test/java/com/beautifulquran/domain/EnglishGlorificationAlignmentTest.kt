package com.beautifulquran.domain

import org.junit.Assert.*
import org.junit.Test

class EnglishGlorificationAlignmentTest {
    @Test
    fun `glorification inflections and nouns retain reviewed owners and continuous prefixes`() {
        javaClass.getResourceAsStream("/english-glorification-owners.tsv")!!.bufferedReader().useLines { rows ->
            rows.forEach { row ->
                val (key, raw, encodedGlosses, targets, encodedLemmas) = row.split('\t')
                val glosses = encodedGlosses.split('|')
                for (text in listOf(raw, EnglishTypography.typeset(raw))) {
                    val alignment = EnglishWordAlignment.align(text, glosses, encodedLemmas.split('|'))!!
                    targets.split('|').forEach { target ->
                        val (head, owner) = target.split('=')
                        val match = Regex("\\b${Regex.escape(head)}\\b", RegexOption.IGNORE_CASE).find(text)!!
                        val point = (match.range.first + head.length / 2f) / text.length
                        assertEquals("$key $head", owner.toInt(), alignment.positionAt(point))
                        val wash = alignment.washSpans.filter { it.position <= owner.toInt() }
                        assertEquals(0f, wash.first().from, 0f)
                        assertTrue("$key must reach every preceding word", wash.last().to * text.length >= match.range.last)
                    }
                    for (position in glosses.indices) {
                        val wash = alignment.washSpans.filter { it.position <= position + 1 }
                        if (wash.isNotEmpty()) {
                            assertEquals(0f, wash.first().from, 0f)
                            wash.zipWithNext().forEach { (a, b) -> assertEquals(a.to, b.from, 0f) }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `synonym reordering preserves the heard owner instead of delaying the first printed word`() {
        val text = "heavens exalt Allah"
        val heads = listOf("heavens", "glorifies", "Allah")
        for (order in permutations(heads)) {
            val alignment = EnglishWordAlignment.align(text, order)!!
            for ((printed, spoken) in listOf("heavens" to "heavens", "exalt" to "glorifies", "Allah" to "Allah")) {
                val point = (text.indexOf(printed) + printed.length / 2f) / text.length
                assertEquals("$order $printed", order.indexOf(spoken) + 1, alignment.positionAt(point))
            }
            assertEquals(1, alignment.washSpans.first().position)
            assertEquals(0f, alignment.washSpans.first().from, 0f)
        }
    }

    private fun <T> permutations(values: List<T>): List<List<T>> =
        if (values.isEmpty()) listOf(emptyList()) else values.flatMap { value ->
            permutations(values - value).map { listOf(value) + it }
        }
}
