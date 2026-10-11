package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixed meaning correspondences, including words a literal diagnostic cannot judge. */
class EnglishSemanticAlignmentTest {
    @Test
    fun `reported verses keep the Arabic owners of their clear content heads`() {
        verifyCases("english-semantic-heads.tsv", 2)
    }

    @Test
    fun `wrap does not mean draw outside the headcover phrase`() {
        assertNull(EnglishWordAlignment.ownerAt("wrap a gift", listOf("draw", "a gift"), 2))
    }

    @Test
    fun `the All-Aware phrase does not merge ordinary awareness with acquaintance`() {
        val text = "The Acquainted and those aware"
        val alignment = EnglishWordAlignment.align(text, listOf("the All-Aware", "and", "those", "aware"))!!
        assertEquals(1, alignment.positionAt(0.2f))
        assertEquals(4, alignment.positionAt(0.9f))
    }

    @Test
    fun `Acquainted belongs to Khabeer in every published occurrence`() {
        verifyCases("english-semantic-alignment.tsv", 44)
    }

    private fun verifyCases(resource: String, verses: Int) {
        val cases = javaClass.getResourceAsStream("/$resource")!!
            .bufferedReader().use { it.readLines() }
        assertEquals(verses, cases.size)
        val failures = mutableListOf<String>()
        for (line in cases) {
            val (verse, text, glosses, targets) = line.split('\t')
            val alignment = EnglishWordAlignment.align(text, glosses.split('|'))!!
            for (target in targets.split('|')) {
                val match = Regex("(.+?)(?:#(\\d+))?=(\\d+)").matchEntire(target)!!
                val (word, occurrence, position) = match.destructured
                val at = Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE)
                    .findAll(text).elementAt((occurrence.toIntOrNull() ?: 1) - 1).range.first
                val actual = alignment.positionAt((at + word.length / 2f) / text.length)
                if (actual != position.toInt()) failures += "$verse $target was $actual"
            }
        }
        assertEquals(emptyList<String>(), failures)
    }
}
