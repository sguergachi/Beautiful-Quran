package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixed meaning correspondences, including words a literal diagnostic cannot judge. */
class EnglishSemanticAlignmentTest {
    @Test
    fun `prayer performance and establishment keep their separately spoken heads`() {
        verifyCases("english-prayer-alignment.tsv", 2)
    }

    @Test
    fun `a performance outside the prayer phrase does not mean establishing`() {
        assertNull(EnglishWordAlignment.ownerAt(
            "performance of work and prayer", listOf("establishing", "of work", "and", "prayer"), 3,
        ))
    }

    @Test
    fun `commerce and sale keep distinct owners when reordered`() {
        val alignment = EnglishWordAlignment.align("commerce and sale", listOf("sale", "trade"))!!
        assertEquals(2, alignment.positionAt(0.1f))
        assertEquals(1, alignment.positionAt(0.9f))
    }

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

    @Test
    fun `corpus audit cases reach the spoken verb phrase title and name`() {
        verifyCases("english-semantic-patterns.tsv", 56, exact = false)
    }

    @Test
    fun `Rahman and Rahim stay distinct when both occur in reordered prose`() {
        val text = "Especially Merciful and Entirely Merciful"
        val alignment = EnglishWordAlignment.align(text, listOf("Most Gracious", "Most Merciful"))!!
        assertEquals(2, alignment.positionAt(0.2f))
        assertEquals(1, alignment.positionAt(0.9f))
        assertEquals(1, alignment.washSpans.single().position)
    }

    @Test
    fun `an explicit deed keeps its owner before the adjective evil`() {
        val text = "evil deeds"
        val alignment = EnglishWordAlignment.align(text, listOf("deeds", "evil"))!!
        assertEquals(1, alignment.positionAt(0.8f))
        assertEquals(2, alignment.positionAt(0.2f))
        assertEquals(1, alignment.washSpans.single().position)
    }

    @Test
    fun `an incomplete evil gloss cannot acquire the following noun`() {
        val alignment = EnglishWordAlignment.align("evil deeds", listOf("evil thoughts", "deeds"))!!
        assertEquals(2, alignment.positionAt(0.8f))
    }

    @Test
    fun `phrases cannot combine heads explicitly spoken by different Arabic words`() {
        for ((text, glosses, heads) in listOf(
            Triple("give life", listOf("give", "life"), listOf("give", "life")),
            Triple("acceptor of repentance", listOf("acceptor", "of repentance"), listOf("acceptor", "repentance")),
            Triple("come to know", listOf("come", "to know"), listOf("come", "know")),
            Triple("seek to hasten", listOf("seek", "to hasten"), listOf("seek", "hasten")),
            Triple("seems suitable", listOf("seems", "suitable"), listOf("seems", "suitable")),
            Triple("my way", listOf("my", "way"), listOf("my", "way")),
        )) {
            val alignment = EnglishWordAlignment.align(text, glosses)!!
            heads.forEachIndexed { index, head ->
                assertEquals(text, index + 1, alignment.positionAt((text.indexOf(head) + 1f) / text.length))
            }
        }
    }

    @Test
    fun `literal brought remains distinct from come when both are spoken`() {
        val text = "came and brought"
        val alignment = EnglishWordAlignment.align(text, listOf("brought", "came"))!!
        assertEquals(2, alignment.positionAt(0.1f))
        assertEquals(1, alignment.positionAt(0.8f))
    }

    private fun verifyCases(resource: String, verses: Int, exact: Boolean = true) {
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
                if (exact && actual != position.toInt()) failures += "$verse $target was $actual"
                val end = (at + word.length - 0.5f) / text.length
                val washed = alignment.washSpans.single { end >= it.from && end < it.to }.position
                if (washed > position.toInt()) failures += "$verse $target wash was $washed"
            }
        }
        assertEquals(emptyList<String>(), failures)
    }
}
